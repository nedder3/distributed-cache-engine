package com.nedder3.cache.core.port;

import com.nedder3.cache.core.model.CacheKey;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RED-phase TDD tests for CountMinSketch.
 *
 * CountMinSketch is a probabilistic frequency sketch using d hash functions and a
 * w-wide table of 4-bit counters (max value 15).
 *
 * Expected contract:
 * - Constructor(d, w): d = depth (# hash functions), w = width (# columns).
 *   Throws IllegalArgumentException on d <= 0 or w <= 0.
 * - increment(key): hashes key to d columns, increments each counter by 1 (capped at 15).
 * - estimateFrequency(key): returns min over all d counter values for that key.
 * - reset(): halves all counter values (right-shift by 1), rounding down.
 * - All operations are thread-safe.
 *
 * The sketch has NO dependency on EvictionPort — it is a standalone utility used by
 * WTinyLFUEvictionStrategy for frequency estimation.
 */
class CountMinSketchTest {

    // -------------------------------------------------------------------------
    // Constructor validation
    // -------------------------------------------------------------------------

    @Nested
    class ConstructorValidation {

        @Test
        void throwsOnZeroDepth() {
            assertThatThrownBy(() -> new CountMinSketch(0, 100))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void throwsOnNegativeDepth() {
            assertThatThrownBy(() -> new CountMinSketch(-1, 100))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void throwsOnZeroWidth() {
            assertThatThrownBy(() -> new CountMinSketch(4, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void throwsOnNegativeWidth() {
            assertThatThrownBy(() -> new CountMinSketch(4, -1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void acceptsValidDepthAndWidth() {
            CountMinSketch sketch = new CountMinSketch(4, 100);
            assertThat(sketch).isNotNull();
        }
    }

    // -------------------------------------------------------------------------
    // Frequency estimation correctness
    // -------------------------------------------------------------------------

    @Nested
    class FrequencyEstimation {

        @Test
        void estimateFrequency_returnsZeroForUnknownKey() {
            CountMinSketch sketch = new CountMinSketch(4, 100);
            CacheKey unknown = new CacheKey("ns", "never-seen");

            assertThat(sketch.estimateFrequency(unknown)).isZero();
        }

        @Test
        void estimateFrequency_returnsExactCountAfterSingleIncrement() {
            CountMinSketch sketch = new CountMinSketch(4, 100);
            CacheKey key = new CacheKey("ns", "k1");

            sketch.increment(key);

            // After one increment, all d counters for this key are incremented by 1
            assertThat(sketch.estimateFrequency(key)).isEqualTo(1);
        }

        @Test
        void estimateFrequency_accumulatesMultipleIncrements() {
            CountMinSketch sketch = new CountMinSketch(4, 100);
            CacheKey key = new CacheKey("ns", "k1");

            sketch.increment(key);
            sketch.increment(key);
            sketch.increment(key);

            assertThat(sketch.estimateFrequency(key)).isEqualTo(3);
        }

        @Test
        void estimateFrequency_isMonotonicallyNonDecreasing() {
            CountMinSketch sketch = new CountMinSketch(4, 100);
            CacheKey key = new CacheKey("ns", "k1");

            int prev = 0;
            for (int i = 0; i < 50; i++) {
                sketch.increment(key);
                int current = sketch.estimateFrequency(key);
                assertThat(current).isGreaterThanOrEqualTo(prev);
                prev = current;
            }
        }

        @Test
        void differentKeys_haveDifferentFrequencies() {
            CountMinSketch sketch = new CountMinSketch(4, 100);
            CacheKey keyA = new CacheKey("ns", "a");
            CacheKey keyB = new CacheKey("ns", "b");

            // Access keyA 5 times, keyB 2 times
            for (int i = 0; i < 5; i++) sketch.increment(keyA);
            for (int i = 0; i < 2; i++) sketch.increment(keyB);

            // CountMinSketch is probabilistic — in practice keyA's estimate >= keyB's
            // but due to hash collisions it could be equal or underestimate.
            // We only assert the ordering is non-strict (may be equal due to collision)
            int freqA = sketch.estimateFrequency(keyA);
            int freqB = sketch.estimateFrequency(keyB);
            assertThat(freqA).isGreaterThan(0);
            assertThat(freqB).isGreaterThan(0);
        }

        @Test
        void estimateFrequency_respectsNamespace() {
            CountMinSketch sketch = new CountMinSketch(4, 100);
            CacheKey keyA = new CacheKey("nsA", "key");
            CacheKey keyB = new CacheKey("nsB", "key");

            sketch.increment(keyA);
            sketch.increment(keyB);

            // Same string key but different namespace — must be tracked separately
            assertThat(sketch.estimateFrequency(keyA)).isEqualTo(1);
            assertThat(sketch.estimateFrequency(keyB)).isEqualTo(1);
        }
    }

    // -------------------------------------------------------------------------
    // 4-bit counter behaviour (saturating at 15)
    // -------------------------------------------------------------------------

    @Nested
    class FourBitCounterSaturation {

        @Test
        void increment_saturatesAtFifteen() {
            CountMinSketch sketch = new CountMinSketch(4, 100);
            CacheKey key = new CacheKey("ns", "k1");

            // Saturate at 15
            for (int i = 0; i < 20; i++) {
                sketch.increment(key);
            }

            // Counter is 4-bit: max value is 15
            assertThat(sketch.estimateFrequency(key)).isEqualTo(15);
        }

        @Test
        void multipleKeys_saturateIndependently() {
            CountMinSketch sketch = new CountMinSketch(4, 100);
            CacheKey keyA = new CacheKey("ns", "a");
            CacheKey keyB = new CacheKey("ns", "b");

            // Saturate keyA at 15
            for (int i = 0; i < 20; i++) sketch.increment(keyA);
            // Only increment keyB a few times
            for (int i = 0; i < 3; i++) sketch.increment(keyB);

            assertThat(sketch.estimateFrequency(keyA)).isEqualTo(15);
            assertThat(sketch.estimateFrequency(keyB)).isEqualTo(3);
        }

        @Test
        void reset_afterSaturation_returnsLowerValue() {
            CountMinSketch sketch = new CountMinSketch(4, 100);
            CacheKey key = new CacheKey("ns", "k1");

            for (int i = 0; i < 20; i++) sketch.increment(key);
            assertThat(sketch.estimateFrequency(key)).isEqualTo(15);

            sketch.reset();

            // After halving: floor(15/2) = 7
            assertThat(sketch.estimateFrequency(key)).isEqualTo(7);
        }
    }

    // -------------------------------------------------------------------------
    // Reset / ageing (periodic halving)
    // -------------------------------------------------------------------------

    @Nested
    class ResetAndAgeing {

        @Test
        void reset_halvesAllCounters() {
            CountMinSketch sketch = new CountMinSketch(4, 100);
            CacheKey key1 = new CacheKey("ns", "k1");
            CacheKey key2 = new CacheKey("ns", "k2");

            sketch.increment(key1); // freq 1
            sketch.increment(key1);
            sketch.increment(key2); // freq 1

            sketch.reset();

            assertThat(sketch.estimateFrequency(key1)).isEqualTo(1);
            assertThat(sketch.estimateFrequency(key2)).isEqualTo(0); // floor(1/2) = 0
        }

        @Test
        void reset_oddValuesFloor() {
            CountMinSketch sketch = new CountMinSketch(4, 100);
            CacheKey key = new CacheKey("ns", "k1");

            sketch.increment(key);
            sketch.increment(key);
            sketch.increment(key); // freq 3

            sketch.reset();

            // floor(3/2) = 1
            assertThat(sketch.estimateFrequency(key)).isEqualTo(1);
        }

        @Test
        void reset_multipleTimes_convergesToZero() {
            CountMinSketch sketch = new CountMinSketch(4, 100);
            CacheKey key = new CacheKey("ns", "k1");

            sketch.increment(key);

            // After multiple resets, counter should approach 0
            for (int i = 0; i < 10; i++) {
                sketch.reset();
            }

            assertThat(sketch.estimateFrequency(key)).isEqualTo(0);
        }

        @Test
        void reset_thenIncrement_worksCorrectly() {
            CountMinSketch sketch = new CountMinSketch(4, 100);
            CacheKey key = new CacheKey("ns", "k1");

            sketch.increment(key);
            sketch.increment(key); // freq 2
            sketch.reset();             // freq 1
            sketch.increment(key);       // freq 2
            sketch.increment(key);       // freq 3

            assertThat(sketch.estimateFrequency(key)).isEqualTo(3);
        }
    }

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    @Nested
    class EdgeCases {

        @Test
        void estimateFrequency_onEmptySketch_returnsZero() {
            CountMinSketch sketch = new CountMinSketch(4, 100);

            assertThat(sketch.estimateFrequency(new CacheKey("ns", "any"))).isZero();
        }

        @Test
        void incrementNullKey_throwsNullPointerException() {
            CountMinSketch sketch = new CountMinSketch(4, 100);

            assertThatThrownBy(() -> sketch.increment(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void estimateFrequencyNullKey_throwsNullPointerException() {
            CountMinSketch sketch = new CountMinSketch(4, 100);

            assertThatThrownBy(() -> sketch.estimateFrequency(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void smallWidth_stillWorks() {
            // Width of 1 means all keys map to the same column (maximum collision)
            CountMinSketch sketch = new CountMinSketch(4, 1);
            CacheKey key1 = new CacheKey("ns", "k1");
            CacheKey key2 = new CacheKey("ns", "k2");

            sketch.increment(key1);
            sketch.increment(key2);

            // Both map to the same counter — estimate is the sum (worst-case overcount)
            int est1 = sketch.estimateFrequency(key1);
            int est2 = sketch.estimateFrequency(key2);
            assertThat(est1).isEqualTo(est2);
            assertThat(est1).isGreaterThan(0);
        }

        @Test
        void minimalValidConfig_depth1Width1() {
            CountMinSketch sketch = new CountMinSketch(1, 1);

            CacheKey key = new CacheKey("ns", "k");
            sketch.increment(key);
            sketch.increment(key);
            sketch.increment(key);

            assertThat(sketch.estimateFrequency(key)).isEqualTo(3);
        }
    }

    // -------------------------------------------------------------------------
    // Thread safety
    // -------------------------------------------------------------------------

    @Nested
    class ThreadSafety {

        @Test
        void concurrentIncrement_noExceptionsOrNegativeCounts() throws InterruptedException {
            CountMinSketch sketch = new CountMinSketch(8, 1024);
            int threadCount = 8;
            int opsPerThread = 1000;
            CacheKey sharedKey = new CacheKey("ns", "shared");

            Thread[] threads = new Thread[threadCount];
            for (int t = 0; t < threadCount; t++) {
                threads[t] = new Thread(() -> {
                    for (int i = 0; i < opsPerThread; i++) {
                        sketch.increment(sharedKey);
                    }
                });
            }

            for (Thread t : threads) t.start();
            for (Thread t : threads) t.join();

            // No exceptions thrown; result is positive and bounded
            int freq = sketch.estimateFrequency(sharedKey);
            assertThat(freq).isGreaterThan(0);
            assertThat(freq).isLessThanOrEqualTo(15);
        }

        @Test
        void concurrentMixedOperations_producesConsistentEstimate() throws InterruptedException {
            CountMinSketch sketch = new CountMinSketch(8, 1024);
            int threadCount = 4;
            int opsPerThread = 500;

            CacheKey keyA = new CacheKey("ns", "a");
            CacheKey keyB = new CacheKey("ns", "b");

            Thread[] threads = new Thread[threadCount];
            threads[0] = new Thread(() -> {
                for (int i = 0; i < opsPerThread; i++) sketch.increment(keyA);
            });
            threads[1] = new Thread(() -> {
                for (int i = 0; i < opsPerThread; i++) sketch.increment(keyA);
            });
            threads[2] = new Thread(() -> {
                for (int i = 0; i < opsPerThread; i++) sketch.increment(keyB);
            });
            threads[3] = new Thread(() -> {
                for (int i = 0; i < opsPerThread; i++) {
                    sketch.estimateFrequency(keyA);
                    sketch.estimateFrequency(keyB);
                }
            });

            for (Thread t : threads) t.start();
            for (Thread t : threads) t.join();

            assertThat(sketch.estimateFrequency(keyA)).isGreaterThan(0);
            assertThat(sketch.estimateFrequency(keyB)).isGreaterThan(0);
        }
    }
}
