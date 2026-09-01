package com.nedder3.cache.core.model;

/**
 * Composite immutable cache key: namespace + key.
 * Records generate hashCode/equals on all fields automatically.
 */
public record CacheKey(String namespace, String key) {
}
