package com.nedder3.cache.core.port;

import com.nedder3.cache.core.event.CacheEvent;
import com.nedder3.cache.core.model.CacheKey;
import com.nedder3.cache.core.model.CacheStats;
import java.util.Optional;

/**
 * Primary inbound port defining the cache API contract.
 *
 * @param <V> the type of values stored in the cache
 */
public interface InboundPort<V> {

    /**
     * Retrieves a value from the cache.
     *
     * @param key the cache key
     * @return an {@link Optional} containing the value if present, or empty
     */
    Optional<V> get(CacheKey key);

    /**
     * Inserts or updates a value in the cache.
     *
     * @param key the cache key
     * @param value the value to store
     */
    void put(CacheKey key, V value);

    /**
     * Inserts or updates a value with a specific time-to-live.
     *
     * @param key the cache key
     * @param value the value to store
     * @param ttlMillis time-to-live in milliseconds
     */
    void put(CacheKey key, V value, long ttlMillis);

    /**
     * Removes a value from the cache.
     *
     * @param key the cache key
     * @return true if the key existed and was removed, false otherwise
     */
    boolean delete(CacheKey key);

    /**
     * Returns the number of entries currently in the cache.
     *
     * @return the cache size
     */
    int size();

    /**
     * Returns current cache statistics.
     *
     * @return the cache stats
     */
    CacheStats stats();

    /**
     * Adds a listener for cache events.
     *
     * @param listener the event listener to add
     */
    void addListener(CacheEventListener listener);

    /**
     * Removes a listener for cache events.
     *
     * @param listener the event listener to remove
     */
    void removeListener(CacheEventListener listener);

    /**
     * Interface for cache event listeners.
     */
    interface CacheEventListener {
        void onEvent(CacheEvent event);
    }
}
