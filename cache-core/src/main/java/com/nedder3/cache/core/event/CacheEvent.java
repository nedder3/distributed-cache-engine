package com.nedder3.cache.core.event;

import java.util.Collections;
import java.util.Map;

/**
 * Base interface for all cache-domain events.
 * Provides polymorphic event identity, timestamp, and optional vector clock mappings.
 */
public interface CacheEvent {

    /**
     * Optional vector clock representation associated with this event.
     */
    default Map<String, Long> clock() {
        return Collections.emptyMap();
    }

    /**
     * Epoch timestamp in milliseconds or nanoseconds of event creation.
     */
    default long timestamp() {
        return System.currentTimeMillis();
    }
}
