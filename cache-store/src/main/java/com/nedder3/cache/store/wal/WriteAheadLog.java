package com.nedder3.cache.store.wal;

import com.nedder3.cache.core.clock.VectorClock;
import com.nedder3.cache.core.event.CacheEvent;
import com.nedder3.cache.core.event.DeleteEvent;
import com.nedder3.cache.core.event.EvictEvent;
import com.nedder3.cache.core.event.PutEvent;
import com.nedder3.cache.core.model.CacheKey;
import com.nedder3.cache.core.model.EvictionReason;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

public class WriteAheadLog implements Closeable {

    private static final byte MAGIC_WAL_1 = (byte) 0x57; // 'W'
    private static final byte MAGIC_WAL_2 = (byte) 0x41; // 'A'
    private static final byte MAGIC_WAL_3 = (byte) 0x4C; // 'L'
    private static final byte MAGIC_WAL_4 = (byte) 0x01; // v1

    private static final byte TYPE_PUT = 0x01;
    private static final byte TYPE_DELETE = 0x02;
    private static final byte TYPE_EVICT = 0x03;

    private final Path walDir;
    private final long maxSegmentSizeBytes;
    private final ReentrantLock lock = new ReentrantLock();

    private long currentSegmentIndex = 0;
    private FileChannel currentChannel;
    private long currentSegmentSize = 0;
    private boolean closed = false;

    public WriteAheadLog(Path walDir, long maxSegmentSizeBytes) throws IOException {
        this.walDir = walDir;
        this.maxSegmentSizeBytes = maxSegmentSizeBytes;
        Files.createDirectories(walDir);
        initCurrentSegment();
    }

    private void initCurrentSegment() throws IOException {
        List<Long> indices = listSegmentIndices();
        if (indices.isEmpty()) {
            currentSegmentIndex = 1;
        } else {
            currentSegmentIndex = indices.get(indices.size() - 1);
        }
        openSegment(currentSegmentIndex);
    }

    private void openSegment(long index) throws IOException {
        if (currentChannel != null && currentChannel.isOpen()) {
            currentChannel.force(true);
            currentChannel.close();
        }
        Path segmentPath = walDir.resolve(String.format("wal-%08d.log", index));
        currentChannel = FileChannel.open(segmentPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        currentSegmentSize = currentChannel.size();
        currentChannel.position(currentSegmentSize);
    }

    private List<Long> listSegmentIndices() throws IOException {
        try (Stream<Path> stream = Files.list(walDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.startsWith("wal-") && name.endsWith(".log"))
                    .map(name -> {
                        try {
                            String num = name.substring(4, name.length() - 4);
                            return Long.parseLong(num);
                        } catch (NumberFormatException e) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .sorted()
                    .toList();
        }
    }

    public void append(CacheEvent event) throws IOException {
        lock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("WAL is closed");
            }

            byte[] encodedRecord = encodeEvent(event);
            int recordLength = encodedRecord.length;

            if (currentSegmentSize > 0 && (currentSegmentSize + recordLength > maxSegmentSizeBytes)) {
                currentSegmentIndex++;
                openSegment(currentSegmentIndex);
            }

            ByteBuffer buffer = ByteBuffer.wrap(encodedRecord);
            while (buffer.hasRemaining()) {
                currentChannel.write(buffer);
            }
            currentSegmentSize += recordLength;
        } finally {
            lock.unlock();
        }
    }

    public void flush() throws IOException {
        lock.lock();
        try {
            if (currentChannel != null && currentChannel.isOpen()) {
                currentChannel.force(false);
            }
        } finally {
            lock.unlock();
        }
    }

    public List<CacheEvent> replayAll() throws IOException {
        lock.lock();
        try {
            flush();
            List<CacheEvent> events = new ArrayList<>();
            List<Long> indices = listSegmentIndices();

            for (Long index : indices) {
                Path segmentPath = walDir.resolve(String.format("wal-%08d.log", index));
                if (!Files.exists(segmentPath)) continue;

                byte[] allBytes = Files.readAllBytes(segmentPath);
                ByteBuffer buf = ByteBuffer.wrap(allBytes);

                while (buf.remaining() >= 8) { // Minimum header: magic (4) + length (4)
                    int mark = buf.position();
                    byte m1 = buf.get();
                    byte m2 = buf.get();
                    byte m3 = buf.get();
                    byte m4 = buf.get();

                    if (m1 != MAGIC_WAL_1 || m2 != MAGIC_WAL_2 || m3 != MAGIC_WAL_3 || m4 != MAGIC_WAL_4) {
                        // Corrupted or incomplete record, stop reading this segment
                        break;
                    }

                    int length = buf.getInt();
                    if (length <= 0 || buf.remaining() < length) {
                        // Incomplete record payload, stop safely
                        break;
                    }

                    byte[] payload = new byte[length];
                    buf.get(payload);

                    try {
                        CacheEvent event = decodeEvent(payload);
                        events.add(event);
                    } catch (Exception e) {
                        // Malformed payload, stop segment recovery
                        break;
                    }
                }
            }
            return events;
        } finally {
            lock.unlock();
        }
    }

    private byte[] encodeEvent(CacheEvent event) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);

        switch (event) {
            case PutEvent put -> {
                out.writeByte(TYPE_PUT);
                writeKey(out, put.key());
                writeVectorClock(out, put.vectorClock());
                out.writeLong(put.timestamp());
                byte[] valBytes = put.serializedValue();
                out.writeInt(valBytes.length);
                out.write(valBytes);
            }
            case DeleteEvent del -> {
                out.writeByte(TYPE_DELETE);
                writeKey(out, del.key());
                writeVectorClock(out, del.vectorClock());
                out.writeLong(del.timestamp());
            }
            case EvictEvent ev -> {
                out.writeByte(TYPE_EVICT);
                writeKey(out, ev.key());
                byte[] reasonBytes = ev.reason().name().getBytes(StandardCharsets.UTF_8);
                out.writeShort(reasonBytes.length);
                out.write(reasonBytes);
                out.writeLong(ev.timestamp());
            }
            default -> throw new IllegalArgumentException("Unknown CacheEvent type: " + event.getClass());
        }

        out.flush();
        byte[] eventPayload = baos.toByteArray();

        // Wrap with WAL envelope: Magic (4) + Length (4) + Payload
        ByteArrayOutputStream envelopeBaos = new ByteArrayOutputStream();
        DataOutputStream envOut = new DataOutputStream(envelopeBaos);
        envOut.writeByte(MAGIC_WAL_1);
        envOut.writeByte(MAGIC_WAL_2);
        envOut.writeByte(MAGIC_WAL_3);
        envOut.writeByte(MAGIC_WAL_4);
        envOut.writeInt(eventPayload.length);
        envOut.write(eventPayload);
        envOut.flush();

        return envelopeBaos.toByteArray();
    }

    private CacheEvent decodeEvent(byte[] payload) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        byte type = in.readByte();

        return switch (type) {
            case TYPE_PUT -> {
                CacheKey key = readKey(in);
                VectorClock clock = readVectorClock(in);
                long timestamp = in.readLong();
                int valLen = in.readInt();
                byte[] valBytes = new byte[valLen];
                in.readFully(valBytes);
                yield new PutEvent(key, valBytes, clock, timestamp);
            }
            case TYPE_DELETE -> {
                CacheKey key = readKey(in);
                VectorClock clock = readVectorClock(in);
                long timestamp = in.readLong();
                yield new DeleteEvent(key, clock, timestamp);
            }
            case TYPE_EVICT -> {
                CacheKey key = readKey(in);
                int rLen = in.readShort() & 0xFFFF;
                byte[] rBytes = new byte[rLen];
                in.readFully(rBytes);
                String reasonStr = new String(rBytes, StandardCharsets.UTF_8);
                EvictionReason reason = EvictionReason.valueOf(reasonStr);
                long timestamp = in.readLong();
                yield new EvictEvent(key, reason, timestamp);
            }
            default -> throw new IllegalArgumentException("Unknown event type byte: " + type);
        };
    }

    private void writeKey(DataOutputStream out, CacheKey key) throws IOException {
        byte[] ns = key.namespace().getBytes(StandardCharsets.UTF_8);
        out.writeShort(ns.length);
        out.write(ns);
        byte[] k = key.key().getBytes(StandardCharsets.UTF_8);
        out.writeShort(k.length);
        out.write(k);
    }

    private CacheKey readKey(DataInputStream in) throws IOException {
        int nsLen = in.readShort() & 0xFFFF;
        byte[] ns = new byte[nsLen];
        in.readFully(ns);
        int kLen = in.readShort() & 0xFFFF;
        byte[] k = new byte[kLen];
        in.readFully(k);
        return new CacheKey(new String(ns, StandardCharsets.UTF_8), new String(k, StandardCharsets.UTF_8));
    }

    private void writeVectorClock(DataOutputStream out, VectorClock clock) throws IOException {
        Map<String, Long> map = clock.counters();
        out.writeShort(map.size());
        for (Map.Entry<String, Long> entry : map.entrySet()) {
            byte[] nodeBytes = entry.getKey().getBytes(StandardCharsets.UTF_8);
            out.writeShort(nodeBytes.length);
            out.write(nodeBytes);
            out.writeLong(entry.getValue());
        }
    }

    private VectorClock readVectorClock(DataInputStream in) throws IOException {
        int size = in.readShort() & 0xFFFF;
        Map<String, Long> map = new HashMap<>(size);
        for (int i = 0; i < size; i++) {
            int nodeLen = in.readShort() & 0xFFFF;
            byte[] nodeBytes = new byte[nodeLen];
            in.readFully(nodeBytes);
            long counter = in.readLong();
            map.put(new String(nodeBytes, StandardCharsets.UTF_8), counter);
        }
        return new VectorClock(map);
    }

    @Override
    public void close() throws IOException {
        lock.lock();
        try {
            closed = true;
            if (currentChannel != null && currentChannel.isOpen()) {
                currentChannel.force(true);
                currentChannel.close();
            }
        } finally {
            lock.unlock();
        }
    }
}
