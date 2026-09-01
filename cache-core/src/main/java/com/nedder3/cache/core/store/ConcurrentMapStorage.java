package com.nedder3.cache.core.store;

import com.nedder3.cache.core.clock.VectorClock;
import com.nedder3.cache.core.event.CacheEvent;
import com.nedder3.cache.core.model.CacheEntry;
import com.nedder3.cache.core.model.CacheKey;
import com.nedder3.cache.core.port.StoragePort;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory storage adapter implementing {@link StoragePort}.
 *
 * @param <V> value type
 */
public final class ConcurrentMapStorage<V> implements StoragePort<V> {

    private final Map<CacheKey, CacheEntry<V>> map = new ConcurrentHashMap<>();

    @Override
    public void write(CacheKey key, V value, OptionalLong expiresAt) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(value, "value cannot be null");
        Objects.requireNonNull(expiresAt, "expiresAt cannot be null");
        long now = System.currentTimeMillis();
        map.put(key, new CacheEntry<>(key, value, new VectorClock(Map.of()), now, expiresAt));
    }

    @Override
    public boolean delete(CacheKey key) {
        Objects.requireNonNull(key, "key cannot be null");
        return map.remove(key) != null;
    }

    @Override
    public Optional<CacheEntry<V>> read(CacheKey key) {
        Objects.requireNonNull(key, "key cannot be null");
        CacheEntry<V> entry = map.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAt().isPresent() && entry.expiresAt().getAsLong() <= System.currentTimeMillis()) {
            map.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public void append(CacheEvent event) {
    }

    @Override
    public List<CacheEvent> readAll() {
        return Collections.emptyList();
    }

    @Override
    public List<CacheEvent> readAfter(long timestamp) {
        return Collections.emptyList();
    }

    @Override
    public void createSnapshot(Map<CacheKey, byte[]> state) {
    }

    @Override
    public Map<CacheKey, byte[]> loadLatestSnapshot() {
        return Collections.emptyMap();
    }

    @Override
    public void addListener(StorageEventListener listener) {
    }

    @Override
    public void removeListener(StorageEventListener listener) {
    }
}