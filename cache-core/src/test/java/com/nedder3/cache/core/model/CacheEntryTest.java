package com.nedder3.cache.core.model;

import com.nedder3.cache.core.clock.VectorClock;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

class CacheEntryTest {

    @Test
    void entryPreservesAllFields() {
        var clock = new VectorClock(Map.of("node-1", 1L));
        var entry = new CacheEntry<>(
            new CacheKey("ns", "k1"),
            "value",
            clock,
            1000L,
            OptionalLong.of(2000L)
        );
        assertThat(entry.key()).isEqualTo(new CacheKey("ns", "k1"));
        assertThat(entry.value()).isEqualTo("value");
        assertThat(entry.version()).isEqualTo(clock);
        assertThat(entry.createdAt()).isEqualTo(1000L);
        assertThat(entry.expiresAt()).hasValue(2000L);
    }

    @Test
    void entryWithNoExpiration() {
        var entry = new CacheEntry<>(
            new CacheKey("ns", "k1"),
            42,
            new VectorClock(Map.of()),
            System.currentTimeMillis(),
            OptionalLong.empty()
        );
        assertThat(entry.expiresAt()).isEmpty();
    }
}
