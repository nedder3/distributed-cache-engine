package com.nedder3.cache.core.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CacheKeyTest {

    @Test
    void recordEquality_basedOnBothFields() {
        var a = new CacheKey("ns", "k1");
        var b = new CacheKey("ns", "k1");
        assertThat(a).isEqualTo(b);
    }

    @Test
    void differentNamespace_notEqual() {
        var a = new CacheKey("ns1", "k1");
        var b = new CacheKey("ns2", "k1");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void differentKey_notEqual() {
        var a = new CacheKey("ns", "k1");
        var b = new CacheKey("ns", "k2");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void consistentHashCode() {
        var key = new CacheKey("ns", "k1");
        assertThat(key.hashCode()).isEqualTo(key.hashCode());
    }

    @Test
    void equalKeysSameHashCode() {
        var a = new CacheKey("ns", "k1");
        var b = new CacheKey("ns", "k1");
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }
}
