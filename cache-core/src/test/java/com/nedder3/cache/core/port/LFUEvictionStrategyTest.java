package com.nedder3.cache.core.port;

import com.nedder3.cache.core.model.CacheKey;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RED-phase TDD tests for LFUEvictionStrategy.
 *
 * These tests define the expected contract of the LFU eviction strategy and must fail
 * until the implementation is provided by Cy.
 *
 * Contract (per EvictionPort):
 * - onAccess(key): increments the access frequency counter for key.
 * - onInsert(key): registers key with frequency = 1.
 * - evict(): returns Optional of the key with the lowest frequency count; on ties, the
 *   least-recently-accessed (by access-time order) wins.
 *   Eviction is triggered when size() > capacity. Removes the selected key from tracking.
 * - size(): returns the number of tracked keys.
 * - clear(): removes all tracked keys.
 * - addListener / removeListener: manage EvictionEventListener subscriptions.
 * - notify EVICT event with the evicted key as payload on every successful eviction.
 */
class LFUEvictionStrategyTest {

    // -------------------------------------------------------------------------
    // Constructor validation
    // -------------------------------------------------------------------------

    @Nested
    class ConstructorValidation {

        @Test
        void throwsOnZeroCapacity() {
            assertThatThrownBy(() -> new LFUEvictionStrategy(0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("positive");
        }

        @Test
        void throwsOnNegativeCapacity() {
            assertThatThrownBy(() -> new LFUEvictionStrategy(-1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("positive");
        }
    }

    // -------------------------------------------------------------------------
    // Basic eviction correctness — least-frequently-used wins
    // -------------------------------------------------------------------------

    @Nested
    class EvictionSelectsLeastFrequentlyUsed {

        @Test
        void evict_returnsKeyWithLowestFrequencyCount() {
            LFUEvictionStrategy strategy = new LFUEvictionStrategy(2);
            CacheKey key1 = new CacheKey("ns", "k1");
            CacheKey key2 = new CacheKey("ns", "k2");

            strategy.onInsert(key1);
            strategy.onInsert(key2);

            // k1 accessed once, k2 never accessed
            strategy.onAccess(key1);

            // After k3 insert, cache is over capacity (3 > 2)
            CacheKey key3 = new CacheKey("ns", "k3");
            strategy.onInsert(key3);

            Optional<CacheKey> evicted = strategy.evict();

            // k2 has frequency 0, so it must be the victim
            assertThat(evicted).contains(key2);
        }

        @Test
        void evict_returnsLowestFrequencyAmongMultipleAccesses() {
            LFUEvictionStrategy strategy = new LFUEvictionStrategy(2);
            CacheKey key1 = new CacheKey("ns", "k1");
            CacheKey key2 = new CacheKey("ns", "k2");

            strategy.onInsert(key1);
            strategy.onInsert(key2);

            // k1 accessed 3 times (total 4), k2 accessed 5 times (total 6)
            strategy.onAccess(key1);
            strategy.onAccess(key1);
            strategy.onAccess(key1);
            strategy.onAccess(key2);
            strategy.onAccess(key2);
            strategy.onAccess(key2);
            strategy.onAccess(key2);
            strategy.onAccess(key2);

            CacheKey key3 = new CacheKey("ns", "k3");
            strategy.onInsert(key3); // k3 has freq=1

            Optional<CacheKey> evicted = strategy.evict();

            // k3 has freq 1, lowest among k1(4) and k2(6) -> k3 is the victim
            assertThat(evicted).contains(key3);
        }
    }

    // -------------------------------------------------------------------------
    // Tie-breaking: LRU among equal frequencies
    // -------------------------------------------------------------------------

    @Nested
    class TieBreakingLRU {

        @Test
        void evict_breaksTiesInFavorOfLeastRecentlyAccessed() {
            LFUEvictionStrategy strategy = new LFUEvictionStrategy(2);
            CacheKey key1 = new CacheKey("ns", "k1");
            CacheKey key2 = new CacheKey("ns", "k2");

            strategy.onInsert(key1); // freq: k1=1
            strategy.onInsert(key2); // freq: k2=1, k1=1 (tie)

            // Both have same frequency; k1 accessed after k2, so k2 is LRU
            strategy.onAccess(key2);

            CacheKey key3 = new CacheKey("ns", "k3");
            strategy.onInsert(key3);

            Optional<CacheKey> evicted = strategy.evict();

            // Tie: k1=1 (LRU), k2=1 (MRU) → k1 is evicted
            assertThat(evicted).contains(key1);
        }

        @Test
        void evict_breaksTiesWithInsertionOrderWhenNoAccesses() {
            LFUEvictionStrategy strategy = new LFUEvictionStrategy(2);
            CacheKey key1 = new CacheKey("ns", "k1");
            CacheKey key2 = new CacheKey("ns", "k2");

            strategy.onInsert(key1);
            strategy.onInsert(key2);
            // Both have frequency 1, no accesses — k1 was inserted first (LRU)

            CacheKey key3 = new CacheKey("ns", "k3");
            strategy.onInsert(key3);

            Optional<CacheKey> evicted = strategy.evict();

            // Tie with no access ordering: k1 inserted first → k1 evicted
            assertThat(evicted).contains(key1);
        }
    }

    // -------------------------------------------------------------------------
    // Trigger condition: evict() only fires when size > capacity
    // -------------------------------------------------------------------------

    @Nested
    class TriggerCondition {

        @Test
        void evict_returnsEmptyWhenSizeEqualsCapacity() {
            LFUEvictionStrategy strategy = new LFUEvictionStrategy(2);
            CacheKey key1 = new CacheKey("ns", "k1");
            CacheKey key2 = new CacheKey("ns", "k2");

            strategy.onInsert(key1);
            strategy.onInsert(key2);
            // size == capacity, no eviction should trigger

            assertThat(strategy.evict()).isEmpty();
        }

        @Test
        void evict_returnsEmptyOnEmptyStrategy() {
            LFUEvictionStrategy strategy = new LFUEvictionStrategy(5);

            assertThat(strategy.evict()).isEmpty();
        }

        @Test
        void evict_returnsVictimAndRemovesItFromTracking() {
            LFUEvictionStrategy strategy = new LFUEvictionStrategy(2);
            CacheKey key1 = new CacheKey("ns", "k1");
            CacheKey key2 = new CacheKey("ns", "k2");

            strategy.onInsert(key1);
            strategy.onInsert(key2);
            strategy.onAccess(key1);

            CacheKey key3 = new CacheKey("ns", "k3");
            strategy.onInsert(key3);

            strategy.evict();

            // After eviction the strategy should be back at capacity
            assertThat(strategy.size()).isEqualTo(2);
        }
    }

    // -------------------------------------------------------------------------
    // Access frequency: onAccess increments counter, onInsert starts at 1
    // -------------------------------------------------------------------------

    @Nested
    class AccessFrequencyTracking {

        @Test
        void onInsert_initializesFrequencyToOne() {
            LFUEvictionStrategy strategy = new LFUEvictionStrategy(3);
            CacheKey key1 = new CacheKey("ns", "k1");
            CacheKey key2 = new CacheKey("ns", "k2");
            CacheKey key3 = new CacheKey("ns", "k3");
            CacheKey key4 = new CacheKey("ns", "k4");

            strategy.onInsert(key1);
            strategy.onInsert(key2);
            strategy.onInsert(key3);

            // k1 inserted but never accessed, so freq=1 → LFU candidate
            strategy.onAccess(key2);
            strategy.onAccess(key2);
            strategy.onAccess(key3);
            strategy.onAccess(key3);
            strategy.onAccess(key3);

            strategy.onInsert(key4);

            Optional<CacheKey> evicted = strategy.evict();

            // k1 has freq=1, k2 has freq=3, k3 has freq=4 → k1 is evicted
            assertThat(evicted).contains(key1);
        }

        @Test
        void onAccess_incrementsFrequencyOnSubsequentCalls() {
            LFUEvictionStrategy strategy = new LFUEvictionStrategy(2);
            CacheKey key1 = new CacheKey("ns", "k1");
            CacheKey key2 = new CacheKey("ns", "k2");

            strategy.onInsert(key1); // freq(k1) = 1
            strategy.onInsert(key2); // freq(k2) = 1

            // k1 gets 3 more accesses → freq(k1) = 4
            strategy.onAccess(key1);
            strategy.onAccess(key1);
            strategy.onAccess(key1);

            // k2 gets 1 access → freq(k2) = 2
            strategy.onAccess(key2);

            CacheKey key3 = new CacheKey("ns", "k3");
            strategy.onInsert(key3); // freq(k3) = 1
            strategy.onAccess(key3);
            strategy.onAccess(key3); // freq(k3) = 3

            Optional<CacheKey> evicted = strategy.evict();

            // Frequencies: k1=4, k2=2, k3=3 -> k2 is evicted (lowest freq)
            assertThat(evicted).contains(key2);
        }

        @Test
        void onAccess_onUntrackedKey_hasNoEffect() {
            LFUEvictionStrategy strategy = new LFUEvictionStrategy(3);
            CacheKey key1 = new CacheKey("ns", "k1");
            CacheKey unknown = new CacheKey("ns", "unknown");

            strategy.onInsert(key1);
            strategy.onAccess(unknown); // no-op — key not tracked

            assertThat(strategy.size()).isEqualTo(1);
        }
    }

    // -------------------------------------------------------------------------
    // Size and clear
    // -------------------------------------------------------------------------

    @Nested
    class SizeAndClear {

        @Test
        void size_returnsZeroOnNewInstance() {
            LFUEvictionStrategy strategy = new LFUEvictionStrategy(10);

            assertThat(strategy.size()).isZero();
        }

        @Test
        void size_incrementsOnInsert() {
            LFUEvictionStrategy strategy = new LFUEvictionStrategy(5);
            strategy.onInsert(new CacheKey("ns", "k1"));
            strategy.onInsert(new CacheKey("ns", "k2"));

            assertThat(strategy.size()).isEqualTo(2);
        }

        @Test
        void size_decrementsAfterEviction() {
            LFUEvictionStrategy strategy = new LFUEvictionStrategy(2);
            CacheKey key1 = new CacheKey("ns", "k1");
            CacheKey key2 = new CacheKey("ns", "k2");

            strategy.onInsert(key1);
            strategy.onInsert(key2);

            CacheKey key3 = new CacheKey("ns", "k3");
            strategy.onInsert(key3);

            strategy.evict();

            assertThat(strategy.size()).isEqualTo(2);
        }

        @Test
        void clear_resetsSizeToZero() {
            LFUEvictionStrategy strategy = new LFUEvictionStrategy(2);
            strategy.onInsert(new CacheKey("ns", "k1"));
            strategy.onInsert(new CacheKey("ns", "k2"));

            strategy.clear();

            assertThat(strategy.size()).isZero();
        }

        @Test
        void clear_allowsReinsertionAfterEviction() {
            LFUEvictionStrategy strategy = new LFUEvictionStrategy(2);
            CacheKey key1 = new CacheKey("ns", "k1");
            CacheKey key2 = new CacheKey("ns", "k2");
            CacheKey key3 = new CacheKey("ns", "k3");

            strategy.onInsert(key1);
            strategy.onInsert(key2);
            strategy.onInsert(key3);
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
            LFUEvictionStrategy strategy = new LFUEvictionStrategy(2);
            CacheKey keyA = new CacheKey("nsA", "key");
            CacheKey keyB = new CacheKey("nsB", "key");

            strategy.onInsert(keyA);
            strategy.onInsert(keyB);
            strategy.onAccess(keyA);

            CacheKey keyC = new CacheKey("nsC", "key");
            strategy.onInsert(keyC);

            // keyB has freq=1 (never accessed afterwards), keyA has freq=2, keyC has freq=1 (MRU tie with keyB)
            // so keyB is LRU among freq=1 entries
            Optional<CacheKey> evicted = strategy.evict();

            assertThat(evicted).contains(keyB);
        }
    }

    // -------------------------------------------------------------------------
    // Event listener contract
    // -------------------------------------------------------------------------

    @Nested
    class EventListenerContract {

        @Test
        void evict_notifiesListenerWithEVICTEvent() {
            LFUEvictionStrategy strategy = new LFUEvictionStrategy(2);
            CacheKey key1 = new CacheKey("ns", "k1");
            CacheKey key2 = new CacheKey("ns", "k2");

            strategy.onInsert(key1);
            strategy.onInsert(key2);
            strategy.onAccess(key1);

            CacheKey key3 = new CacheKey("ns", "k3");
            strategy.onInsert(key3);

            EvictionPort.EvictionEvent[] received = new EvictionPort.EvictionEvent[1];
            EvictionPort.EvictionEventListener spy = event -> received[0] = event;
            strategy.addListener(spy);

            strategy.evict();

            assertThat(received[0]).isNotNull();
            assertThat(received[0].getType()).isEqualTo("EVICT");
            assertThat(received[0].getPayload()).isInstanceOf(CacheKey.class);
        }

        @Test
        void removeListener_preventsNotification() {
            LFUEvictionStrategy strategy = new LFUEvictionStrategy(2);
            CacheKey key1 = new CacheKey("ns", "k1");
            CacheKey key2 = new CacheKey("ns", "k2");

            strategy.onInsert(key1);
            strategy.onInsert(key2);

            CacheKey key3 = new CacheKey("ns", "k3");
            strategy.onInsert(key3);

            int[] callCount = {0};
            EvictionPort.EvictionEventListener listener = e -> callCount[0]++;

            strategy.addListener(listener);
            strategy.removeListener(listener);
            strategy.evict();

            assertThat(callCount[0]).isZero();
        }

        @Test
        void listenerAddedAfterInsertion_stillNotifiedOnEviction() {
            LFUEvictionStrategy strategy = new LFUEvictionStrategy(2);
            CacheKey key1 = new CacheKey("ns", "k1");
            CacheKey key2 = new CacheKey("ns", "k2");

            strategy.onInsert(key1);
            strategy.onInsert(key2);
            strategy.onAccess(key1);

            CacheKey key3 = new CacheKey("ns", "k3");
            strategy.onInsert(key3);

            int[] callCount = {0};
            strategy.addListener(e -> callCount[0]++);

            strategy.evict();

            assertThat(callCount[0]).isEqualTo(1);
        }

        @Test
        void multipleListeners_allReceiveEvictionEvent() {
            LFUEvictionStrategy strategy = new LFUEvictionStrategy(2);
            CacheKey key1 = new CacheKey("ns", "k1");
            CacheKey key2 = new CacheKey("ns", "k2");

            strategy.onInsert(key1);
            strategy.onInsert(key2);

            CacheKey key3 = new CacheKey("ns", "k3");
            strategy.onInsert(key3);

            int[] callCount = {0};
            strategy.addListener(e -> callCount[0]++);
            strategy.addListener(e -> callCount[0]++);
            strategy.addListener(e -> callCount[0]++);

            strategy.evict();

            assertThat(callCount[0]).isEqualTo(3);
        }

        @Test
        void evict_withoutCapacityExceeded_doesNotNotify() {
            LFUEvictionStrategy strategy = new LFUEvictionStrategy(5);
            CacheKey key1 = new CacheKey("ns", "k1");

            strategy.onInsert(key1);

            int[] callCount = {0};
            strategy.addListener(e -> callCount[0]++);

            strategy.evict();

            assertThat(callCount[0]).isZero();
        }
    }

    // -------------------------------------------------------------------------
    // Concurrent access safety (best-effort)
    // -------------------------------------------------------------------------

    @Nested
    class ConcurrentSafety {

        @Test
        void size_remainsConsistentUnderConcurrentInsert() throws InterruptedException {
            LFUEvictionStrategy strategy = new LFUEvictionStrategy(1000);
            int targetCount = 200;

            Thread[] threads = new Thread[targetCount];
            for (int i = 0; i < targetCount; i++) {
                final int id = i;
                threads[i] = new Thread(() ->
                        strategy.onInsert(new CacheKey("ns", "k" + id)));
            }

            for (Thread t : threads) {
                t.start();
            }
            for (Thread t : threads) {
                t.join();
            }

            assertThat(strategy.size()).isEqualTo(targetCount);
        }

        @Test
        void evict_isIdempotentWhenNoCapacityExceeded() {
            LFUEvictionStrategy strategy = new LFUEvictionStrategy(5);
            strategy.onInsert(new CacheKey("ns", "k1"));

            Optional<CacheKey> first = strategy.evict();
            Optional<CacheKey> second = strategy.evict();

            assertThat(first).isEmpty();
            assertThat(second).isEmpty();
        }
    }
}
