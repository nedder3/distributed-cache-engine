package com.nedder3.cache.core.event;

import java.util.Map;

/**
 * Emitted when background compaction prunes old events from the store.
 */
public record CompactionEvent(
    long cutoffTimestamp,
    int eventsPruned,
    long timestamp
) implements CacheEvent {

    @Override
    public Map<String, Long> clock() {
        return Map.of();
    }
}
