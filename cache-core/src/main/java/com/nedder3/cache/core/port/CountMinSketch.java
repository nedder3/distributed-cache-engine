package com.nedder3.cache.core.port;

import com.nedder3.cache.core.model.CacheKey;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * CountMinSketch — probabilistic frequency sketch using d hash functions and a
 * w-wide table of 4-bit counters (max value 15).
 */
public class CountMinSketch {

    private final int depth;
    private final int width;
    private final byte[][] table;
    private final ReentrantLock lock = new ReentrantLock();

    // Seeds for hash functions
    private final int[] seeds;

    public CountMinSketch(int depth, int width) {
        if (depth <= 0) {
            throw new IllegalArgumentException("Depth must be positive: " + depth);
        }
        if (width <= 0) {
            throw new IllegalArgumentException("Width must be positive: " + width);
        }
        this.depth = depth;
        this.width = width;
        this.table = new byte[depth][width];
        this.seeds = new int[depth];
        for (int i = 0; i < depth; i++) {
            this.seeds[i] = (i + 1) * 0x9e3779b9;
        }
    }

    public void increment(CacheKey key) {
        Objects.requireNonNull(key, "key cannot be null");
        lock.lock();
        try {
            for (int d = 0; d < depth; d++) {
                int col = hash(key, d);
                if (table[d][col] < 15) {
                    table[d][col]++;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public int estimateFrequency(CacheKey key) {
        Objects.requireNonNull(key, "key cannot be null");
        lock.lock();
        try {
            int min = Integer.MAX_VALUE;
            for (int d = 0; d < depth; d++) {
                int col = hash(key, d);
                int count = table[d][col] & 0xFF;
                if (count < min) {
                    min = count;
                }
            }
            return min == Integer.MAX_VALUE ? 0 : min;
        } finally {
            lock.unlock();
        }
    }

    public void reset() {
        lock.lock();
        try {
            for (int d = 0; d < depth; d++) {
                for (int w = 0; w < width; w++) {
                    table[d][w] = (byte) ((table[d][w] & 0xFF) >>> 1);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private int hash(CacheKey key, int index) {
        int h = key.hashCode() ^ seeds[index];
        h ^= (h >>> 16);
        h *= 0x85ebca6b;
        h ^= (h >>> 13);
        h *= 0xc2b2ae35;
        h ^= (h >>> 16);
        return Math.floorMod(h, width);
    }
}
