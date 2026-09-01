package com.nedder3.cache.core.store;

import com.nedder3.cache.core.event.CacheEvent;
import com.nedder3.cache.core.model.CacheKey;
import com.nedder3.cache.core.port.StoragePort;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe, append-only in-memory event store backed by a {@link ConcurrentLinkedQueue}.
 *
 * <p>Read operations return unmodifiable snapshots. Listeners are notified
 * on every {@link #append} with a {@link StorageEvent} carrying the appended event.
 * Snapshots are stored via {@link AtomicReference} and are replaced atomically.</p>
 *
 * <p>This implementation is intended for single-node development, testing,
 * and prototyping. Production deployments should use a persistent store.</p>
 */
public final class InMemoryEventStore implements StoragePort<Object> {

    private final Queue<CacheEvent> events = new ConcurrentLinkedQueue<>();
    private final AtomicReference<Map<CacheKey, byte[]>> latestSnapshot =
            new AtomicReference<>(Map.of());
    private final CopyOnWriteArrayList<StorageEventListener> listeners =
            new CopyOnWriteArrayList<>();

    @Override
    public void append(CacheEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        events.add(event);
        var storageEvent = new StorageEvent("APPEND", event);
        for (StorageEventListener listener : listeners) {
            listener.onEvent(storageEvent);
        }
    }

    @Override
    public List<CacheEvent> readAll() {
        return List.copyOf(events);
    }

    @Override
    public List<CacheEvent> readAfter(long timestamp) {
        List<CacheEvent> result = new ArrayList<>();
        for (CacheEvent event : events) {
            if (event.timestamp() > timestamp) {
                result.add(event);
            }
        }
        return List.copyOf(result);
    }

    @Override
    public int compactBefore(long cutoffTimestamp) {
        int removed = 0;
        var iterator = events.iterator();
        while (iterator.hasNext()) {
            CacheEvent event = iterator.next();
            if (event.timestamp() <= cutoffTimestamp) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    @Override
    public void createSnapshot(Map<CacheKey, byte[]> state) {
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        latestSnapshot.set(Map.copyOf(state));
    }

    @Override
    public Map<CacheKey, byte[]> loadLatestSnapshot() {
        return latestSnapshot.get();
    }

    @Override
    public void addListener(StorageEventListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        listeners.add(listener);
    }

    @Override
    public void removeListener(StorageEventListener listener) {
        listeners.remove(listener);
    }
}
