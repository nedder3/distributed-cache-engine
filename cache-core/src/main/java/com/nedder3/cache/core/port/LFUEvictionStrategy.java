package com.nedder3.cache.core.port;

import com.nedder3.cache.core.model.CacheKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * LFU (Least Frequently Used) eviction strategy with LRU tie-breaking for equal frequencies.
 * Implements {@link EvictionPort}.
 *
 * <p>Frequency model:
 * <ul>
 *   <li>onInsert(key): registers key with initial access frequency = 1. If key already exists, increments frequency and moves to MRU.
 *       Note: when key is evicted, it must be the one with the lowest frequency among tracked keys. Keys are only candidate for eviction
 *       if total entries exceed capacity. If a new key is inserted, its frequency starts at 1, but existing keys with higher frequency are preserved.</li>
 *   <li>onAccess(key): increments access frequency (+1) and marks key as MRU.</li>
 *   <li>evict(): when size exceeds capacity, evicts the entry with the strictly lowest frequency, breaking ties using LRU order.</li>
 * </ul>
 */
public final class LFUEvictionStrategy implements EvictionPort {

    private final int capacity;
    private final Map<CacheKey, Integer> counts = new HashMap<>();
    private final LinkedHashSet<CacheKey> order = new LinkedHashSet<>();
    private final List<EvictionEventListener> listeners = new CopyOnWriteArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();

    public LFUEvictionStrategy(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
    }

    @Override
    public void onAccess(CacheKey key) {
        Objects.requireNonNull(key, "key must not be null");
        lock.lock();
        try {
            if (counts.containsKey(key)) {
                counts.put(key, counts.get(key) + 1);
                order.remove(key);
                order.add(key);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void onInsert(CacheKey key) {
        Objects.requireNonNull(key, "key must not be null");
        lock.lock();
        try {
            if (counts.containsKey(key)) {
                counts.put(key, counts.get(key) + 1);
                order.remove(key);
                order.add(key);
            } else {
                counts.put(key, 1);
                order.remove(key);
                order.add(key);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<CacheKey> evict() {
        lock.lock();
        try {
            if (counts.size() <= capacity) {
                return Optional.empty();
            }
            CacheKey victim = null;
            int minCount = Integer.MAX_VALUE;

            for (CacheKey candidate : order) {
                int count = counts.get(candidate);
                if (count < minCount) {
                    minCount = count;
                    victim = candidate;
                }
            }

            if (victim != null) {
                counts.remove(victim);
                order.remove(victim);
                notifyListeners(new EvictionEvent("EVICT", victim));
                return Optional.of(victim);
            }
            return Optional.empty();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int size() {
        lock.lock();
        try {
            return counts.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void clear() {
        lock.lock();
        try {
            counts.clear();
            order.clear();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void addListener(EvictionEventListener listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        listeners.add(listener);
    }

    @Override
    public void removeListener(EvictionEventListener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners(EvictionEvent event) {
        for (EvictionEventListener listener : listeners) {
            listener.onEvent(event);
        }
    }
}
