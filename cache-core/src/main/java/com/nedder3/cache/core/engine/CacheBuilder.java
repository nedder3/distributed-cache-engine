package com.nedder3.cache.core.engine;

import com.nedder3.cache.core.event.EventBus;
import com.nedder3.cache.core.model.CacheKey;
import com.nedder3.cache.core.port.EvictionPort;
import com.nedder3.cache.core.port.LRUEvictionPort;
import com.nedder3.cache.core.port.ReplicationPort;
import com.nedder3.cache.core.port.StoragePort;
import com.nedder3.cache.core.store.ConcurrentMapStorage;

import java.util.Objects;

/**
 * Fluent builder for configuring and assembling {@link CacheEngine} instances.
 *
 * @param <V> value type
 */
public final class CacheBuilder<V> {

    private static final int DEFAULT_CAPACITY = 100;

    private Integer capacity;
    private EvictionPort evictionStrategy;
    private EventBus eventBus;
    private ReplicationPort replicationPort;
    private StoragePort<V> storagePort;
    private Class<V> valueType;
    private boolean built = false;

    private CacheBuilder() {
    }

    public static <V> CacheBuilder<V> builder() {
        return new CacheBuilder<>();
    }

    public static <V> CacheBuilder<V> withDefaults() {
        CacheBuilder<V> b = new CacheBuilder<>();
        b.capacity = DEFAULT_CAPACITY;
        b.eventBus = new EventBus();
        return b;
    }

    public static <V> CacheBuilder<V> async() {
        CacheBuilder<V> b = new CacheBuilder<>();
        b.eventBus = EventBus.async();
        return b;
    }

    public CacheBuilder<V> capacity(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        return this;
    }

    public CacheBuilder<V> evictionStrategy(EvictionPort evictionStrategy) {
        this.evictionStrategy = Objects.requireNonNull(evictionStrategy, "evictionStrategy cannot be null");
        return this;
    }

    @SuppressWarnings("unchecked")
    public <T> CacheBuilder<T> valueType(Class<T> type) {
        Objects.requireNonNull(type, "type cannot be null");
        CacheBuilder<T> casted = (CacheBuilder<T>) this;
        casted.valueType = type;
        return casted;
    }

    public CacheBuilder<V> eventBus(EventBus eventBus) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus cannot be null");
        return this;
    }

    public CacheBuilder<V> replicationPort(ReplicationPort replicationPort) {
        this.replicationPort = Objects.requireNonNull(replicationPort, "replicationPort cannot be null");
        return this;
    }

    public CacheBuilder<V> storagePort(StoragePort<V> storagePort) {
        this.storagePort = Objects.requireNonNull(storagePort, "storagePort cannot be null");
        return this;
    }

    @SuppressWarnings("unchecked")
    public CacheEngine<V> build() {
        if (built) {
            throw new IllegalStateException("build() can only be called once per CacheBuilder instance");
        }
        if (capacity == null) {
            throw new IllegalStateException("capacity must be configured before calling build()");
        }

        built = true;

        EvictionPort eviction = (evictionStrategy != null) ? evictionStrategy : new LRUEvictionPort(capacity);
        EventBus bus = (eventBus != null) ? eventBus : new EventBus();
        ReplicationPort rep = (replicationPort != null) ? replicationPort : new NoOpReplicationPort();
        StoragePort<V> storage = (storagePort != null) ? storagePort : new ConcurrentMapStorage<>();

        return new CacheEngine<>(storage, rep, bus, eviction);
    }

    private static final class NoOpReplicationPort implements ReplicationPort {
        @Override
        public void replicate(com.nedder3.cache.core.event.CacheEvent event) {
        }

        @Override
        public void notifyPut(CacheKey key) {
        }

        @Override
        public void notifyDelete(CacheKey key) {
        }

        @Override
        public void addListener(ReplicationEventListener listener) {
        }

        @Override
        public void removeListener(ReplicationEventListener listener) {
        }
    }
}