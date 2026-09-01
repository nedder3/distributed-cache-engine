package com.nedder3.cache.core.snapshot;

/**
 * Strategy interface for deciding when to take a cache snapshot.
 * Implementations are sealed via the module system; only approved
 * implementations (e.g. ThresholdSnapshotStrategy) may be added.
 */
public sealed interface SnapshotStrategy
    permits ThresholdSnapshotStrategy {

    /**
     * Returns true when a snapshot should be taken.
     *
     * @param eventCountSinceLastSnapshot number of events that have occurred since the last snapshot
     * @param lastSnapshotTimestamp       epoch millis of the last snapshot, or 0 if none was ever taken
     * @return true if a snapshot is due, false otherwise
     */
    boolean shouldSnapshot(int eventCountSinceLastSnapshot, long lastSnapshotTimestamp);

    /**
     * Returns true when a snapshot should be taken given explicit current timestamp.
     *
     * @param eventCountSinceLastSnapshot number of events that have occurred since the last snapshot
     * @param lastSnapshotTimestamp       epoch millis of the last snapshot, or 0 if none was ever taken
     * @param currentTimestamp            current epoch millis
     * @return true if a snapshot is due, false otherwise
     */
    default boolean shouldSnapshot(int eventCountSinceLastSnapshot, long lastSnapshotTimestamp, long currentTimestamp) {
        return shouldSnapshot(eventCountSinceLastSnapshot, lastSnapshotTimestamp);
    }

    /**
     * Records that a snapshot was taken at the given timestamp.
     * Implementations may use this to reset internal counters or advance state.
     *
     * @param timestamp epoch millis at which the snapshot was taken
     */
    void recordSnapshot(long timestamp);
}
