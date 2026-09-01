package com.nedder3.cache.core.model;

import com.nedder3.cache.core.clock.VectorClock;

import java.util.OptionalLong;

/**
 * A cached entry holding the value, version vector, and lifecycle timestamps.
 */
public record CacheEntry<V>(
    CacheKey key,
    V value,
    VectorClock version,
    long createdAt,
    OptionalLong expiresAt
) {
}
