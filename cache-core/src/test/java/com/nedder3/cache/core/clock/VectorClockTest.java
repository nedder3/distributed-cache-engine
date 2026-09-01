package com.nedder3.cache.core.clock;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VectorClockTest {

    @Test
    void emptyClockHasNoCounters() {
        var clock = new VectorClock(Map.of());
        assertThat(clock.counters()).isEmpty();
    }

    @Test
    void incrementCreatesNewClockWithUpdatedNode() {
        var clock = new VectorClock(Map.of());
        var incremented = clock.increment("node-1");
        assertThat(incremented.counters()).containsEntry("node-1", 1L);
    }

    @Test
    void incrementDoesNotMutateOriginal() {
        var clock = new VectorClock(Map.of());
        clock.increment("node-1");
        assertThat(clock.counters()).isEmpty();
    }

    @Test
    void incrementTwiceOnSameNode() {
        var clock = new VectorClock(Map.of());
        var after = clock.increment("node-1").increment("node-1");
        assertThat(after.counters()).containsEntry("node-1", 2L);
    }

    @Test
    void mergeTakesElementWiseMax() {
        var a = new VectorClock(Map.of("node-1", 3L, "node-2", 1L));
        var b = new VectorClock(Map.of("node-1", 1L, "node-2", 4L));
        var merged = a.merge(b);
        assertThat(merged.counters()).containsEntry("node-1", 3L);
        assertThat(merged.counters()).containsEntry("node-2", 4L);
    }

    @Test
    void mergeHandlesDisjointNodes() {
        var a = new VectorClock(Map.of("node-1", 2L));
        var b = new VectorClock(Map.of("node-2", 5L));
        var merged = a.merge(b);
        assertThat(merged.counters()).containsEntry("node-1", 2L);
        assertThat(merged.counters()).containsEntry("node-2", 5L);
    }

    @Test
    void causalityCheck_equal_clocks() {
        var a = new VectorClock(Map.of("node-1", 1L, "node-2", 2L));
        var b = new VectorClock(Map.of("node-1", 1L, "node-2", 2L));
        assertThat(a.causalityCheck(b)).isEqualTo(Causality.EQUAL);
    }

    @Test
    void causalityCheck_before_when_all_counters_less_or_equal() {
        var a = new VectorClock(Map.of("node-1", 1L));
        var b = new VectorClock(Map.of("node-1", 2L));
        assertThat(a.causalityCheck(b)).isEqualTo(Causality.BEFORE);
    }

    @Test
    void causalityCheck_after_when_all_counters_greater_or_equal() {
        var a = new VectorClock(Map.of("node-1", 3L));
        var b = new VectorClock(Map.of("node-1", 1L));
        assertThat(a.causalityCheck(b)).isEqualTo(Causality.AFTER);
    }

    @Test
    void causalityCheck_concurrent_mixed_counters() {
        var a = new VectorClock(Map.of("node-1", 3L, "node-2", 1L));
        var b = new VectorClock(Map.of("node-1", 1L, "node-2", 4L));
        assertThat(a.causalityCheck(b)).isEqualTo(Causality.CONCURRENT);
    }

    @Test
    void causalityCheck_three_node_scenario() {
        // node-1: a=5,b=3,c=2  vs  node-1: a=4,b=4,c=1
        var a = new VectorClock(Map.of("node-1", 5L, "node-2", 3L, "node-3", 2L));
        var b = new VectorClock(Map.of("node-1", 4L, "node-2", 4L, "node-3", 1L));
        assertThat(a.causalityCheck(b)).isEqualTo(Causality.CONCURRENT);
    }

    @Test
    void causalityCheck_with_missing_nodes_treated_as_zero() {
        var a = new VectorClock(Map.of("node-1", 1L));
        var b = new VectorClock(Map.of("node-1", 2L, "node-2", 1L));
        assertThat(a.causalityCheck(b)).isEqualTo(Causality.BEFORE);
    }
}
