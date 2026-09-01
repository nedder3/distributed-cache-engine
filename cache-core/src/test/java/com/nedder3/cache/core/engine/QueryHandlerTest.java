package com.nedder3.cache.core.engine;

import com.nedder3.cache.core.clock.VectorClock;
import com.nedder3.cache.core.model.CacheEntry;
import com.nedder3.cache.core.model.CacheKey;
import com.nedder3.cache.core.model.CacheStats;
import com.nedder3.cache.core.port.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RED tests for {@code com.nedder3.cache.core.engine.QueryHandler}.
 *
 * <p>These tests are written BEFORE the implementation exists (RED phase).
 * They define the CQRS QueryHandler contract:
 * <ol>
 *   <li>get(namespace, key) returns the value from StoragePort, wrapping it as CacheEntry.</li>
 *   <li>get on a missing key returns empty Optional without throwing.</li>
 *   <li>get records hit/miss in StatsCollector.</li>
 *   <li>size() returns the count from StoragePort.</li>
 *   <li>stats() returns a CacheStats snapshot.</li>
 *   <li>No mutating events are published by query operations (read-only contract).</li>
 *   <li>Null arguments are rejected with NPE.</li>
 *   <li>Concurrent get calls are thread-safe.</li>
 *   <li>get returns CacheEntry with all fields populated.</li>
 * </ol>
 */
class QueryHandlerTest {

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private StoragePort<Object> storage;
    private TestStatsCollector stats;
    private QueryHandler<Object> handler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        storage = mock(StoragePort.class);
        stats = new TestStatsCollector();
        handler = new QueryHandler<Object>(storage, stats);
    }

    // -------------------------------------------------------------------------
    // 1. get(namespace, key) — read value
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("1. get(namespace, key) — read value")
    class GetBasic {

        @Test
        @DisplayName("returns the value wrapped in CacheEntry when the key exists")
        void returnsEntryWhenPresent() {
            CacheKey key = new CacheKey("ns", "k1");
            Object value = "stored-value";
            CacheEntry<Object> entry = new CacheEntry<>(
                key, value, new VectorClock(Map.of()), 1000L, OptionalLong.empty()
            );
            when(storage.read(key)).thenReturn(Optional.of(entry));

            Optional<CacheEntry<Object>> result = handler.get("ns", "k1");

            assertThat(result).isPresent();
            assertThat(result.get().key()).isEqualTo(key);
            assertThat(result.get().value()).isEqualTo(value);
        }

        @Test
        @DisplayName("returns empty Optional when the key does not exist")
        void returnsEmptyWhenAbsent() {
            CacheKey key = new CacheKey("ns", "k1");
            when(storage.read(key)).thenReturn(Optional.empty());

            Optional<CacheEntry<Object>> result = handler.get("ns", "k1");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("calls StoragePort.read with the correct composite CacheKey")
        void delegatesToStorage() {
            CacheKey key = new CacheKey("ns", "k1");
            when(storage.read(key)).thenReturn(Optional.empty());

            handler.get("ns", "k1");

            verify(storage).read(key);
        }

        @Test
        @DisplayName("CacheEntry returned has all fields: key, value, version, createdAt, expiresAt")
        void returnsFullCacheEntry() {
            CacheKey key = new CacheKey("ns", "k1");
            VectorClock vc = new VectorClock(Map.of("node1", 5L));
            long created = 1000L;
            long expires = 5000L;
            CacheEntry<Object> entry = new CacheEntry<>(key, "val", vc, created, OptionalLong.of(expires));
            when(storage.read(key)).thenReturn(Optional.of(entry));

            CacheEntry<Object> result = handler.get("ns", "k1").orElseThrow();

            assertThat(result.key()).isEqualTo(key);
            assertThat(result.value()).isEqualTo("val");
            assertThat(result.version()).isEqualTo(vc);
            assertThat(result.createdAt()).isEqualTo(created);
            assertThat(result.expiresAt()).hasValue(expires);
        }

        @Test
        @DisplayName("different namespaces produce different CacheKeys")
        void namespaceIsolation() {
            CacheKey keyA = new CacheKey("ns-a", "k1");
            CacheKey keyB = new CacheKey("ns-b", "k1");

            handler.get("ns-a", "k1");
            handler.get("ns-b", "k1");

            verify(storage).read(keyA);
            verify(storage).read(keyB);
        }
    }

    // -------------------------------------------------------------------------
    // 2. Stats recording — hit / miss
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("2. Stats recording — hit / miss")
    class StatsRecording {

        @Test
        @DisplayName("get on an existing key records a hit")
        void hitRecordedWhenPresent() {
            CacheKey key = new CacheKey("ns", "k1");
            when(storage.read(key)).thenReturn(Optional.of(
                new CacheEntry<>(key, "v", new VectorClock(Map.of()), 0L, OptionalLong.empty())
            ));

            handler.get("ns", "k1");

            assertThat(stats.hits()).isEqualTo(1);
            assertThat(stats.misses()).isZero();
        }

        @Test
        @DisplayName("get on a missing key records a miss")
        void missRecordedWhenAbsent() {
            CacheKey key = new CacheKey("ns", "k1");
            when(storage.read(key)).thenReturn(Optional.empty());

            handler.get("ns", "k1");

            assertThat(stats.misses()).isEqualTo(1);
            assertThat(stats.hits()).isZero();
        }

        @Test
        @DisplayName("multiple hits and misses accumulate correctly")
        void accumulatesAcrossMultipleCalls() {
            CacheKey key = new CacheKey("ns", "k1");
            CacheKey key2 = new CacheKey("ns", "k2");

            when(storage.read(key)).thenReturn(Optional.of(
                new CacheEntry<>(key, "v", new VectorClock(Map.of()), 0L, OptionalLong.empty())
            ));
            when(storage.read(key2)).thenReturn(Optional.empty());
            when(storage.read(new CacheKey("ns", "k3"))).thenReturn(Optional.empty());

            handler.get("ns", "k1"); // hit
            handler.get("ns", "k2"); // miss
            handler.get("ns", "k1"); // hit
            handler.get("ns", "k3"); // miss

            assertThat(stats.hits()).isEqualTo(2);
            assertThat(stats.misses()).isEqualTo(2);
        }
    }

    // -------------------------------------------------------------------------
    // 3. size() — entry count
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("3. size() — entry count")
    class Size {

        @Test
        @DisplayName("returns the count from StoragePort.size()")
        void delegatesToStorage() {
            when(storage.size()).thenReturn(42);

            int result = handler.size();

            assertThat(result).isEqualTo(42);
            verify(storage).size();
        }

        @Test
        @DisplayName("returns zero when the cache is empty")
        void zeroWhenEmpty() {
            when(storage.size()).thenReturn(0);

            assertThat(handler.size()).isZero();
        }
    }

    // -------------------------------------------------------------------------
    // 4. stats() — snapshot
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("4. stats() — CacheStats snapshot")
    class Stats {

        @Test
        @DisplayName("returns a CacheStats snapshot containing hit/miss/put/eviction counts")
        void returnsSnapshotWithCounts() {
            CacheKey key = new CacheKey("ns", "k1");
            when(storage.read(key)).thenReturn(Optional.of(
                new CacheEntry<>(key, "v", new VectorClock(Map.of()), 0L, OptionalLong.empty())
            ));
            when(storage.size()).thenReturn(1);

            handler.get("ns", "k1"); // hit
            handler.get("ns", "missing"); // miss

            CacheStats snapshot = handler.stats();

            assertThat(snapshot.hits()).isEqualTo(1);
            assertThat(snapshot.misses()).isEqualTo(1);
            assertThat(snapshot.puts()).isZero();
            assertThat(snapshot.deletes()).isZero();
            assertThat(snapshot.evictions()).isZero();
        }

        @Test
        @DisplayName("stats() can be called multiple times without mutating state")
        void repeatable() {
            CacheStats first = handler.stats();
            CacheStats second = handler.stats();

            assertThat(first).isEqualTo(second);
        }
    }

    // -------------------------------------------------------------------------
    // 5. Read-only contract — no events published
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("5. Read-only contract — query operations publish no events")
    class ReadOnlyContract {

        @Test
        @DisplayName("get does not interact with any EventBus (no event publication)")
        void getDoesNotPublishEvents() {
            // QueryHandler has no EventBus dependency — get() is a pure read.
            // This test documents that reads are side-effect-free wrt events.
            CacheKey key = new CacheKey("ns", "k1");
            when(storage.read(key)).thenReturn(Optional.empty());

            // Must not throw — no EventBus involvement
            handler.get("ns", "k1");
        }

        @Test
        @DisplayName("size does not interact with EventBus")
        void sizeDoesNotPublishEvents() {
            when(storage.size()).thenReturn(0);

            handler.size(); // must not throw
        }

        @Test
        @DisplayName("stats does not interact with EventBus")
        void statsDoesNotPublishEvents() {
            handler.stats(); // must not throw
        }
    }

    // -------------------------------------------------------------------------
    // 6. Null-argument rejection
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("6. Null-argument rejection")
    class NullArgumentRejection {

        @Test
        @DisplayName("get(null, key) throws NullPointerException")
        void getRejectsNullNamespace() {
            assertThatThrownBy(() -> handler.get(null, "k"))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("get(namespace, null) throws NullPointerException")
        void getRejectsNullKey() {
            assertThatThrownBy(() -> handler.get("ns", null))
                .isInstanceOf(NullPointerException.class);
        }
    }

    // -------------------------------------------------------------------------
    // 7. Thread-safety — concurrent get calls
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("7. Thread-safety — concurrent get calls")
    class ThreadSafety {

        @Test
        @DisplayName("concurrent get calls do not corrupt hit/miss counters")
        void concurrentGetsPreserveCounters() throws InterruptedException {
            int threads = 8;
            int callsPerThread = 500;
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threads);
            java.util.concurrent.atomic.AtomicInteger errors = new java.util.concurrent.atomic.AtomicInteger();

            CacheKey hitKey = new CacheKey("ns", "hit");
            CacheKey missKey = new CacheKey("ns", "miss");
            when(storage.read(hitKey)).thenReturn(Optional.of(
                new CacheEntry<>(hitKey, "v", new VectorClock(Map.of()), 0L, OptionalLong.empty())
            ));
            when(storage.read(missKey)).thenReturn(Optional.empty());

            for (int t = 0; t < threads; t++) {
                final int tid = t;
                Thread th = new Thread(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < callsPerThread; i++) {
                            // Alternate between hit and miss keys
                            if ((tid + i) % 2 == 0) {
                                handler.get("ns", "hit");
                            } else {
                                handler.get("ns", "miss");
                            }
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
                th.start();
            }

            start.countDown();
            done.await(10, java.util.concurrent.TimeUnit.SECONDS);

            assertThat(errors.get()).isZero();
            assertThat(stats.hits()).isEqualTo(threads * callsPerThread / 2);
            assertThat(stats.misses()).isEqualTo(threads * callsPerThread / 2);
        }
    }

    // -------------------------------------------------------------------------
    // Test infrastructure — minimal StatsCollector for isolated unit testing
    // -------------------------------------------------------------------------

    /** Minimal StatsCollector test double — uses LongAdder for thread-safety. */
    private static class TestStatsCollector implements com.nedder3.cache.core.port.StatsPort {
        private final java.util.concurrent.atomic.LongAdder hitsCount = new java.util.concurrent.atomic.LongAdder();
        private final java.util.concurrent.atomic.LongAdder missesCount = new java.util.concurrent.atomic.LongAdder();
        private final java.util.concurrent.atomic.LongAdder evictionsCount = new java.util.concurrent.atomic.LongAdder();
        private final java.util.concurrent.atomic.LongAdder putsCount = new java.util.concurrent.atomic.LongAdder();
        private final java.util.concurrent.atomic.LongAdder deletesCount = new java.util.concurrent.atomic.LongAdder();

        public void recordHit() { hitsCount.increment(); }
        public void recordMiss() { missesCount.increment(); }
        public void recordEviction() { evictionsCount.increment(); }
        public void recordPut() { putsCount.increment(); }
        public void recordDelete() { deletesCount.increment(); }

        public CacheStats snapshot() {
            return new CacheStats(hitsCount.sum(), missesCount.sum(), evictionsCount.sum(), putsCount.sum(), deletesCount.sum());
        }

        public void reset() {
            hitsCount.reset();
            missesCount.reset();
            evictionsCount.reset();
            putsCount.reset();
            deletesCount.reset();
        }

        long hits()   { return hitsCount.sum(); }
        long misses() { return missesCount.sum(); }
        long evictions() { return evictionsCount.sum(); }
        long puts()   { return putsCount.sum(); }
        long deletes(){ return deletesCount.sum(); }
    }
}
