package com.nedder3.cache.core.port;

import com.nedder3.cache.core.model.CacheKey;
import java.util.Optional;

/**
 * Outbound port defining the eviction strategy contract.
 */
public interface EvictionPort {

    default void onAccess(CacheKey key) {}

    default void onInsert(CacheKey key) {}

    default void onDelete(CacheKey key) {}

    default Optional<CacheKey> evict() { return Optional.empty(); }

    /**
     * Returns the number of keys tracked by the eviction strategy.
     *
     * @return the tracked key count
     */
    int size();

    /**
     * Clears all tracked keys from the eviction strategy.
     */
    void clear();

    /**
     * Adds a listener for eviction events.
     *
     * @param listener the event listener to add
     */
    void addListener(EvictionEventListener listener);

    /**
     * Removes a listener for eviction events.
     *
     * @param listener the event listener to remove
     */
    void removeListener(EvictionEventListener listener);

    /**
     * Interface for eviction event listeners.
     */
    interface EvictionEventListener {
        void onEvent(EvictionEvent event);
    }

    /**
     * Represents an event in the eviction system.
     */
    class EvictionEvent {
        private final String type;
        private final Object payload;

        public EvictionEvent(String type, Object payload) {
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
