package com.nedder3.cache.core.model;

/**
 * Immutable snapshot of cache operation counts.
 * For mutable stats, use {@link com.nedder3.cache.core.engine.StatsCollector}.
 */
public record CacheStats(
    long hits,
    long misses,
    long evictions,
    long puts,
    long deletes
) {
}
