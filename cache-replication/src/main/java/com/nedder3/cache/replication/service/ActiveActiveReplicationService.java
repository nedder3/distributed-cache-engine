package com.nedder3.cache.replication.service;

import com.nedder3.cache.core.clock.Causality;
import com.nedder3.cache.core.clock.VectorClock;
import com.nedder3.cache.core.event.CacheEvent;
import com.nedder3.cache.core.event.DeleteEvent;
import com.nedder3.cache.core.event.PutEvent;
import com.nedder3.cache.core.model.CacheEntry;
import com.nedder3.cache.core.model.CacheKey;
import com.nedder3.cache.core.port.ReplicationPort;
import com.nedder3.cache.core.port.SerializerPort;
import com.nedder3.cache.replication.conflict.ConflictResolver;
import com.nedder3.cache.replication.membership.ClusterMembership;

import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * High-performance, active-active replication service orchestrating inter-node event broadcasts,
 * conflict resolution and async replication.
 */
public class ActiveActiveReplicationService<V> implements ReplicationPort, AutoCloseable {

    private final ClusterMembership membership;
    private final ConflictResolver<V> conflictResolver;
    private final SerializerPort<V> serializer;
    private final BlockingQueue<CacheEvent> replicationQueue = new LinkedBlockingQueue<>(10_000);
    private final List<ReplicationEventListener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService dispatcherExecutor;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public ActiveActiveReplicationService(ClusterMembership membership, ConflictResolver<V> conflictResolver, SerializerPort<V> serializer) {
        this.membership = Objects.requireNonNull(membership, "membership cannot be null");
        this.conflictResolver = Objects.requireNonNull(conflictResolver, "conflictResolver cannot be null");
        this.serializer = Objects.requireNonNull(serializer, "serializer cannot be null");
        this.dispatcherExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "replication-dispatcher-" + membership.self().nodeId());
            t.setDaemon(true);
            return t;
        });
        startDispatcher();
    }

    private void startDispatcher() {
        dispatcherExecutor.submit(() -> {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    CacheEvent event = replicationQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (event != null) {
                        broadcastToCluster(event);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    private void broadcastToCluster(CacheEvent event) {
        ReplicationEvent repEvent = new ReplicationEvent("REPLICATION_BROADCAST", event);
        for (ReplicationEventListener listener : listeners) {
            try {
                listener.onEvent(repEvent);
            } catch (Exception e) {
                // Ignore listener exceptions during broadcast
            }
        }
    }

    @Override
    public void replicate(CacheEvent event) {
        if (!running.get() || event == null) {
            return;
        }
        replicationQueue.offer(event);
    }

    /**
     * Ingests a remote event received from another cluster node and resolves any conflict.
     *
     * @param remoteEvent the incoming remote cache event
     * @param localEntry  current local entry for the key
     * @return the resolved CacheEntry to apply, or null if deleted/evicted
     */
    public CacheEntry<V> receiveRemoteEvent(CacheEvent remoteEvent, CacheEntry<V> localEntry) {
        Objects.requireNonNull(remoteEvent, "remoteEvent cannot be null");

        if (remoteEvent instanceof PutEvent put) {
            V value = serializer.deserialize(put.serializedValue());
            CacheEntry<V> remoteEntry = new CacheEntry<>(
                    put.key(),
                    value,
                    put.vectorClock(),
                    put.timestamp(),
                    OptionalLong.empty()
            );
            return conflictResolver.resolve(localEntry, remoteEntry);
        } else if (remoteEvent instanceof DeleteEvent del) {
            if (localEntry == null) {
                return null;
            }
            Causality causality = del.vectorClock().causalityCheck(localEntry.version());
            if (causality == Causality.AFTER || del.timestamp() >= localEntry.createdAt()) {
                return null;
            }
            return localEntry;
        }
        return localEntry;
    }

    @Override
    public void addListener(ReplicationEventListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public void removeListener(ReplicationEventListener listener) {
        listeners.remove(listener);
    }

    public ClusterMembership getMembership() {
        return membership;
    }

    public ConflictResolver<V> getConflictResolver() {
        return conflictResolver;
    }

    public int queueSize() {
        return replicationQueue.size();
    }

    @Override
    public void close() {
        running.set(false);
        dispatcherExecutor.shutdownNow();
    }
}
