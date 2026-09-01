package com.nedder3.cache.replication.service;

import com.nedder3.cache.core.clock.VectorClock;
import com.nedder3.cache.core.event.PutEvent;
import com.nedder3.cache.core.model.CacheEntry;
import com.nedder3.cache.core.model.CacheKey;
import com.nedder3.cache.core.port.SerializerPort;
import com.nedder3.cache.replication.conflict.ConflictResolver;
import com.nedder3.cache.replication.membership.ClusterMembership;
import com.nedder3.cache.replication.model.ClusterNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ActiveActiveReplicationServiceTest {

    private ActiveActiveReplicationService<String> service;
    private ClusterMembership membership;

    private static class StringSerializer implements SerializerPort<String> {
        @Override
        public byte[] serialize(String object) {
            return object.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String deserialize(byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }

        @Override
        public void addListener(SerializationEventListener listener) {}

        @Override
        public void removeListener(SerializationEventListener listener) {}
    }

    @BeforeEach
    void setUp() {
        ClusterNode self = new ClusterNode("node-1", "127.0.0.1", 9001);
        membership = new ClusterMembership(self);
        service = new ActiveActiveReplicationService<>(membership, new ConflictResolver<>(), new StringSerializer());
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    @Test
    @DisplayName("Replicate queues event and dispatches to listeners asynchronously")
    void replicate_dispatchesAsync() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        service.addListener(event -> {
            if ("REPLICATION_BROADCAST".equals(event.getType())) {
                latch.countDown();
            }
        });

        CacheKey key = new CacheKey("default", "k1");
        PutEvent put = new PutEvent(key, "v1".getBytes(StandardCharsets.UTF_8), new VectorClock(Map.of("node-1", 1L)), 1000L);
        service.replicate(put);

        boolean reached = latch.await(2, TimeUnit.SECONDS);
        assertThat(reached).isTrue();
    }

    @Test
    @DisplayName("Receive remote event applies conflict resolution correctly")
    void receiveRemoteEvent_appliesConflictResolution() {
        CacheKey key = new CacheKey("default", "balance");
        VectorClock localClock = new VectorClock(Map.of("node-1", 1L));
        CacheEntry<String> local = new CacheEntry<>(key, "100", localClock, 1000L, OptionalLong.empty());

        VectorClock remoteClock = new VectorClock(Map.of("node-1", 1L, "node-2", 1L));
        PutEvent remotePut = new PutEvent(
                key,
                "150".getBytes(StandardCharsets.UTF_8),
                remoteClock,
                2000L
        );

        CacheEntry<String> resolved = service.receiveRemoteEvent(remotePut, local);

        assertThat(resolved).isNotNull();
        assertThat(resolved.value()).isEqualTo("150");
        assertThat(resolved.version().counters()).containsEntry("node-2", 1L);
    }
}
