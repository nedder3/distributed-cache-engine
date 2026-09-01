package com.nedder3.cache.core.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CacheStatsTest {

    @Test
    void allZerosByDefault() {
        var stats = new CacheStats(0, 0, 0, 0, 0);
        assertThat(stats.hits()).isZero();
        assertThat(stats.misses()).isZero();
        assertThat(stats.evictions()).isZero();
        assertThat(stats.puts()).isZero();
        assertThat(stats.deletes()).isZero();
    }

    @Test
    void recordEquality() {
        var a = new CacheStats(1, 2, 3, 4, 5);
        var b = new CacheStats(1, 2, 3, 4, 5);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void evictionReasonEnumValues() {
        assertThat(EvictionReason.values()).containsExactlyInAnyOrder(
            EvictionReason.CAPACITY,
            EvictionReason.TTL,
            EvictionReason.EXPLICIT
        );
    }
}
