package com.nedder3.cache.core.engine;

import com.nedder3.cache.core.event.CompactionEvent;
import com.nedder3.cache.core.event.EventBus;
import com.nedder3.cache.core.model.CacheKey;
import com.nedder3.cache.core.port.StoragePort;
import com.nedder3.cache.core.snapshot.SnapshotStrategy;

import java.time.Clock;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Background Compaction & Snapshotting Engine.
 * Runs asynchronously on a dedicated or virtual thread to monitor event counts,
 * trigger snapshots according to the configured {@link SnapshotStrategy},
 * and compact the event store to reclaim memory/storage.
 */
public class CompactionEngine implements AutoCloseable {

    private final StoragePort<?> storage;
    private final SnapshotStrategy strategy;
    private final EventBus bus;
    private final StatsCollector stats;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final AtomicInteger eventCounter = new AtomicInteger(0);
    private final AtomicLong lastSnapshotTime;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public CompactionEngine(
            StoragePort<?> storage,
            SnapshotStrategy strategy,
            EventBus bus,
            StatsCollector stats) {
        this(storage, strategy, bus, stats, Clock.systemUTC(), Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "compaction-engine");
            t.setDaemon(true);
            return t;
        }));
    }

    public CompactionEngine(
            StoragePort<?> storage,
            SnapshotStrategy strategy,
            EventBus bus,
            StatsCollector stats,
            Clock clock,
            ScheduledExecutorService scheduler) {
        this.storage = Objects.requireNonNull(storage, "storage cannot be null");
        this.strategy = Objects.requireNonNull(strategy, "strategy cannot be null");
        this.bus = Objects.requireNonNull(bus, "bus cannot be null");
        this.stats = Objects.requireNonNull(stats, "stats cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler cannot be null");
        this.lastSnapshotTime = new AtomicLong(clock.millis());

        // Subscribe to storage events to track count
        this.storage.addListener(event -> {
            if ("APPEND".equals(event.getType())) {
                eventCounter.incrementAndGet();
            }
        });
    }

    /**
     * Starts the periodic background snapshot and compaction checks.
     *
     * @param periodMillis check interval in milliseconds
     */
    public synchronized void start(long periodMillis) {
        if (periodMillis <= 0) {
            throw new IllegalArgumentException("periodMillis must be positive: " + periodMillis);
        }
        if (running.compareAndSet(false, true)) {
            scheduler.scheduleWithFixedDelay(this::checkAndExecute, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Evaluates snapshot condition and executes snapshot + compaction if triggered.
     * Returns true if a snapshot and compaction were performed.
     */
    public boolean checkAndExecute() {
        int count = eventCounter.get();
        long lastTime = lastSnapshotTime.get();
        long now = clock.millis();

        if (strategy.shouldSnapshot(count, lastTime, now)) {
            executeSnapshotAndCompaction(now);
            return true;
        }
        return false;
    }

    /**
     * Forces an immediate snapshot and compaction regardless of strategy thresholds.
     */
    public void forceSnapshotAndCompaction() {
        executeSnapshotAndCompaction(clock.millis());
    }

    private synchronized void executeSnapshotAndCompaction(long timestamp) {
        try {
            // 1. Take Snapshot
            Map<CacheKey, byte[]> currentState = new HashMap<>(storage.loadLatestSnapshot());
            storage.createSnapshot(currentState);
            strategy.recordSnapshot(timestamp);
            lastSnapshotTime.set(timestamp);
            stats.recordSnapshot();

            // 2. Compact Event Store
            int pruned = storage.compactBefore(timestamp);
            eventCounter.set(0);
            stats.recordCompaction(pruned);

            // 3. Emit Domain Event
            bus.publish(new CompactionEvent(timestamp, pruned, timestamp));
        } catch (Throwable ignored) {
            // Isolate background task failures
        }
    }

    public int getPendingEventCount() {
        return eventCounter.get();
    }

    public long getLastSnapshotTime() {
        return lastSnapshotTime.get();
    }

    public boolean isRunning() {
        return running.get();
    }

    @Override
    public void close() {
        running.set(false);
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
