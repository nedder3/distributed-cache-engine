package com.nedder3.cache.core.engine;

import com.nedder3.cache.core.model.CacheEntry;
import com.nedder3.cache.core.model.CacheKey;
import com.nedder3.cache.core.model.CacheStats;
import com.nedder3.cache.core.port.StatsPort;
import com.nedder3.cache.core.port.StoragePort;

import java.util.Objects;
import java.util.Optional;

/**
 * CQRS Query Handler for cache read operations.
 * Pure read-only operations with hit/miss statistics tracking.
 *
 * @param <V> value type
 */
public class QueryHandler<V> {

    private final StoragePort<V> storage;
    private final StatsPort stats;

    public QueryHandler(StoragePort<V> storage) {
        this(storage, null);
    }

    public QueryHandler(StoragePort<V> storage, StatsPort stats) {
        this.storage = Objects.requireNonNull(storage, "storage cannot be null");
        this.stats = stats;
    }

    public Optional<CacheEntry<V>> getEntry(CacheKey cacheKey) {
        Objects.requireNonNull(cacheKey, "cacheKey cannot be null");

        Optional<CacheEntry<V>> result = storage.read(cacheKey);

        if (result.isPresent()) {
            recordHit();
        } else {
            recordMiss();
        }

        return result;
    }

    public Optional<CacheEntry<V>> get(String namespace, String key) {
        Objects.requireNonNull(namespace, "namespace cannot be null");
        Objects.requireNonNull(key, "key cannot be null");
        return getEntry(new CacheKey(namespace, key));
    }

    public Optional<V> get(CacheKey cacheKey) {
        return getEntry(cacheKey).map(CacheEntry::value);
    }

    public int size() {
        return storage.size();
    }

    public CacheStats stats() {
        if (stats != null) {
            return stats.snapshot();
        }
        return new CacheStats(0, 0, 0, 0, 0);
    }

    private void recordHit() {
        if (stats != null) {
            stats.recordHit();
        }
    }

    private void recordMiss() {
        if (stats != null) {
            stats.recordMiss();
        }
    }
}
