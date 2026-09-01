package com.nedder3.cache.core.event;

import com.nedder3.cache.core.clock.VectorClock;
import com.nedder3.cache.core.model.CacheKey;
import com.nedder3.cache.core.model.EvictionReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CacheEvent Model Tests")
class CacheEventTest {

    private CacheKey key;
    private VectorClock clock;

    @BeforeEach
    void setUp() {
        key = new CacheKey("users", "user:42");
        clock = new VectorClock(Map.of()).increment("node-1");
    }

    @Test
    void putEvent_implementsCacheEvent_andExposesProperties() {
        byte[] payload = "test-payload".getBytes();
        PutEvent event = new PutEvent(key, payload, clock, 1000L);

        assertThat(event.key()).isEqualTo(key);
        assertThat(event.serializedValue()).isEqualTo(payload);
        assertThat(event.vectorClock()).isEqualTo(clock);
        assertThat(event.timestamp()).isEqualTo(1000L);
        assertThat(event.clock()).containsEntry("node-1", 1L);
    }

    @Test
    void deleteEvent_implementsCacheEvent_andExposesProperties() {
        DeleteEvent event = new DeleteEvent(key, clock, 2000L);

        assertThat(event.key()).isEqualTo(key);
        assertThat(event.vectorClock()).isEqualTo(clock);
        assertThat(event.timestamp()).isEqualTo(2000L);
        assertThat(event.clock()).containsEntry("node-1", 1L);
    }

    @Test
    void evictEvent_implementsCacheEvent_andExposesProperties() {
        EvictEvent event = new EvictEvent(key, EvictionReason.CAPACITY, 3000L);

        assertThat(event.key()).isEqualTo(key);
        assertThat(event.reason()).isEqualTo(EvictionReason.CAPACITY);
        assertThat(event.timestamp()).isEqualTo(3000L);
    }

    @Test
    void patternMatching_switch_compilesAndRuns() {
        CacheEvent event = new PutEvent(key, new byte[]{}, clock, 0L);
        String label = describe(event);
        assertThat(label).isEqualTo("put");

        event = new DeleteEvent(key, clock, 0L);
        assertThat(describe(event)).isEqualTo("delete");

        event = new EvictEvent(key, EvictionReason.TTL, 0L);
        assertThat(describe(event)).isEqualTo("evict");
    }

    private String describe(CacheEvent event) {
        return switch (event) {
            case PutEvent e -> "put";
            case DeleteEvent e -> "delete";
            case EvictEvent e -> "evict";
            default -> "unknown";
        };
    }
}
