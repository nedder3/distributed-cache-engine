package com.nedder3.cache.core.port;

import com.nedder3.cache.core.model.CacheKey;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * W-TinyLFU eviction strategy — Window LRU + SLRU main cache (Protected + Probation)
 * + CountMinSketch admission policy.
 * Implements {@link EvictionPort}.
 */
public class WTinyLFUEvictionStrategy implements EvictionPort {

    private final int capacity;
    private final int windowCapacity;
    private final int protectedCapacity;
    private final int probationCapacity;

    private final CountMinSketch sketch;

    // Segments maintain LRU order (eldest is first via LinkedHashSet iterator)
    private final LinkedHashSet<CacheKey> window;
    private final LinkedHashSet<CacheKey> probation;
    private final LinkedHashSet<CacheKey> protectedSegment;

    private final List<EvictionEventListener> listeners = new CopyOnWriteArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();

    public WTinyLFUEvictionStrategy(int capacity) {
        if (capacity < 2) {
            throw new IllegalArgumentException("Capacity must be at least 2: " + capacity);
        }
        this.capacity = capacity;

        // Window ~ 1% of capacity (min 1)
        this.windowCapacity = Math.max(1, (int) Math.floor(capacity * 0.01));

        int mainCapacity = Math.max(1, capacity - this.windowCapacity);
        // Protected ~ 80% of main (min 1)
        this.protectedCapacity = Math.max(1, (int) Math.floor(mainCapacity * 0.80));
        // Probation ~ remainder of main (min 1)
        this.probationCapacity = Math.max(1, mainCapacity - this.protectedCapacity);

        this.sketch = new CountMinSketch(4, Math.max(64, capacity * 2));

        this.window = new LinkedHashSet<>();
        this.probation = new LinkedHashSet<>();
        this.protectedSegment = new LinkedHashSet<>();
    }

    @Override
    public void onAccess(CacheKey key) {
        Objects.requireNonNull(key, "key cannot be null");
        lock.lock();
        try {
            if (!contains(key)) {
                return;
            }

            sketch.increment(key);

            if (window.remove(key)) {
                window.add(key); // MRU in window
            } else if (probation.remove(key)) {
                // Promote probation to protected
                protectedSegment.add(key);
                // If protected overflowed, demote protected LRU to probation
                if (protectedSegment.size() > protectedCapacity) {
                    CacheKey demoted = popFirst(protectedSegment);
                    if (demoted != null) {
                        probation.add(demoted);
                    }
                }
            } else if (protectedSegment.remove(key)) {
                protectedSegment.add(key); // MRU in protected
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void onInsert(CacheKey key) {
        Objects.requireNonNull(key, "key cannot be null");
        lock.lock();
        try {
            sketch.increment(key);

            if (contains(key)) {
                onAccess(key);
                return;
            }

            // Insert into window as MRU
            window.add(key);

            // Flow items from window to probation if window exceeds capacity
            while (window.size() > windowCapacity) {
                CacheKey candidate = popFirst(window);
                if (candidate != null) {
                    probation.add(candidate);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<CacheKey> evict() {
        lock.lock();
        try {
            int currentSize = size();
            if (currentSize < capacity) {
                return Optional.empty();
            }

            // If currentSize exceeds capacity or is at capacity, we evict
            CacheKey victim = null;

            // When there is an entry in window and probation, TinyLFU admission check
            if (!window.isEmpty() && !probation.isEmpty()) {
                CacheKey windowCandidate = first(window);
                CacheKey probationVictim = first(probation);

                int windowFreq = sketch.estimateFrequency(windowCandidate);
                int probationFreq = sketch.estimateFrequency(probationVictim);

                if (windowFreq > probationFreq) {
                    // Probation victim is evicted, window candidate admitted to probation
                    victim = popFirst(probation);
                    popFirst(window);
                    probation.add(windowCandidate);
                } else {
                    // Window candidate is rejected
                    victim = popFirst(window);
                }
            } else if (!probation.isEmpty()) {
                victim = popFirst(probation);
            } else if (!window.isEmpty()) {
                victim = popFirst(window);
            } else if (!protectedSegment.isEmpty()) {
                victim = popFirst(protectedSegment);
            }

            if (victim != null) {
                notifyEviction(victim);
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
            return window.size() + probation.size() + protectedSegment.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void clear() {
        lock.lock();
        try {
            window.clear();
            probation.clear();
            protectedSegment.clear();
            sketch.reset();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void addListener(EvictionEventListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public void removeListener(EvictionEventListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    private boolean contains(CacheKey key) {
        return window.contains(key) || probation.contains(key) || protectedSegment.contains(key);
    }

    private CacheKey first(LinkedHashSet<CacheKey> set) {
        Iterator<CacheKey> it = set.iterator();
        return it.hasNext() ? it.next() : null;
    }

    private CacheKey popFirst(LinkedHashSet<CacheKey> set) {
        Iterator<CacheKey> it = set.iterator();
        if (it.hasNext()) {
            CacheKey item = it.next();
            it.remove();
            return item;
        }
        return null;
    }

    private void notifyEviction(CacheKey key) {
        EvictionEvent event = new EvictionEvent("EVICT", key);
        for (EvictionEventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Exception ignored) {
            }
        }
    }
}
