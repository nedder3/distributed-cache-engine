package com.nedder3.cache.core.engine;

import com.nedder3.cache.core.model.CacheStats;

import java.util.concurrent.atomic.LongAdder;

/**
 * Mutable stats collector using lock-free LongAdder counters.
 * Thread-safe for high-contention scenarios.
 */
public final class StatsCollector {

    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder evictions = new LongAdder();
    private final LongAdder puts = new LongAdder();
    private final LongAdder deletes = new LongAdder();
    private final LongAdder snapshots = new LongAdder();
    private final LongAdder compactions = new LongAdder();

    public void recordHit() { hits.increment(); }
    public void recordMiss() { misses.increment(); }
    public void recordEviction() { evictions.increment(); }
    public void recordPut() { puts.increment(); }
    public void recordDelete() { deletes.increment(); }
    public void recordSnapshot() { snapshots.increment(); }
    public void recordCompaction(int eventsPruned) { compactions.add(eventsPruned); }

    public CacheStats snapshot() {
        return new CacheStats(
            hits.sum(),
            misses.sum(),
            evictions.sum(),
            puts.sum(),
            deletes.sum()
        );
    }

    public void reset() {
        hits.reset();
        misses.reset();
        evictions.reset();
        puts.reset();
        deletes.reset();
        snapshots.reset();
        compactions.reset();
    }
}
