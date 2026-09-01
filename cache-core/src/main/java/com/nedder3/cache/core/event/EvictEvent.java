package com.nedder3.cache.core.event;

import com.nedder3.cache.core.model.CacheKey;
import com.nedder3.cache.core.model.EvictionReason;

import java.util.Map;

/**
 * Emitted when an entry is evicted by the eviction strategy.
 */
public record EvictEvent(
    CacheKey key,
    EvictionReason reason,
    long timestamp
) implements CacheEvent {

    @Override
    public Map<String, Long> clock() {
        return Map.of();
    }
}
