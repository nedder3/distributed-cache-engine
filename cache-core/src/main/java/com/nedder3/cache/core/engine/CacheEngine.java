package com.nedder3.cache.core.engine;

import com.nedder3.cache.core.event.CacheEvent;
import com.nedder3.cache.core.event.DeleteEvent;
import com.nedder3.cache.core.event.EvictEvent;
import com.nedder3.cache.core.event.EventBus;
import com.nedder3.cache.core.event.PutEvent;
import com.nedder3.cache.core.model.CacheEntry;
import com.nedder3.cache.core.model.CacheKey;
import com.nedder3.cache.core.model.CacheStats;
import com.nedder3.cache.core.model.EvictionReason;
import com.nedder3.cache.core.port.EvictionPort;
import com.nedder3.cache.core.port.InboundPort;
import com.nedder3.cache.core.port.ReplicationPort;
import com.nedder3.cache.core.port.StoragePort;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Primary Inbound Port implementing full cache lifecycle, CQRS delegation,
 * eviction integration, event sourcing dispatch, and replication.
 *
 * @param <V> value type
 */
public class CacheEngine<V> implements InboundPort<V> {

    private final StoragePort<V> storage;
    private final ReplicationPort replication;
    private final EventBus bus;
    private final EvictionPort eviction;
    private final CommandHandler<V> commandHandler;
    private final QueryHandler<V> queryHandler;
    private final StatsCollector stats;
    private final CopyOnWriteArrayList<CacheEventListener> listeners = new CopyOnWriteArrayList<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public CacheEngine(
            StoragePort<V> storage,
            ReplicationPort replication,
            EventBus bus,
            EvictionPort eviction) {
        this(storage, replication, bus, eviction, new StatsCollector());
    }

    public CacheEngine(
            StoragePort<V> storage,
            ReplicationPort replication,
            EventBus bus,
            EvictionPort eviction,
            StatsCollector stats) {
        this.storage = Objects.requireNonNull(storage, "storage cannot be null");
        this.replication = Objects.requireNonNull(replication, "replication cannot be null");
        this.bus = Objects.requireNonNull(bus, "bus cannot be null");
        this.eviction = Objects.requireNonNull(eviction, "eviction cannot be null");
        this.stats = Objects.requireNonNull(stats, "stats cannot be null");

        this.commandHandler = new CommandHandler<>(storage, replication, bus, stats);
        this.queryHandler = new QueryHandler<>(storage, stats);

        // Register internal event listener to forward bus events to inbound listeners
        this.bus.subscribe(CacheEvent.class, event -> {
            for (CacheEventListener listener : listeners) {
                try {
                    listener.onEvent(event);
                } catch (Throwable ignored) {
                    // Isolate listener failures
                }
            }
        });
    }

    @Override
    public Optional<V> get(CacheKey key) {
        Objects.requireNonNull(key, "key cannot be null");
        lock.readLock().lock();
        try {
            Optional<V> result = queryHandler.get(key);
            result.ifPresent(v -> eviction.onAccess(key));
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    public Optional<V> get(String namespace, String key) {
        Objects.requireNonNull(namespace, "namespace cannot be null");
        Objects.requireNonNull(key, "key cannot be null");
        return get(new CacheKey(namespace, key));
    }

    public Optional<CacheEntry<V>> getEntry(CacheKey key) {
        Objects.requireNonNull(key, "key cannot be null");
        lock.readLock().lock();
        try {
            eviction.onAccess(key);
            return queryHandler.getEntry(key);
        } finally {
            lock.readLock().unlock();
        }
    }

    public Optional<CacheEntry<V>> getEntry(String namespace, String key) {
        Objects.requireNonNull(namespace, "namespace cannot be null");
        Objects.requireNonNull(key, "key cannot be null");
        return getEntry(new CacheKey(namespace, key));
    }

    @Override
    public void put(CacheKey key, V value) {
        put(key, value, OptionalLong.empty());
    }

    public void put(String namespace, String key, V value) {
        Objects.requireNonNull(namespace, "namespace cannot be null");
        Objects.requireNonNull(key, "key cannot be null");
        put(new CacheKey(namespace, key), value, OptionalLong.empty());
    }

    @Override
    public void put(CacheKey key, V value, long ttlMillis) {
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ttlMillis must be positive: " + ttlMillis);
        }
        put(key, value, OptionalLong.of(System.currentTimeMillis() + ttlMillis));
    }

    public void put(String namespace, String key, V value, long ttlMillis) {
        Objects.requireNonNull(namespace, "namespace cannot be null");
        Objects.requireNonNull(key, "key cannot be null");
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ttlMillis must be positive: " + ttlMillis);
        }
        put(new CacheKey(namespace, key), value, ttlMillis);
    }

    public void put(CacheKey key, V value, OptionalLong expiresAt) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(value, "value cannot be null");
        Objects.requireNonNull(expiresAt, "expiresAt cannot be null");

        lock.writeLock().lock();
        try {
            eviction.onInsert(key);

            Optional<CacheKey> victim = eviction.evict();
            victim.ifPresent(vKey -> {
                storage.delete(vKey);
                stats.recordEviction();
                bus.publish(new EvictEvent(vKey, EvictionReason.CAPACITY, System.currentTimeMillis()));
            });

            commandHandler.put(key, value, expiresAt);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean delete(CacheKey key) {
        Objects.requireNonNull(key, "key cannot be null");
        lock.writeLock().lock();
        try {
            eviction.onDelete(key);
            return commandHandler.delete(key);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean delete(String namespace, String key) {
        Objects.requireNonNull(namespace, "namespace cannot be null");
        Objects.requireNonNull(key, "key cannot be null");
        return delete(new CacheKey(namespace, key));
    }

    @Override
    public int size() {
        lock.readLock().lock();
        try {
            return storage.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public CacheStats stats() {
        return stats.snapshot();
    }

    @Override
    public void addListener(CacheEventListener listener) {
        Objects.requireNonNull(listener, "listener cannot be null");
        listeners.add(listener);
    }

    @Override
    public void removeListener(CacheEventListener listener) {
        Objects.requireNonNull(listener, "listener cannot be null");
        listeners.remove(listener);
    }
}