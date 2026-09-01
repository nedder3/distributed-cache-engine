package com.nedder3.cache.core.engine;

import com.nedder3.cache.core.clock.VectorClock;
import com.nedder3.cache.core.event.DeleteEvent;
import com.nedder3.cache.core.event.EvictEvent;
import com.nedder3.cache.core.event.EventBus;
import com.nedder3.cache.core.event.PutEvent;
import com.nedder3.cache.core.model.CacheKey;
import com.nedder3.cache.core.model.EvictionReason;
import com.nedder3.cache.core.port.ReplicationPort;
import com.nedder3.cache.core.port.StatsPort;
import com.nedder3.cache.core.port.StoragePort;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CQRS Command Handler for cache mutation operations.
 * Handles put, delete, and evict operations, isolates failures from storage/replication,
 * emits domain events to EventBus, and updates statistics.
 *
 * @param <V> value type
 */
public class CommandHandler<V> {

    private final StoragePort<V> storage;
    private final ReplicationPort replication;
    private final EventBus eventBus;
    private final StatsPort stats;
    private final AtomicLong clockSequence = new AtomicLong(0);

    public CommandHandler(StoragePort<V> storage, ReplicationPort replication, EventBus eventBus) {
        this(storage, replication, eventBus, null);
    }

    public CommandHandler(StoragePort<V> storage, ReplicationPort replication, EventBus eventBus, StatsPort stats) {
        this.storage = Objects.requireNonNull(storage, "storage cannot be null");
        this.replication = Objects.requireNonNull(replication, "replication cannot be null");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus cannot be null");
        this.stats = stats;
    }

    public void put(CacheKey cacheKey, V value) {
        put(cacheKey, value, OptionalLong.empty());
    }

    public void put(CacheKey cacheKey, V value, OptionalLong expiresAt) {
        Objects.requireNonNull(cacheKey, "cacheKey cannot be null");
        Objects.requireNonNull(value, "value cannot be null");
        Objects.requireNonNull(expiresAt, "expiresAt cannot be null");

        long now = System.currentTimeMillis();
        long seq = clockSequence.incrementAndGet();
        VectorClock clock = new VectorClock(Map.of("local-node", seq));
        byte[] payload = value.toString().getBytes(StandardCharsets.UTF_8);

        // 1. Write to storage (failure isolated)
        try {
            storage.write(cacheKey, value, expiresAt);
        } catch (Exception ignored) {
            // Storage failure isolated — event emission proceeds
        }

        // 2. Publish event to EventBus
        PutEvent event = new PutEvent(cacheKey, payload, clock, now);
        eventBus.publish(event);

        // 3. Notify replication (failure isolated)
        try {
            replication.notifyPut(cacheKey);
        } catch (Exception ignored) {
            // Replication failure isolated
        }

        // 4. Record stats
        recordPut();
    }

    public void put(String namespace, String key, V value) {
        Objects.requireNonNull(namespace, "namespace cannot be null");
        Objects.requireNonNull(key, "key cannot be null");
        put(new CacheKey(namespace, key), value, OptionalLong.empty());
    }

    public void put(String namespace, String key, V value, long ttlMillis) {
        Objects.requireNonNull(namespace, "namespace cannot be null");
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(value, "value cannot be null");
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ttlMillis must be positive: " + ttlMillis);
        }

        long now = System.currentTimeMillis();
        put(new CacheKey(namespace, key), value, OptionalLong.of(now + ttlMillis));
    }

    public boolean delete(CacheKey cacheKey) {
        Objects.requireNonNull(cacheKey, "cacheKey cannot be null");

        long now = System.currentTimeMillis();
        long seq = clockSequence.incrementAndGet();
        VectorClock clock = new VectorClock(Map.of("local-node", seq));

        boolean existed = false;
        try {
            existed = storage.delete(cacheKey);
        } catch (Exception ignored) {
            // Storage failure isolated
        }

        // 2. Publish event
        DeleteEvent event = new DeleteEvent(cacheKey, clock, now);
        eventBus.publish(event);

        // 3. Notify replication
        try {
            replication.notifyDelete(cacheKey);
        } catch (Exception ignored) {
            // Replication failure isolated
        }

        // 4. Record stats
        recordDelete();

        return existed;
    }

    public boolean delete(String namespace, String key) {
        Objects.requireNonNull(namespace, "namespace cannot be null");
        Objects.requireNonNull(key, "key cannot be null");
        return delete(new CacheKey(namespace, key));
    }

    public void evict(CacheKey key, EvictionReason reason) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(reason, "reason cannot be null");

        long now = System.currentTimeMillis();

        try {
            storage.delete(key);
        } catch (Exception ignored) {
            // Storage failure isolated
        }

        EvictEvent event = new EvictEvent(key, reason, now);
        eventBus.publish(event);

        recordEviction();
    }

    private void recordPut() {
        if (stats != null) {
            stats.recordPut();
        }
    }

    private void recordDelete() {
        if (stats != null) {
            stats.recordDelete();
        }
    }

    private void recordEviction() {
        if (stats != null) {
            stats.recordEviction();
        }
    }
}
