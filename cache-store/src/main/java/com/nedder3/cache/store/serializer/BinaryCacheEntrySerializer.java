package com.nedder3.cache.store.serializer;

import com.nedder3.cache.core.clock.VectorClock;
import com.nedder3.cache.core.model.CacheEntry;
import com.nedder3.cache.core.model.CacheKey;
import com.nedder3.cache.core.port.SerializerPort;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class BinaryCacheEntrySerializer implements SerializerPort<CacheEntry<byte[]>> {

    private static final byte MAGIC_BYTE_1 = (byte) 0xDC;
    private static final byte MAGIC_BYTE_2 = (byte) 0xE1; // Distributed Cache Engine v1

    private final List<SerializationEventListener> listeners = new CopyOnWriteArrayList<>();

    @Override
    public byte[] serialize(CacheEntry<byte[]> entry) {
        if (entry == null) {
            byte[] empty = new byte[0];
            notifyListeners(new SerializationEvent("SERIALIZE", empty));
            return empty;
        }

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(baos)) {

            // Magic header
            out.writeByte(MAGIC_BYTE_1);
            out.writeByte(MAGIC_BYTE_2);

            // CacheKey
            byte[] nsBytes = entry.key().namespace().getBytes(StandardCharsets.UTF_8);
            out.writeShort(nsBytes.length);
            out.write(nsBytes);

            byte[] keyBytes = entry.key().key().getBytes(StandardCharsets.UTF_8);
            out.writeShort(keyBytes.length);
            out.write(keyBytes);

            // VectorClock
            Map<String, Long> clockMap = entry.version().counters();
            out.writeShort(clockMap.size());
            for (Map.Entry<String, Long> nodeEntry : clockMap.entrySet()) {
                byte[] nodeBytes = nodeEntry.getKey().getBytes(StandardCharsets.UTF_8);
                out.writeShort(nodeBytes.length);
                out.write(nodeBytes);
                out.writeLong(nodeEntry.getValue());
            }

            // Timestamps
            out.writeLong(entry.createdAt());
            if (entry.expiresAt().isPresent()) {
                out.writeBoolean(true);
                out.writeLong(entry.expiresAt().getAsLong());
            } else {
                out.writeBoolean(false);
            }

            // Payload
            byte[] payload = entry.value();
            if (payload == null) {
                out.writeInt(-1);
            } else {
                out.writeInt(payload.length);
                out.write(payload);
            }

            out.flush();
            byte[] result = baos.toByteArray();
            notifyListeners(new SerializationEvent("SERIALIZE", result));
            return result;
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize CacheEntry", e);
        }
    }

    @Override
    public CacheEntry<byte[]> deserialize(byte[] data) {
        if (data == null || data.length == 0) {
            notifyListeners(new SerializationEvent("DESERIALIZE", data));
            return null;
        }

        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             DataInputStream in = new DataInputStream(bais)) {

            byte m1 = in.readByte();
            byte m2 = in.readByte();
            if (m1 != MAGIC_BYTE_1 || m2 != MAGIC_BYTE_2) {
                throw new IllegalArgumentException("Invalid binary magic header");
            }

            // Namespace
            int nsLen = in.readShort() & 0xFFFF;
            byte[] nsBytes = new byte[nsLen];
            in.readFully(nsBytes);
            String namespace = new String(nsBytes, StandardCharsets.UTF_8);

            // Key
            int keyLen = in.readShort() & 0xFFFF;
            byte[] keyBytes = new byte[keyLen];
            in.readFully(keyBytes);
            String key = new String(keyBytes, StandardCharsets.UTF_8);

            // VectorClock
            int clockSize = in.readShort() & 0xFFFF;
            Map<String, Long> clockMap = new HashMap<>(clockSize);
            for (int i = 0; i < clockSize; i++) {
                int nodeLen = in.readShort() & 0xFFFF;
                byte[] nodeBytes = new byte[nodeLen];
                in.readFully(nodeBytes);
                String node = new String(nodeBytes, StandardCharsets.UTF_8);
                long clockVal = in.readLong();
                clockMap.put(node, clockVal);
            }

            // Timestamps
            long createdAt = in.readLong();
            boolean hasExpires = in.readBoolean();
            OptionalLong expiresAt = hasExpires ? OptionalLong.of(in.readLong()) : OptionalLong.empty();

            // Payload
            int payloadLen = in.readInt();
            byte[] payload;
            if (payloadLen == -1) {
                payload = null;
            } else {
                payload = new byte[payloadLen];
                in.readFully(payload);
            }

            CacheEntry<byte[]> entry = new CacheEntry<>(
                    new CacheKey(namespace, key),
                    payload,
                    new VectorClock(clockMap),
                    createdAt,
                    expiresAt
            );

            notifyListeners(new SerializationEvent("DESERIALIZE", data));
            return entry;
        } catch (IOException e) {
            throw new RuntimeException("Failed to deserialize binary CacheEntry", e);
        }
    }

    @Override
    public void addListener(SerializationEventListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public void removeListener(SerializationEventListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    private void notifyListeners(SerializationEvent event) {
        for (SerializationEventListener listener : listeners) {
            listener.onEvent(event);
        }
    }
}
