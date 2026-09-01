package com.nedder3.cache.server;

import com.nedder3.cache.core.engine.CacheEngine;
import com.nedder3.cache.core.event.EventBus;
import com.nedder3.cache.core.port.EvictionPort;
import com.nedder3.cache.core.port.ReplicationPort;
import com.nedder3.cache.core.port.StoragePort;
import com.nedder3.cache.core.snapshot.SnapshotStrategy;

public class CacheBuilder {
    private EvictionPort evictionStrategy;
    private SnapshotStrategy snapshotStrategy;
    private ReplicationPort replicationStrategy;
    private StoragePort<Object> storagePort;
    private EventBus eventBus = new EventBus();

    public CacheBuilder withEvictionStrategy(EvictionPort evictionStrategy) {
        this.evictionStrategy = evictionStrategy;
        return this;
    }

    public CacheBuilder withSnapshotStrategy(SnapshotStrategy snapshotStrategy) {
        this.snapshotStrategy = snapshotStrategy;
        return this;
    }

    public CacheBuilder withReplicationStrategy(ReplicationPort replicationStrategy) {
        this.replicationStrategy = replicationStrategy;
        return this;
    }

    @SuppressWarnings("unchecked")
    public CacheBuilder withStoragePort(StoragePort storagePort) {
        this.storagePort = (StoragePort<Object>) storagePort;
        return this;
    }

    public CacheBuilder withEventBus(EventBus eventBus) {
        this.eventBus = eventBus;
        return this;
    }

    public CacheEngine<Object> build() {
        return new CacheEngine<>(storagePort, replicationStrategy, eventBus, evictionStrategy);
    }
}