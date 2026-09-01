package com.nedder3.cache.core.clock;

import java.util.HashMap;
import java.util.Map;

/**
 * Immutable vector clock for causal ordering in distributed systems.
 * Each entry maps a node identifier to its logical counter.
 */
public record VectorClock(Map<String, Long> counters) {

    public VectorClock {
        counters = Map.copyOf(counters);
    }

    public VectorClock increment(String nodeId) {
        var updated = new HashMap<>(counters);
        updated.merge(nodeId, 1L, Long::sum);
        return new VectorClock(updated);
    }

    public VectorClock merge(VectorClock other) {
        var merged = new HashMap<>(counters);
        other.counters.forEach((node, count) ->
            merged.merge(node, count, Math::max));
        return new VectorClock(merged);
    }

    public Causality causalityCheck(VectorClock other) {
        boolean anyLess = false;
        boolean anyGreater = false;
        var allNodes = new java.util.LinkedHashSet<>(counters.keySet());
        allNodes.addAll(other.counters.keySet());

        for (var node : allNodes) {
            long thisCount = counters.getOrDefault(node, 0L);
            long otherCount = other.counters.getOrDefault(node, 0L);
            if (thisCount < otherCount) anyLess = true;
            if (thisCount > otherCount) anyGreater = true;
        }

        if (!anyLess && !anyGreater) return Causality.EQUAL;
        if (anyLess && anyGreater) return Causality.CONCURRENT;
        if (anyLess) return Causality.BEFORE;
        return Causality.AFTER;
    }
}
