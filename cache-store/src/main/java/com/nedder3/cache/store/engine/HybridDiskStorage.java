package com.nedder3.cache.store.engine;

import com.nedder3.cache.core.clock.VectorClock;
import com.nedder3.cache.core.event.CacheEvent;
import com.nedder3.cache.core.event.DeleteEvent;
import com.nedder3.cache.core.event.EvictEvent;
import com.nedder3.cache.core.event.PutEvent;
import com.nedder3.cache.core.model.CacheEntry;
import com.nedder3.cache.core.model.CacheKey;
import com.nedder3.cache.core.model.EvictionReason;
import com.nedder3.cache.core.port.SerializerPort;
import com.nedder3.cache.core.port.StoragePort;
import com.nedder3.cache.store.serializer.JavaNativeSerializer;
import com.nedder3.cache.store.wal.WriteAheadLog;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Production-ready hybrid in-memory + WAL + disk snapshot storage adapter.
 */
public class HybridDiskStorage<V> implements StoragePort<V>, Closeable {

    private final Path baseDir;
    private final WriteAheadLog wal;
    private final SerializerPort<V> serializer;
    private final Map<CacheKey, CacheEntry<V>> memoryMap = new ConcurrentHashMap<>();
    private final List<StorageEventListener> listeners = new CopyOnWriteArrayList<>();
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    @SuppressWarnings("unchecked")
    public HybridDiskStorage(Path baseDir, long walSegmentSizeBytes, SerializerPort<V> serializer) throws IOException {
        this.baseDir = baseDir;
        this.serializer = serializer != null ? serializer : (SerializerPort<V>) new JavaNativeSerializer<Object>();
        Files.createDirectories(baseDir);
        this.wal = new WriteAheadLog(baseDir.resolve("wal"), walSegmentSizeBytes);
        recoverFromDisk();
    }

    private void recoverFromDisk() throws IOException {
        rwLock.writeLock().lock();
        try {
            // 1. Load latest snapshot if exists
            Map<CacheKey, byte[]> snapshot = loadLatestSnapshot();
            for (Map.Entry<CacheKey, byte[]> entry : snapshot.entrySet()) {
                try {
                    V val = serializer.deserialize(entry.getValue());
                    CacheEntry<V> cacheEntry = new CacheEntry<>(
                            entry.getKey(),
                            val,
                            new VectorClock(Map.of()),
                            System.currentTimeMillis(),
                            java.util.OptionalLong.empty()
                    );
                    memoryMap.put(entry.getKey(), cacheEntry);
                } catch (Exception e) {
                    // Ignore corrupted snapshot entry
                }
            }

            // 2. Replay WAL events on top of snapshot
            List<CacheEvent> events = wal.replayAll();
            for (CacheEvent event : events) {
                applyEventToMemory(event);
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    private void applyEventToMemory(CacheEvent event) {
        if (event instanceof PutEvent put) {
            try {
                V val = serializer.deserialize(put.serializedValue());
                CacheEntry<V> entry = new CacheEntry<>(
                        put.key(),
                        val,
                        put.vectorClock(),
                        put.timestamp(),
                        java.util.OptionalLong.empty()
                );
                memoryMap.put(put.key(), entry);
            } catch (Exception e) {
                // Ignore deserialization issue during replay
            }
        } else if (event instanceof DeleteEvent del) {
            memoryMap.remove(del.key());
        } else if (event instanceof EvictEvent ev) {
            memoryMap.remove(ev.key());
        }
    }

    @Override
    public void write(CacheKey key, V value, OptionalLong expiresAt) {
        rwLock.writeLock().lock();
        try {
            byte[] serialized = serializer.serialize(value);
            VectorClock clock = new VectorClock(Map.of());
            long now = System.currentTimeMillis();

            PutEvent event = new PutEvent(key, serialized, clock, now);
            wal.append(event);
            wal.flush();

            CacheEntry<V> entry = new CacheEntry<>(key, value, clock, now, expiresAt);
            memoryMap.put(key, entry);

            notifyListeners(new StorageEvent("WRITE", entry));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public boolean delete(CacheKey key) {
        rwLock.writeLock().lock();
        try {
            CacheEntry<V> removed = memoryMap.remove(key);
            if (removed != null) {
                try {
                    DeleteEvent event = new DeleteEvent(key, new VectorClock(Map.of()), System.currentTimeMillis());
                    wal.append(event);
                    wal.flush();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                notifyListeners(new StorageEvent("DELETE", key));
                return true;
            }
            return false;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public Optional<CacheEntry<V>> read(CacheKey key) {
        rwLock.readLock().lock();
        try {
            CacheEntry<V> entry = memoryMap.get(key);
            if (entry == null) return Optional.empty();

            if (entry.expiresAt().isPresent() && entry.expiresAt().getAsLong() < System.currentTimeMillis()) {
                return Optional.empty();
            }
            return Optional.of(entry);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public int size() {
        rwLock.readLock().lock();
        try {
            return (int) memoryMap.values().stream()
                    .filter(e -> e.expiresAt().isEmpty() || e.expiresAt().getAsLong() >= System.currentTimeMillis())
                    .count();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public void append(CacheEvent event) {
        rwLock.writeLock().lock();
        try {
            wal.append(event);
            wal.flush();
            applyEventToMemory(event);
            notifyListeners(new StorageEvent("APPEND_EVENT", event));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public List<CacheEvent> readAll() {
        rwLock.readLock().lock();
        try {
            return wal.replayAll();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public List<CacheEvent> readAfter(long timestamp) {
        rwLock.readLock().lock();
        try {
            return wal.replayAll().stream()
                    .filter(e -> e.timestamp() > timestamp)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public void createSnapshot(Map<CacheKey, byte[]> state) {
        rwLock.writeLock().lock();
        try {
            Path snapFile = baseDir.resolve(String.format("snapshot-%d.bin", System.currentTimeMillis()));
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(snapFile)))) {
                out.writeInt(state.size());
                for (Map.Entry<CacheKey, byte[]> entry : state.entrySet()) {
                    CacheKey key = entry.getKey();
                    byte[] ns = key.namespace().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    out.writeShort(ns.length);
                    out.write(ns);
                    byte[] k = key.key().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    out.writeShort(k.length);
                    out.write(k);

                    byte[] valBytes = entry.getValue();
                    out.writeInt(valBytes.length);
                    out.write(valBytes);
                }
                out.flush();
            }
            notifyListeners(new StorageEvent("SNAPSHOT_CREATED", snapFile.toString()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public Map<CacheKey, byte[]> loadLatestSnapshot() {
        rwLock.readLock().lock();
        try {
            List<Path> snapshots;
            try (var stream = Files.list(baseDir)) {
                snapshots = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().startsWith("snapshot-") && p.getFileName().toString().endsWith(".bin"))
                        .sorted(Comparator.comparing(Path::toString).reversed())
                        .toList();
            }

            if (snapshots.isEmpty()) {
                return Map.of();
            }

            Path latest = snapshots.get(0);
            Map<CacheKey, byte[]> map = new HashMap<>();

            try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(latest)))) {
                int size = in.readInt();
                for (int i = 0; i < size; i++) {
                    int nsLen = in.readShort() & 0xFFFF;
                    byte[] ns = new byte[nsLen];
                    in.readFully(ns);
                    int kLen = in.readShort() & 0xFFFF;
                    byte[] k = new byte[kLen];
                    in.readFully(k);

                    int valLen = in.readInt();
                    byte[] val = new byte[valLen];
                    in.readFully(val);

                    CacheKey key = new CacheKey(new String(ns, java.nio.charset.StandardCharsets.UTF_8), new String(k, java.nio.charset.StandardCharsets.UTF_8));
                    map.put(key, val);
                }
            } catch (Exception e) {
                // Return whatever read if corrupted
            }

            return map;
        } catch (IOException e) {
            return Map.of();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public void addListener(StorageEventListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public void removeListener(StorageEventListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    private void notifyListeners(StorageEvent event) {
        for (StorageEventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void close() throws IOException {
        rwLock.writeLock().lock();
        try {
            wal.close();
        } finally {
            rwLock.writeLock().unlock();
        }
    }
}
