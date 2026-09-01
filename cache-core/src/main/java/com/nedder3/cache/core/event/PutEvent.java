package com.nedder3.cache.core.event;

import com.nedder3.cache.core.clock.VectorClock;
import com.nedder3.cache.core.model.CacheKey;

import java.util.Map;

/**
 * Emitted when a new entry is written or an existing one is overwritten.
 */
public record PutEvent(
    CacheKey key,
    byte[] serializedValue,
    VectorClock vectorClock,
    long timestamp
) implements CacheEvent {

    @Override
    public Map<String, Long> clock() {
        return vectorClock.counters();
    }
}
