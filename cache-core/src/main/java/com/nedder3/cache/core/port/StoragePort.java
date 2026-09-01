package com.nedder3.cache.core.port;

import com.nedder3.cache.core.event.CacheEvent;
import com.nedder3.cache.core.model.CacheKey;
import java.util.List;
import java.util.Map;

/**
 * Outbound port defining the event store persistence contract.
 */
public interface StoragePort<V> {

    default void write(CacheKey key, V value, java.util.OptionalLong expiresAt) {}

    default boolean delete(CacheKey key) { return false; }

    default java.util.Optional<com.nedder3.cache.core.model.CacheEntry<V>> read(CacheKey key) { return java.util.Optional.empty(); }

    default int size() { return 0; }

    /**
     * Appends a cache event to the store.
     *
     * @param event the cache event to append
     */
    void append(CacheEvent event);

    /**
     * Reads all events from the store in order.
     *
     * @return the list of all events
     */
    List<CacheEvent> readAll();

    /**
     * Reads events that occurred after the given timestamp.
     *
     * @param timestamp the cutoff timestamp (exclusive)
     * @return the list of events after the timestamp
     */
    List<CacheEvent> readAfter(long timestamp);

    /**
     * Compacts the store by dropping all events that occurred on or before the given timestamp.
     *
     * @param cutoffTimestamp timestamp up to which events are pruned
     * @return number of events pruned
     */
    default int compactBefore(long cutoffTimestamp) { return 0; }

    /**
     * Persists a snapshot of the current cache state.
     *
     * @param state the cache state as key-to-bytes mapping
     */
    void createSnapshot(Map<CacheKey, byte[]> state);

    /**
     * Loads the latest snapshot from the store.
     *
     * @return the snapshot state, or an empty map if none exists
     */
    Map<CacheKey, byte[]> loadLatestSnapshot();

    /**
     * Adds a listener for storage events.
     *
     * @param listener the event listener to add
     */
    void addListener(StorageEventListener listener);

    /**
     * Removes a listener for storage events.
     *
     * @param listener the event listener to remove
     */
    void removeListener(StorageEventListener listener);

    /**
     * Interface for storage event listeners.
     */
    interface StorageEventListener {
        void onEvent(StorageEvent event);
    }

    /**
     * Represents an event in the storage system.
     */
    class StorageEvent {
        private final String type;
        private final Object payload;

        public StorageEvent(String type, Object payload) {
            this.type = type;
            this.payload = payload;
        }

        public String getType() {
            return type;
        }

        public Object getPayload() {
            return payload;
        }
    }
}
