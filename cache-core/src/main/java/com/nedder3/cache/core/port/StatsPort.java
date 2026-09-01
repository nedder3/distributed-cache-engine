package com.nedder3.cache.core.port;

import com.nedder3.cache.core.model.CacheStats;

/**
 * Port interface for stats recording.
 * Implementations provide hit/miss/eviction counters and a snapshot view.
 */
public interface StatsPort {

    void recordHit();
    void recordMiss();
    void recordEviction();
    void recordPut();
    void recordDelete();
    CacheStats snapshot();
}
