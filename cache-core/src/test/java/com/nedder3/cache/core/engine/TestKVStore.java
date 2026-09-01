package com.nedder3.cache.core.engine;

import com.nedder3.cache.core.clock.VectorClock;
import com.nedder3.cache.core.model.CacheKey;
import com.nedder3.cache.core.model.EvictionReason;

import java.util.Map;
import java.util.OptionalLong;

/**
 * Minimal in-process key-value store test double.
 * Implements exactly the IKVStore interface that CommandHandler and QueryHandler require.
 */
public final class TestKVStore {

    private final java.util.concurrent.ConcurrentHashMap<CacheKey, Entry> map =
        new java.util.concurrent.ConcurrentHashMap<>();

    public void write(CacheKey key, Object value, OptionalLong expiresAt) {
        map.put(key, new Entry(value, expiresAt));
    }

    public OptionalLong delete(CacheKey key) {
        Entry removed = map.remove(key);
        return removed != null ? OptionalLong.of(1L) : OptionalLong.empty();
    }

    public java.util.Optional<Entry> read(CacheKey key) {
        return java.util.Optional.ofNullable(map.get(key));
    }

    public int size() {
        return map.size();
    }

    public void clear() {
        map.clear();
    }

    public static final class Entry {
        private final Object value;
        private final OptionalLong expiresAt;

        public Entry(Object value, OptionalLong expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }

        public Object value() { return value; }
        public OptionalLong expiresAt() { return expiresAt; }
    }
}
