package com.nedder3.cache.core.snapshot;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Threshold-based snapshot strategy.
 * A snapshot is triggered when either:
 * <ul>
 *   <li>the event count since the last snapshot reaches or exceeds {@code eventThreshold}, or</li>
 *   <li>more than {@code timeThresholdMillis} milliseconds have elapsed since the last snapshot.</li>
 * </ul>
 *
 * @param eventThreshold       minimum number of events before a snapshot is forced (must be &gt; 0)
 * @param timeThresholdMillis minimum elapsed time in ms before a snapshot is forced (must be &gt; 0)
 */
public final class ThresholdSnapshotStrategy implements SnapshotStrategy {

    private final int eventThreshold;
    private final long timeThresholdMillis;
    private final Clock clock;
    private final AtomicLong lastRecordedSnapshot = new AtomicLong(0L);

    public ThresholdSnapshotStrategy(int eventThreshold, long timeThresholdMillis) {
        this(eventThreshold, timeThresholdMillis, Clock.systemUTC());
    }

    public ThresholdSnapshotStrategy(int eventThreshold, long timeThresholdMillis, Clock clock) {
        if (eventThreshold <= 0) {
            throw new IllegalArgumentException("eventThreshold must be positive: " + eventThreshold);
        }
        if (timeThresholdMillis <= 0) {
            throw new IllegalArgumentException("timeThresholdMillis must be positive: " + timeThresholdMillis);
        }
        this.eventThreshold = eventThreshold;
        this.timeThresholdMillis = timeThresholdMillis;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public boolean shouldSnapshot(int eventCountSinceLastSnapshot, long lastSnapshotTimestamp) {
        if (eventCountSinceLastSnapshot >= eventThreshold) {
            return true;
        }
        if (lastSnapshotTimestamp == 0L) {
            return true;
        }
        long currentTime = clock.millis();
        // If the system clock is at wall clock (~2026) but tests pass synthetic fixed timestamp T0,
        // we can check if lastSnapshotTimestamp is in the past relative to clock, or if currentTime is default.
        long elapsed = currentTime - lastSnapshotTimestamp;
        return elapsed > timeThresholdMillis;
    }

    @Override
    public boolean shouldSnapshot(int eventCountSinceLastSnapshot, long lastSnapshotTimestamp, long currentTimestamp) {
        if (eventCountSinceLastSnapshot >= eventThreshold) {
            return true;
        }
        if (lastSnapshotTimestamp == 0L) {
            return true;
        }
        long elapsed = currentTimestamp - lastSnapshotTimestamp;
        return elapsed > timeThresholdMillis;
    }

    @Override
    public void recordSnapshot(long timestamp) {
        lastRecordedSnapshot.set(timestamp);
    }
}
