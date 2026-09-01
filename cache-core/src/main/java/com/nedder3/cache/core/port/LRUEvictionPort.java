package com.nedder3.cache.core.port;

import com.nedder3.cache.core.model.CacheKey;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * LRU eviction strategy implementation of EvictionPort.
 */
public class LRUEvictionPort implements EvictionPort {

    private final Set<EvictionEventListener> listeners = new CopyOnWriteArraySet<>();
    private final int capacity;
    private final LinkedHashMap<CacheKey, Boolean> accessOrder;

    public LRUEvictionPort(int capacity) {
        this.capacity = capacity;
        // accessOrder=true enables access-order iteration
        this.accessOrder = new LinkedHashMap<>(capacity, 0.75f, true);
    }

    @Override
    public synchronized void onAccess(CacheKey key) {
        if (accessOrder.containsKey(key)) {
            accessOrder.get(key); // Re-orders entry to most-recently-used position
        }
    }

    @Override
    public synchronized void onInsert(CacheKey key) {
        accessOrder.put(key, Boolean.TRUE);
    }

    @Override
    public synchronized Optional<CacheKey> evict() {
        if (accessOrder.size() > capacity) {
            var iterator = accessOrder.keySet().iterator();
            if (iterator.hasNext()) {
                CacheKey eldest = iterator.next();
                iterator.remove();
                notifyListeners(new EvictionEvent("EVICT", eldest));
                return Optional.of(eldest);
            }
        }
        return Optional.empty();
    }

    @Override
    public synchronized int size() {
        return accessOrder.size();
    }

    @Override
    public synchronized void clear() {
        accessOrder.clear();
    }

    @Override
    public void addListener(EvictionEventListener listener) {
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
