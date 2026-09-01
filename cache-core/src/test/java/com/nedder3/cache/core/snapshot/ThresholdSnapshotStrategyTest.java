package com.nedder3.cache.core.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link ThresholdSnapshotStrategy}.
 *
 * <p>Validates snapshot trigger logic against:
 * <ul>
 *   <li>Event count threshold (>= threshold triggers)</li>
 *   <li>Time elapsed threshold (> threshold millis triggers)</li>
 *   <li>Combined conditions (either condition satisfies)</li>
 *   <li>State reset on recordSnapshot</li>
 *   <li>Constructor argument validation</li>
 *   <li>Edge cases (negative counters, zero thresholds, overflow safety)</li>
 * </ul>
 */
@DisplayName("ThresholdSnapshotStrategy")
class ThresholdSnapshotStrategyTest {

    private static final long T0 = 1_700_000_000_000L; // fixed baseline timestamp

    private static Clock fixedClock(long millis) {
        return Clock.fixed(Instant.ofEpochMilli(millis), ZoneId.of("UTC"));
    }

    // -------------------------------------------------------------------------
    // Contract: shouldSnapshot — triggers when event count reaches or exceeds threshold
    // -------------------------------------------------------------------------

    @Nested
    class ShouldSnapshotByEventCount {

        @Test
        void triggers_when_event_count_equals_threshold() {
            var strategy = new ThresholdSnapshotStrategy(10, 60_000L, fixedClock(T0));
            assertThat(strategy.shouldSnapshot(10, T0)).isTrue();
        }

        @Test
        void triggers_when_event_count_exceeds_threshold() {
            var strategy = new ThresholdSnapshotStrategy(10, 60_000L, fixedClock(T0));
            assertThat(strategy.shouldSnapshot(15, T0)).isTrue();
        }

        @Test
        void does_not_trigger_when_event_count_is_one_below_threshold() {
            var strategy = new ThresholdSnapshotStrategy(10, 60_000L, fixedClock(T0));
            assertThat(strategy.shouldSnapshot(9, T0)).isFalse();
        }

        @Test
        void does_not_trigger_when_event_count_is_zero() {
            var strategy = new ThresholdSnapshotStrategy(10, 60_000L, fixedClock(T0));
            assertThat(strategy.shouldSnapshot(0, T0)).isFalse();
        }
    }

    // -------------------------------------------------------------------------
    // Contract: shouldSnapshot — does NOT trigger when count < threshold AND time not elapsed
    // -------------------------------------------------------------------------

    @Nested
    class ShouldNotSnapshotWhenConditionsNotMet {

        @Test
        void does_not_trigger_if_count_below_threshold_and_time_not_elapsed() {
            // Time threshold = 60 s; current time = T0 + 30 s  => 30 s elapsed < 60 s threshold
            long now = T0 + 30_000L;
            var strategy = new ThresholdSnapshotStrategy(5, 60_000L, fixedClock(now));
            assertThat(strategy.shouldSnapshot(3, T0)).isFalse();
        }

        @Test
        void does_not_trigger_if_count_is_zero_and_time_not_elapsed() {
            long now = T0 + 60_000L;
            var strategy = new ThresholdSnapshotStrategy(10, 120_000L, fixedClock(now));
            assertThat(strategy.shouldSnapshot(0, T0)).isFalse();
        }
    }

    // -------------------------------------------------------------------------
    // Contract: shouldSnapshot — triggers when time threshold is exceeded (count may be low)
    // -------------------------------------------------------------------------

    @Nested
    class ShouldSnapshotByTimeThreshold {

        @Test
        void triggers_when_time_elapsed_exceeds_threshold_even_with_zero_count() {
            long now = T0 + 90_000L;
            var strategy = new ThresholdSnapshotStrategy(100, 60_000L, fixedClock(now));
            // 90 s elapsed > 60 s threshold, but only 2 events (well below 100)
            assertThat(strategy.shouldSnapshot(2, T0)).isTrue();
        }

        @Test
        void triggers_when_time_elapsed_exceeds_threshold_with_low_count() {
            long now = T0 + 45_000L;
            var strategy = new ThresholdSnapshotStrategy(1_000, 30_000L, fixedClock(now));
            // 45 s elapsed > 30 s threshold, 50 events (well below 1000)
            assertThat(strategy.shouldSnapshot(50, T0)).isTrue();
        }

        @Test
        void does_not_trigger_if_time_elapsed_is_exactly_at_threshold() {
            long now = T0 + 60_000L;
            var strategy = new ThresholdSnapshotStrategy(100, 60_000L, fixedClock(now));
            // Exactly 60 s elapsed — elapsed > threshold is false
            assertThat(strategy.shouldSnapshot(0, T0)).isFalse();
        }
    }

    // -------------------------------------------------------------------------
    // Contract: recordSnapshot resets counters and advances the last-snapshot timestamp
    // -------------------------------------------------------------------------

    @Nested
    class RecordSnapshotResetsState {

        @Test
        void after_recordSnapshot_no_trigger_even_if_previously_at_threshold() {
            var strategy = new ThresholdSnapshotStrategy(5, 60_000L, fixedClock(T0 + 5_000L));

            // Accumulate events to the threshold — snapshot is due
            assertThat(strategy.shouldSnapshot(5, T0)).isTrue();

            // Record snapshot at time T1
            long T1 = T0 + 5_000L;
            strategy.recordSnapshot(T1);

            // Now only 2 events have accumulated since T1, and no time has passed:
            // should NOT trigger a new snapshot
            assertThat(strategy.shouldSnapshot(2, T1)).isFalse();
        }

        @Test
        void after_recordSnapshot_time_reset_prevents_early_trigger() {
            long T1 = T0 + 10_000L;
            long T2 = T1 + 5_000L;
            var strategy = new ThresholdSnapshotStrategy(100, 30_000L, fixedClock(T2));

            // Record first snapshot
            strategy.recordSnapshot(T1);

            // Only 5 s have elapsed since T1 — far below 30 s threshold
            assertThat(strategy.shouldSnapshot(99, T1)).isFalse();
        }

        @Test
        void recordSnapshot_can_be_called_multiple_times() {
            long T3 = T0 + 20_000L;
            var strategy = new ThresholdSnapshotStrategy(5, 60_000L, fixedClock(T3));

            strategy.recordSnapshot(T0);
            strategy.recordSnapshot(T0 + 10_000L);
            strategy.recordSnapshot(T0 + 20_000L);

            // After third snapshot, zero events have accumulated — no trigger
            assertThat(strategy.shouldSnapshot(0, T0 + 20_000L)).isFalse();
        }
    }

    // -------------------------------------------------------------------------
    // Contract: constructor rejects non-positive thresholds
    // -------------------------------------------------------------------------

    @Nested
    class ConstructorValidation {

        @Test
        void rejects_zero_event_threshold() {
            assertThatThrownBy(() -> new ThresholdSnapshotStrategy(0, 60_000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventThreshold");
        }

        @Test
        void rejects_negative_event_threshold() {
            assertThatThrownBy(() -> new ThresholdSnapshotStrategy(-1, 60_000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventThreshold");
        }

        @Test
        void rejects_zero_time_threshold() {
            assertThatThrownBy(() -> new ThresholdSnapshotStrategy(10, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeThresholdMillis");
        }

        @Test
        void rejects_negative_time_threshold() {
            assertThatThrownBy(() -> new ThresholdSnapshotStrategy(10, -500L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeThresholdMillis");
        }

        @Test
        void accepts_positive_thresholds() {
            var strategy = new ThresholdSnapshotStrategy(1, 1L);
            assertThat(strategy).isNotNull();
        }
    }

    // -------------------------------------------------------------------------
    // Edge cases and robustness
    // -------------------------------------------------------------------------

    @Nested
    class EdgeCases {

        @Test
        void negative_event_count_is_treated_as_below_threshold() {
            var strategy = new ThresholdSnapshotStrategy(5, 60_000L, fixedClock(T0));
            assertThat(strategy.shouldSnapshot(-1, T0)).isFalse();
        }

        @Test
        void zero_last_snapshot_timestamp_indicates_no_prior_snapshot_and_triggers_if_events_present() {
            var strategy = new ThresholdSnapshotStrategy(5, 60_000L, fixedClock(T0));
            assertThat(strategy.shouldSnapshot(5, 0L)).isTrue();
        }

        @Test
        void large_event_count_does_not_overflow() {
            var strategy = new ThresholdSnapshotStrategy(1_000, 60_000L, fixedClock(T0));
            assertThat(strategy.shouldSnapshot(Integer.MAX_VALUE, T0)).isTrue();
        }
    }
}
