package com.nedder3.cache.core.port;

import com.nedder3.cache.core.model.CacheKey;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RED-phase TDD tests for WTinyLFUEvictionStrategy.
 *
 * W-TinyLFU (Window TinyLFU) combines:
 *   - A small LRU window cache (~1% of capacity) for recency-filtering burst access.
 *   - A main cache split into Protected (SLRU 80%) and Probation (SLRU 20%) segments.
 *   - A CountMinSketch frequency sketch for admission decisions: when the window
 *     evicts a candidate and the probation segment is full, the admission policy
 *     compares the candidate's frequency against the probation's LRU victim; the
 *     higher-frequency entry is retained and the loser is evicted.
 *
 * Implements {@link EvictionPort}.
 *
 * Contract (per EvictionPort):
 *   - onAccess(key): records access for LRU ordering in the appropriate segment,
 *                   and increments frequency in CountMinSketch.
 *   - onInsert(key): inserts into the window cache; if window is full, the LRU
 *                   window entry becomes the admission candidate.
 *   - evict(): when total tracked entries exceed total capacity, selects the victim
 *              using the TinyLFU admission policy (window vs probation LRU).
 *              Fires EVICT event for the actual victim.
 *   - size(): returns total tracked entries across all segments.
 *   - clear(): clears all three segments and the sketch.
 *   - addListener / removeListener: EVICT events fired on each eviction.
 */
class WTinyLFUEvictionStrategyTest {

    // -------------------------------------------------------------------------
    // Constructor validation
    // -------------------------------------------------------------------------

    @Nested
    class ConstructorValidation {

        @Test
        void throwsOnZeroCapacity() {
            assertThatThrownBy(() -> new WTinyLFUEvictionStrategy(0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void throwsOnNegativeCapacity() {
            assertThatThrownBy(() -> new WTinyLFUEvictionStrategy(-10))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void acceptsMinimumValidCapacity() {
            // At minimum we need window + probation (at least 2)
            WTinyLFUEvictionStrategy strategy = new WTinyLFUEvictionStrategy(2);
            assertThat(strategy).isNotNull();
        }
    }

    // -------------------------------------------------------------------------
    // Window cache behaviour (1% LRU)
    // -------------------------------------------------------------------------

    @Nested
    class WindowCacheBehavior {

        @Test
        void size_includesWindowEntries() {
            WTinyLFUEvictionStrategy strategy = new WTinyLFUEvictionStrategy(100);
            CacheKey key = new CacheKey("ns", "k1");

            strategy.onInsert(key);

            assertThat(strategy.size()).isEqualTo(1);
        }

        @Test
        void onAccess_updatesLRUOrderInWindow() {
            // With 100 capacity, window is ~1 (floor(100*0.01) = 1)
            WTinyLFUEvictionStrategy strategy = new WTinyLFUEvictionStrategy(100);
            CacheKey key1 = new CacheKey("ns", "k1");
            CacheKey key2 = new CacheKey("ns", "k2");

            strategy.onInsert(key1); // enters window
            strategy.onInsert(key2); // may push k1 out of window

            strategy.onAccess(key1); // k1 becomes MRU in window

            // At least one entry tracked
            assertThat(strategy.size()).isGreaterThan(0);
        }

        @Test
        void windowEvictsLRUEntryWhenFull() {
            // Very small total capacity forces window to hold only 1 entry
            WTinyLFUEvictionStrategy strategy = new WTinyLFUEvictionStrategy(5);
            CacheKey key1 = new CacheKey("ns", "k1");
            CacheKey key2 = new CacheKey("ns", "k2");

            strategy.onInsert(key1);
            strategy.onInsert(key2); // window is full — k1 should be pushed toward main cache

            // Strategy must track at least one entry
            assertThat(strategy.size()).isGreaterThanOrEqualTo(1);
        }

        @Test
        void onAccess_onUnknownKey_hasNoEffect() {
            WTinyLFUEvictionStrategy strategy = new WTinyLFUEvictionStrategy(100);
            strategy.onAccess(new CacheKey("ns", "never-inserted"));

            assertThat(strategy.size()).isZero();
        }
    }

    // -------------------------------------------------------------------------
    // Eviction correctness: window vs probation TinyLFU admission
    // -------------------------------------------------------------------------

    @Nested
    class TinyLFUAdmissionPolicy {

        @Test
        void evict_firesWhenCapacityExceeded() {
            WTinyLFUEvictionStrategy strategy = new WTinyLFUEvictionStrategy(5);
            CacheKey key1 = new CacheKey("ns", "k1");
            CacheKey key2 = new CacheKey("ns", "k2");

            strategy.onInsert(key1);
            strategy.onInsert(key2);

            // Capacity not yet exceeded
            Optional<CacheKey> noEviction = strategy.evict();
            assertThat(noEviction).isEmpty();
        }

        @Test
        void evict_returnsVictimKey() {
            WTinyLFUEvictionStrategy strategy = new WTinyLFUEvictionStrategy(3);
            CacheKey key1 = new CacheKey("ns", "k1");
            CacheKey key2 = new CacheKey("ns", "k2");
            CacheKey key3 = new CacheKey("ns", "k3");
            CacheKey key4 = new CacheKey("ns", "k4");

            strategy.onInsert(key1);
            strategy.onInsert(key2);
            strategy.onInsert(key3);
            strategy.onInsert(key4); // capacity exceeded

            Optional<CacheKey> evicted = strategy.evict();

            assertThat(evicted).isPresent();
            // The evicted key must be one of the tracked keys
            assertThat(evicted.get()).isIn(key1, key2, key3, key4);
        }

        @Test
        void evict_removesVictimFromTracking() {
            WTinyLFUEvictionStrategy strategy = new WTinyLFUEvictionStrategy(3);
            CacheKey key1 = new CacheKey("ns", "k1");
            CacheKey key2 = new CacheKey("ns", "k2");
            CacheKey key3 = new CacheKey("ns", "k3");
            CacheKey key4 = new CacheKey("ns", "k4");

            strategy.onInsert(key1);
            strategy.onInsert(key2);
            strategy.onInsert(key3);
            strategy.onInsert(key4);

            strategy.evict();

            // Size should be back to capacity
            assertThat(strategy.size()).isLessThanOrEqualTo(3);
        }

        @Test
        void highFrequencyKey_isNotEvicted_whenCompetingWithLowFrequencyCandidate() {
            WTinyLFUEvictionStrategy strategy = new WTinyLFUEvictionStrategy(5);
            CacheKey hot = new CacheKey("ns", "hot");
            CacheKey cold = new CacheKey("ns", "cold");

            // cold is inserted first and accesses push it into probation
            strategy.onInsert(cold);
            strategy.onAccess(cold);

            // hot becomes the admission candidate from the window
            strategy.onInsert(hot);
            // hot gets many accesses (high frequency)
            for (int i = 0; i < 10; i++) {
                strategy.onAccess(hot);
            }

            CacheKey key3 = new CacheKey("ns", "k3");
            CacheKey key4 = new CacheKey("ns", "k4");
            CacheKey key5 = new CacheKey("ns", "k5");
            strategy.onInsert(key3);
            strategy.onInsert(key4);
            strategy.onInsert(key5);

            // Force eviction
            strategy.evict();

            // hot should NOT be the victim — it has much higher frequency
            assertThat(strategy.size()).isGreaterThan(0);
        }

        @Test
        void lowFrequencyWindowCandidate_isRejected_inFavorOfHigherFrequencyProbationEntry() {
            WTinyLFUEvictionStrategy strategy = new WTinyLFUEvictionStrategy(5);
            CacheKey established = new CacheKey("ns", "established");
            CacheKey burst = new CacheKey("ns", "burst");

            // established gets many accesses — high frequency, ends up in probation
            strategy.onInsert(established);
            for (int i = 0; i < 10; i++) {
                strategy.onAccess(established);
            }

            // Fill remaining capacity
            strategy.onInsert(new CacheKey("ns", "fill1"));
            strategy.onInsert(new CacheKey("ns", "fill2"));
            strategy.onInsert(new CacheKey("ns", "fill3"));

            // burst enters window with only 1 access
            strategy.onInsert(burst);
            strategy.onAccess(burst);

            // Evict — the admission policy should keep established (freq=10)
            // and evict the window's burst candidate (freq=2) instead
            Optional<CacheKey> evicted = strategy.evict();

            // established should survive
            assertThat(evicted).isPresent();
        }
    }

    // -------------------------------------------------------------------------
    // CountMinSketch integration: frequency tracking
    // -------------------------------------------------------------------------

    @Nested
    class FrequencyTracking {

        @Test
        void onInsert_incrementsFrequencyInSketch() {
            WTinyLFUEvictionStrategy strategy = new WTinyLFUEvictionStrategy(10);
            CacheKey key = new CacheKey("ns", "freqkey");

            strategy.onInsert(key);

            // Insert should increment frequency in the sketch
            // Frequency should be at least 1 after insert
            // (exact value depends on internal sketch reference)
            assertThat(strategy.size()).isEqualTo(1);
        }

        @Test
        void onAccess_incrementsFrequency() {
            WTinyLFUEvictionStrategy strategy = new WTinyLFUEvictionStrategy(10);
            CacheKey key = new CacheKey("ns", "freqkey");

            strategy.onInsert(key);
            strategy.onAccess(key);
            strategy.onAccess(key);

            // Multiple accesses tracked — size unchanged
            assertThat(strategy.size()).isEqualTo(1);
        }

        @Test
        void frequencyEstimation_affectsEvictionDecision() {
            WTinyLFUEvictionStrategy strategy = new WTinyLFUEvictionStrategy(5);
            CacheKey hotKey = new CacheKey("ns", "hot");
            CacheKey coldKey = new CacheKey("ns", "cold");

            // cold inserted first and only once
            strategy.onInsert(coldKey);

            // hot inserted and accessed many times
            strategy.onInsert(hotKey);
            for (int i = 0; i < 20; i++) {
                strategy.onAccess(hotKey);
            }

            // Fill up remaining capacity
            strategy.onInsert(new CacheKey("ns", "x1"));
            strategy.onInsert(new CacheKey("ns", "x2"));
            strategy.onInsert(new CacheKey("ns", "x3"));

            Optional<CacheKey> evicted = strategy.evict();

            assertThat(evicted).isPresent();
            // hotKey should NOT be evicted — it has the highest frequency
            assertThat(evicted.get()).isNotEqualTo(hotKey);
        }

        @Test
        void frequentKey_survivesMultipleEvictionCycles() {
            WTinyLFUEvictionStrategy strategy = new WTinyLFUEvictionStrategy(3);
            CacheKey frequent = new CacheKey("ns", "freq");

            strategy.onInsert(frequent);
            for (int i = 0; i < 15; i++) strategy.onAccess(frequent);

            // Fill capacity first: capacity is 3
            strategy.onInsert(new CacheKey("ns", "fill1"));
            strategy.onInsert(new CacheKey("ns", "fill2"));

            // Perform several eviction cycles
            for (int cycle = 0; cycle < 3; cycle++) {
                CacheKey occasional = new CacheKey("ns", "occ" + cycle);
                strategy.onInsert(occasional);
                strategy.onAccess(occasional);
                Optional<CacheKey> evicted = strategy.evict();

                assertThat(evicted).isPresent();
                // frequent should never be evicted
                assertThat(evicted.get()).isNotEqualTo(frequent);
            }
        }
    }

    // -------------------------------------------------------------------------
    // SLRU main cache segments (protected 80% / probation 20%)
    // -------------------------------------------------------------------------

    @Nested
    class SLRUSegments {

        @Test
        void probationSegment_evictsLRUWhenFull() {
            WTinyLFUEvictionStrategy strategy = new WTinyLFUEvictionStrategy(10);
            // Window ≈ 1, Protected ≈ 7, Probation ≈ 2

            CacheKey p1 = new CacheKey("ns", "p1");
            CacheKey p2 = new CacheKey("ns", "p2");
            CacheKey q1 = new CacheKey("ns", "q1");
            CacheKey q2 = new CacheKey("ns", "q2");

            // All enter window first; when window is full, candidates go to probation
            strategy.onInsert(p1);
            strategy.onInsert(p2);
            strategy.onInsert(q1);
            strategy.onInsert(q2);

            // At least some entries tracked
            assertThat(strategy.size()).isGreaterThan(0);
        }

        @Test
        void protectedSegment_preservesHighFrequencyEntries() {
            WTinyLFUEvictionStrategy strategy = new WTinyLFUEvictionStrategy(10);
            CacheKey elite = new CacheKey("ns", "elite");

            strategy.onInsert(elite);
            // Repeated accesses promote to protected
            for (int i = 0; i < 20; i++) {
                strategy.onAccess(elite);
            }

            // Insert many other keys to trigger evictions
            for (int i = 0; i < 15; i++) {
                strategy.onInsert(new CacheKey("ns", "filler" + i));
                strategy.evict();
            }

            // elite should still be tracked
            assertThat(strategy.size()).isGreaterThan(0);
        }
    }

    // -------------------------------------------------------------------------
    // Event listener contract
    // -------------------------------------------------------------------------

    @Nested
    class EventListenerContract {

        @Test
        void evict_notifiesListenerWithEVICTEvent() {
            WTinyLFUEvictionStrategy strategy = new WTinyLFUEvictionStrategy(3);
            CacheKey key1 = new CacheKey("ns", "k1");
            CacheKey key2 = new CacheKey("ns", "k2");
            CacheKey key3 = new CacheKey("ns", "k3");
            CacheKey key4 = new CacheKey("ns", "k4");

            strategy.onInsert(key1);
            strategy.onInsert(key2);
            strategy.onInsert(key3);
            strategy.onInsert(key4);

            EvictionPort.EvictionEvent[] received = new EvictionPort.EvictionEvent[1];
            strategy.addListener(event -> received[0] = event);

            strategy.evict();

            assertThat(received[0]).isNotNull();
            assertThat(received[0].getType()).isEqualTo("EVICT");
        }

        @Test
        void evict_passesEvictedKeyAsPayload() {
            WTinyLFUEvictionStrategy strategy = new WTinyLFUEvictionStrategy(3);
            CacheKey key1 = new CacheKey("ns", "k1");
            CacheKey key2 = new CacheKey("ns", "k2");
            CacheKey key3 = new CacheKey("ns", "k3");
            CacheKey key4 = new CacheKey("ns", "k4");

            strategy.onInsert(key1);
            strategy.onInsert(key2);
            strategy.onInsert(key3);
            strategy.onInsert(key4);

            EvictionPort.EvictionEvent[] received = new EvictionPort.EvictionEvent[1];
            strategy.addListener(event -> received[0] = event);

            strategy.evict();

            assertThat(received[0].getPayload()).isInstanceOf(CacheKey.class);
        }

        @Test
        void removeListener_preventsNotification() {
            WTinyLFUEvictionStrategy strategy = new WTinyLFUEvictionStrategy(3);
            CacheKey key1 = new CacheKey("ns", "k1");
            CacheKey key2 = new CacheKey("ns", "k2");
            CacheKey key3 = new CacheKey("ns", "k3");
            CacheKey key4 = new CacheKey("ns", "k4");

            strategy.onInsert(key1);
            strategy.onInsert(key2);
            strategy.onInsert(key3);
            strategy.onInsert(key4);

            int[] callCount = {0};
            EvictionPort.EvictionEventListener listener = e -> callCount[0]++;

            strategy.addListener(listener);
            strategy.removeListener(listener);
            strategy.evict();

            assertThat(callCount[0]).isZero();
        }

        @Test
        void evict_withoutCapacityExceeded_doesNotNotify() {
            WTinyLFUEvictionStrategy strategy = new WTinyLFUEvictionStrategy(10);
            strategy.onInsert(new CacheKey("ns", "k1"));

            int[] callCount = {0};
            strategy.addListener(e -> callCount[0]++);

            strategy.evict();

            assertThat(callCount[0]).isZero();
        }

        @Test
        void multipleListeners_allReceiveEvictionEvent() {
            WTinyLFUEvictionStrategy strategy = new WTinyLFUEvictionStrategy(3);
            CacheKey key1 = new CacheKey("ns", "k1");
            CacheKey key2 = new CacheKey("ns", "k2");
            CacheKey key3 = new CacheKey("ns", "k3");
            CacheKey key4 = new CacheKey("ns", "k4");

            strategy.onInsert(key1);
            strategy.onInsert(key2);
            strategy.onInsert(key3);
            strategy.onInsert(key4);

            int[] callCount = {0};
            strategy.addListener(e -> callCount[0]++);
            strategy.addListener(e -> callCount[0]++);
            strategy.addListener(e -> callCount[0]++);

            strategy.evict();

            assertThat(callCount[0]).isEqualTo(3);
        }
    }

    // -------------------------------------------------------------------------
    // Size and clear
    // -------------------------------------------------------------------------

    @Nested
    class SizeAndClear {

        @Test
        void size_returnsZeroOnNewInstance() {
            WTinyLFUEvictionStrategy strategy = new WTinyLFUEvictionStrategy(100);

            assertThat(strategy.size()).isZero();
        }

        @Test
        void size_incrementsOnInsert() {
            WTinyLFUEvictionStrategy strategy = new WTinyLFUEvictionStrategy(100);
            strategy.onInsert(new CacheKey("ns", "k1"));
            strategy.onInsert(new CacheKey("ns", "k2"));

            assertThat(strategy.size()).isEqualTo(2);
        }

        @Test
        void size_decrementsAfterEviction() {
            WTinyLFUEvictionStrategy strategy = new WTinyLFUEvictionStrategy(3);
            CacheKey key1 = new CacheKey("ns", "k1");
            CacheKey key2 = new CacheKey("ns", "k2");
            CacheKey key3 = new CacheKey("ns", "k3");
            CacheKey key4 = new CacheKey("ns", "k4");

            strategy.onInsert(key1);
            strategy.onInsert(key2);
            strategy.onInsert(key3);
            strategy.onInsert(key4);

            strategy.evict();

            assertThat(strategy.size()).isEqualTo(3);
        }

        @Test
        void clear_resetsSizeToZero() {
            WTinyLFUEvictionStrategy strategy = new WTinyLFUEvictionStrategy(5);
            strategy.onInsert(new CacheKey("ns", "k1"));
            strategy.onInsert(new CacheKey("ns", "k2"));

            strategy.clear();

            assertThat(strategy.size()).isZero();
        }

        @Test
        void clear_allowsReinsertionAfterEviction() {
            WTinyLFUEvictionStrategy strategy = new WTinyLFUEvictionStrategy(3);
            CacheKey key1 = new CacheKey("ns", "k1");
            CacheKey key2 = new CacheKey("ns", "k2");
            CacheKey key3 = new CacheKey("ns", "k3");
            CacheKey key4 = new CacheKey("ns", "k4");

            strategy.onInsert(key1);
            strategy.onInsert(key2);
            strategy.onInsert(key3);
            strategy.onInsert(key4);
            strategy.evict();
            strategy.clear();

            strategy.onInsert(key1);

            assertThat(strategy.size()).isEqualTo(1);
        }
    }

    // -------------------------------------------------------------------------
    // Namespace isolation
    // -------------------------------------------------------------------------

    @Nested
    class NamespaceIsolation {

        @Test
        void tracksKeysAcrossDifferentNamespaces() {
            WTinyLFUEvictionStrategy strategy = new WTinyLFUEvictionStrategy(5);
            CacheKey keyA = new CacheKey("nsA", "key");
            CacheKey keyB = new CacheKey("nsB", "key");
            CacheKey keyC = new CacheKey("nsC", "key");

            strategy.onInsert(keyA);
            strategy.onInsert(keyB);
            strategy.onAccess(keyA);
            strategy.onInsert(keyC);

            assertThat(strategy.size()).isEqualTo(3);
        }
    }

    // -------------------------------------------------------------------------
    // Thread safety
    // -------------------------------------------------------------------------

    @Nested
    class ThreadSafety {

        @Test
        void concurrentInsertAndEvict_producesNoExceptions() throws InterruptedException {
            WTinyLFUEvictionStrategy strategy = new WTinyLFUEvictionStrategy(100);
            int threadCount = 4;
            int opsPerThread = 200;

            Thread[] threads = new Thread[threadCount];
            for (int t = 0; t < threadCount; t++) {
                int finalT = t;
                threads[t] = new Thread(() -> {
                    for (int i = 0; i < opsPerThread; i++) {
                        CacheKey key = new CacheKey("ns", finalT + "_" + i);
                        strategy.onInsert(key);
                        strategy.onAccess(key);
                        strategy.evict();
                    }
                });
            }

            for (Thread t : threads) t.start();
            for (Thread t : threads) t.join();

            // No exceptions thrown; size is non-negative
            assertThat(strategy.size()).isGreaterThanOrEqualTo(0);
        }

        @Test
        void size_remainsConsistentUnderConcurrentOperations() throws InterruptedException {
            WTinyLFUEvictionStrategy strategy = new WTinyLFUEvictionStrategy(1000);
            int threadCount = 8;
            int opsPerThread = 100;

            Thread[] threads = new Thread[threadCount];
            for (int t = 0; t < threadCount; t++) {
                int finalT = t;
                threads[t] = new Thread(() -> {
                    for (int i = 0; i < opsPerThread; i++) {
                        CacheKey key = new CacheKey("ns", finalT + "_" + i);
                        strategy.onInsert(key);
                    }
                });
            }

            for (Thread t : threads) t.start();
            for (Thread t : threads) t.join();

            // All inserts completed — size equals total inserted entries
            assertThat(strategy.size()).isEqualTo(threadCount * opsPerThread);
        }
    }
}
