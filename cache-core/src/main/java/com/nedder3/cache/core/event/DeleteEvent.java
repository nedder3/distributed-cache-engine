package com.nedder3.cache.core.event;

import com.nedder3.cache.core.clock.VectorClock;
import com.nedder3.cache.core.model.CacheKey;

import java.util.Map;

/**
 * Emitted when an entry is explicitly removed from the cache.
 */
public record DeleteEvent(
    CacheKey key,
    VectorClock vectorClock,
    long timestamp
) implements CacheEvent {

    @Override
    public Map<String, Long> clock() {
        return vectorClock.counters();
    }
}
