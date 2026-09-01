package com.nedder3.cache.core.model;

/**
 * Reason an entry was evicted from the cache.
 */
public enum EvictionReason {
    /** Evicted because cache reached capacity. */
    CAPACITY,
    /** Evicted because entry's TTL expired. */
    TTL,
    /** Evicted by explicit delete or invalidation. */
    EXPLICIT
}
