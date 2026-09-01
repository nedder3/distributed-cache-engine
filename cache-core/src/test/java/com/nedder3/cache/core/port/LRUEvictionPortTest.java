package com.nedder3.cache.core.port;

import com.nedder3.cache.core.model.CacheKey;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LRUEvictionPortTest {

    @Test
    void evict_returnsLeastRecentlyUsedKey() {
        LRUEvictionPort evictionPort = new LRUEvictionPort(2);
        CacheKey key1 = new CacheKey("ns", "k1");
        CacheKey key2 = new CacheKey("ns", "k2");
        CacheKey key3 = new CacheKey("ns", "k3");

        evictionPort.onInsert(key1);
        evictionPort.onInsert(key2);
        evictionPort.onAccess(key1); // Make key1 more recently used
        evictionPort.onInsert(key3);

        Optional<CacheKey> evicted = evictionPort.evict();
        assertThat(evicted).contains(key2);
    }

    @Test
    void size_returnsCorrectCount() {
        LRUEvictionPort evictionPort = new LRUEvictionPort(3);
        CacheKey key1 = new CacheKey("ns", "k1");
        CacheKey key2 = new CacheKey("ns", "k2");

        evictionPort.onInsert(key1);
        evictionPort.onInsert(key2);

        assertThat(evictionPort.size()).isEqualTo(2);
    }

    @Test
    void clear_removesAllKeys() {
        LRUEvictionPort evictionPort = new LRUEvictionPort(2);
        CacheKey key1 = new CacheKey("ns", "k1");
        CacheKey key2 = new CacheKey("ns", "k2");

        evictionPort.onInsert(key1);
        evictionPort.onInsert(key2);
        evictionPort.clear();

        assertThat(evictionPort.size()).isEqualTo(0);
    }

    @Test
    void evict_returnsEmptyWhenBelowCapacity() {
        LRUEvictionPort evictionPort = new LRUEvictionPort(2);
        CacheKey key1 = new CacheKey("ns", "k1");

        evictionPort.onInsert(key1);

        assertThat(evictionPort.evict()).isEmpty();
    }

    @Test
    void onAccess_updatesAccessOrder() {
        LRUEvictionPort evictionPort = new LRUEvictionPort(2);
        CacheKey key1 = new CacheKey("ns", "k1");
        CacheKey key2 = new CacheKey("ns", "k2");

        evictionPort.onInsert(key1);
        evictionPort.onInsert(key2);
        evictionPort.onAccess(key1); // Make key1 more recently used
        evictionPort.onInsert(new CacheKey("ns", "k3"));

        Optional<CacheKey> evicted = evictionPort.evict();
        assertThat(evicted).contains(key2);
    }
}