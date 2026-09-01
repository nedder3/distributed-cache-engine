package com.nedder3.cache.core.clock;

/**
 * Causality relationship between two vector clocks.
 */
public enum Causality {
    /** This clock happened before the other. */
    BEFORE,
    /** This clock happened after the other. */
    AFTER,
    /** Neither happened before the other — concurrent. */
    CONCURRENT,
    /** Both clocks are identical. */
    EQUAL
}
