package com.nedder3.cache.core.port;

import com.nedder3.cache.core.event.CacheEvent;

/**
 * Outbound port defining the replication contract.
 */
public interface ReplicationPort {

    default void notifyPut(com.nedder3.cache.core.model.CacheKey key) {}

    default void notifyDelete(com.nedder3.cache.core.model.CacheKey key) {}

    /**
     * Replicates a cache event to other nodes.
     *
     * @param event the cache event to replicate
     */
    void replicate(CacheEvent event);

    /**
     * Adds a listener for replication events.
     *
     * @param listener the event listener to add
     */
    void addListener(ReplicationEventListener listener);

    /**
     * Removes a listener for replication events.
     *
     * @param listener the event listener to remove
     */
    void removeListener(ReplicationEventListener listener);

    /**
     * Interface for replication event listeners.
     */
    interface ReplicationEventListener {
        void onEvent(ReplicationEvent event);
    }

    /**
     * Represents an event in the replication system.
     */
    class ReplicationEvent {
        private final String type;
        private final Object payload;

        public ReplicationEvent(String type, Object payload) {
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
