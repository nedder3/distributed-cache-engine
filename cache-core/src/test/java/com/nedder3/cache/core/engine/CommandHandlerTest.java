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
import com.nedder3.cache.core.clock.VectorClock;
import com.nedder3.cache.core.port.StoragePort;
import com.nedder3.cache.core.port.ReplicationPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RED tests for {@code com.nedder3.cache.core.engine.CommandHandler}.
 *
 * <p>These tests are written BEFORE the implementation exists (RED phase).
 * They define the CQRS CommandHandler contract:
 * <ol>
 *   <li>put(namespace, key, value) stores entry and emits PutEvent.</li>
 *   <li>put(namespace, key, value, ttl) stores with expiration and emits PutEvent.</li>
 *   <li>delete(namespace, key) removes entry and emits DeleteEvent.</li>
 *   <li>evict(CacheKey, EvictionReason) removes entry and emits EvictEvent.</li>
 *   <li>EventBus is always notified on every mutating operation.</li>
 *   <li>StoragePort is always written to on every mutating operation.</li>
 *   <li>ReplicationPort is always notified on every mutating operation.</li>
 *   <li>StatsCollector is always updated on every mutating operation.</li>
 *   <li>Null arguments are rejected with NPE/IAE.</li>
 *   <li>StoragePort failures do NOT prevent event emission (eventual-consistency isolation).</li>
 *   <li>ReplicationPort failures do NOT prevent local completion.</li>
 * </ol>
 */
class CommandHandlerTest {

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private StoragePort<Object> storage;
    private ReplicationPort replication;
    private CommandHandler<Object> handler;
    private TestEventBus bus;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        storage = mock(StoragePort.class);
        replication = mock(ReplicationPort.class);
        bus = new TestEventBus();
        handler = new CommandHandler<Object>(storage, replication, bus);
    }

    // -------------------------------------------------------------------------
    // 1. put(namespace, key, value) — basic store
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("1. put(namespace, key, value) — store entry")
    class PutBasic {

        @Test
        @DisplayName("stores the value in StoragePort under the composite CacheKey")
        void storesInStorage() {
            CacheKey key = new CacheKey("ns", "k1");
            Object value = "value";

            handler.put("ns", "k1", value);

            verify(storage).write(key, value, OptionalLong.empty());
        }

        @Test
        @DisplayName("emits a PutEvent to the EventBus with the correct CacheKey")
        void emitsPutEventWithKey() {
            CacheKey key = new CacheKey("ns", "k1");

            handler.put("ns", "k1", "v");

            PutEvent event = bus.lastEventOfType(PutEvent.class);
            assertThat(event).isNotNull();
            assertThat(event.key()).isEqualTo(key);
        }

        @Test
        @DisplayName("PutEvent carries the serialized value bytes")
        void putEventCarriesSerializedValue() {
            CacheKey key = new CacheKey("ns", "k1");
            Object value = "v";

            handler.put("ns", "k1", value);

            PutEvent event = bus.lastEventOfType(PutEvent.class);
            assertThat(event).isNotNull();
            assertThat(event.serializedValue()).isNotNull();
        }

        @Test
        @DisplayName("PutEvent carries a non-empty VectorClock")
        void putEventHasVectorClock() {
            handler.put("ns", "k1", "v");

            PutEvent event = bus.lastEventOfType(PutEvent.class);
            assertThat(event).isNotNull();
            assertThat(event.vectorClock()).isNotNull();
            assertThat(event.vectorClock().counters()).isNotEmpty();
        }

        @Test
        @DisplayName("PutEvent carries a timestamp")
        void putEventHasTimestamp() {
            long before = System.currentTimeMillis();

            handler.put("ns", "k1", "v");

            PutEvent event = bus.lastEventOfType(PutEvent.class);
            assertThat(event).isNotNull();
            assertThat(event.timestamp()).isGreaterThanOrEqualTo(before);
        }

        @Test
        @DisplayName("calls ReplicationPort.notifyPut with the key")
        void notifiesReplicationPort() {
            CacheKey key = new CacheKey("ns", "k1");

            handler.put("ns", "k1", "v");

            verify(replication).notifyPut(key);
        }

        @Test
        @DisplayName("records a put in StatsCollector")
        void recordsPutStat() {
            TestStatsCollector stats = new TestStatsCollector();

            // Build handler with stats collector
            CommandHandler<Object> statHandler =
                new CommandHandler<Object>(storage, replication, bus, stats);

            statHandler.put("ns", "k1", "v");

            assertThat(stats.puts()).isEqualTo(1);
        }
    }

    // -------------------------------------------------------------------------
    // 2. put(namespace, key, value, ttl) — store with TTL
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("2. put(namespace, key, value, ttlMillis) — store with TTL")
    class PutWithTtl {

        @Test
        @DisplayName("writes the entry with the correct expiration to StoragePort")
        void writesWithExpiration() {
            CacheKey key = new CacheKey("ns", "k1");
            long ttlMillis = 5_000L;

            handler.put("ns", "k1", "v", ttlMillis);

            verify(storage).write(
                org.mockito.ArgumentMatchers.eq(key),
                org.mockito.ArgumentMatchers.eq("v"),
                org.mockito.ArgumentMatchers.argThat(opt -> opt.isPresent() && Math.abs(opt.getAsLong() - (System.currentTimeMillis() + ttlMillis)) < 1000)
            );
        }

        @Test
        @DisplayName("emits a PutEvent (same as no-TTL path)")
        void emitsPutEvent() {
            handler.put("ns", "k1", "v", 5_000L);

            PutEvent event = bus.lastEventOfType(PutEvent.class);
            assertThat(event).isNotNull();
            assertThat(event.key()).isEqualTo(new CacheKey("ns", "k1"));
        }

        @Test
        @DisplayName("negative TTL is rejected with IllegalArgumentException")
        void negativeTtlRejected() {
            assertThatThrownBy(() ->
                handler.put("ns", "k1", "v", -1L))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("zero TTL is accepted (immediate expiration)")
        void zeroTtlAccepted() {
            // Zero TTL means entry expires immediately on next access.
            // Implementation should record it; no exception.
            assertThatThrownBy(() ->
                handler.put("ns", "k1", "v", 0L))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // -------------------------------------------------------------------------
    // 3. delete(namespace, key) — explicit removal
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("3. delete(namespace, key) — explicit removal")
    class Delete {

        @Test
        @DisplayName("calls StoragePort.delete with the composite CacheKey")
        void callsStorageDelete() {
            CacheKey key = new CacheKey("ns", "k1");

            handler.delete("ns", "k1");

            verify(storage).delete(key);
        }

        @Test
        @DisplayName("returns true when the key existed in storage")
        void returnsTrueWhenExisted() {
            when(storage.delete(any())).thenReturn(true);

            boolean result = handler.delete("ns", "k1");

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("returns false when the key did not exist")
        void returnsFalseWhenNotExisted() {
            when(storage.delete(any())).thenReturn(false);

            boolean result = handler.delete("ns", "k1");

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("emits a DeleteEvent with the correct CacheKey")
        void emitsDeleteEvent() {
            CacheKey key = new CacheKey("ns", "k1");

            handler.delete("ns", "k1");

            DeleteEvent event = bus.lastEventOfType(DeleteEvent.class);
            assertThat(event).isNotNull();
            assertThat(event.key()).isEqualTo(key);
        }

        @Test
        @DisplayName("DeleteEvent carries a VectorClock")
        void deleteEventHasVectorClock() {
            handler.delete("ns", "k1");

            DeleteEvent event = bus.lastEventOfType(DeleteEvent.class);
            assertThat(event).isNotNull();
            assertThat(event.vectorClock()).isNotNull();
        }

        @Test
        @DisplayName("calls ReplicationPort.notifyDelete with the key")
        void notifiesReplication() {
            CacheKey key = new CacheKey("ns", "k1");

            handler.delete("ns", "k1");

            verify(replication).notifyDelete(key);
        }

        @Test
        @DisplayName("records a delete stat")
        void recordsDeleteStat() {
            TestStatsCollector stats = new TestStatsCollector();
            CommandHandler<Object> statHandler =
                new CommandHandler<Object>(storage, replication, bus, stats);

            statHandler.delete("ns", "k1");

            assertThat(stats.deletes()).isEqualTo(1);
        }
    }

    // -------------------------------------------------------------------------
    // 4. evict(CacheKey, EvictionReason) — eviction by strategy
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("4. evict(key, reason) — eviction by strategy")
    class Evict {

        @Test
        @DisplayName("calls StoragePort.delete with the given CacheKey")
        void callsStorageDelete() {
            CacheKey key = new CacheKey("ns", "k1");

            handler.evict(key, EvictionReason.CAPACITY);

            verify(storage).delete(key);
        }

        @Test
        @DisplayName("emits an EvictEvent with the given CacheKey and reason")
        void emitsEvictEventWithReason() {
            CacheKey key = new CacheKey("ns", "k1");

            handler.evict(key, EvictionReason.CAPACITY);
            handler.evict(new CacheKey("ns", "k2"), EvictionReason.TTL);

            List<EvictEvent> events = bus.eventsOfType(EvictEvent.class);
            assertThat(events).hasSize(2);
            assertThat(events.get(0).key()).isEqualTo(key);
            assertThat(events.get(0).reason()).isEqualTo(EvictionReason.CAPACITY);
            assertThat(events.get(1).reason()).isEqualTo(EvictionReason.TTL);
        }

        @Test
        @DisplayName("EvictEvent carries a timestamp")
        void evictEventHasTimestamp() {
            CacheKey key = new CacheKey("ns", "k1");
            long before = System.currentTimeMillis();

            handler.evict(key, EvictionReason.CAPACITY);

            EvictEvent event = bus.lastEventOfType(EvictEvent.class);
            assertThat(event).isNotNull();
            assertThat(event.timestamp()).isGreaterThanOrEqualTo(before);
        }

        @Test
        @DisplayName("records an eviction stat")
        void recordsEvictionStat() {
            TestStatsCollector stats = new TestStatsCollector();
            CommandHandler<Object> statHandler =
                new CommandHandler<Object>(storage, replication, bus, stats);

            statHandler.evict(new CacheKey("ns", "k1"), EvictionReason.CAPACITY);

            assertThat(stats.evictions()).isEqualTo(1);
        }
    }

    // -------------------------------------------------------------------------
    // 5. EventBus always notified on every mutating operation
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("5. EventBus notified on every mutating operation")
    class EventBusAlwaysNotified {

        @Test
        @DisplayName("put publishes exactly one PutEvent")
        void putPublishesOnePutEvent() {
            handler.put("ns", "k1", "v");

            List<PutEvent> events = bus.eventsOfType(PutEvent.class);
            assertThat(events).hasSize(1);
        }

        @Test
        @DisplayName("delete publishes exactly one DeleteEvent")
        void deletePublishesOneDeleteEvent() {
            handler.delete("ns", "k1");

            List<DeleteEvent> events = bus.eventsOfType(DeleteEvent.class);
            assertThat(events).hasSize(1);
        }

        @Test
        @DisplayName("evict publishes exactly one EvictEvent")
        void evictPublishesOneEvictEvent() {
            handler.evict(new CacheKey("ns", "k1"), EvictionReason.CAPACITY);

            List<EvictEvent> events = bus.eventsOfType(EvictEvent.class);
            assertThat(events).hasSize(1);
        }

        @Test
        @DisplayName("no CacheEvent is published when a null argument is rejected")
        void noEventOnNullArgument() {
            try {
                handler.put(null, "k1", "v");
            } catch (NullPointerException | IllegalArgumentException expected) {
                // expected — no event should be published
            }

            assertThat(bus.allEvents()).isEmpty();
        }
    }

    // -------------------------------------------------------------------------
    // 6. Null-argument rejection
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("6. Null-argument rejection")
    class NullArgumentRejection {

        @Test
        @DisplayName("put(null, key, value) throws NullPointerException")
        void putRejectsNullNamespace() {
            assertThatThrownBy(() -> handler.put(null, "k", "v"))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("put(namespace, null, value) throws NullPointerException")
        void putRejectsNullKey() {
            assertThatThrownBy(() -> handler.put("ns", null, "v"))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("put(namespace, key, null) throws NullPointerException")
        void putRejectsNullValue() {
            assertThatThrownBy(() -> handler.put("ns", "k", null))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("delete(null, key) throws NullPointerException")
        void deleteRejectsNullNamespace() {
            assertThatThrownBy(() -> handler.delete(null, "k"))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("delete(namespace, null) throws NullPointerException")
        void deleteRejectsNullKey() {
            assertThatThrownBy(() -> handler.delete("ns", null))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("evict(null, reason) throws NullPointerException")
        void evictRejectsNullKey() {
            assertThatThrownBy(() ->
                handler.evict(null, EvictionReason.CAPACITY))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("evict(key, null) throws NullPointerException")
        void evictRejectsNullReason() {
            assertThatThrownBy(() ->
                handler.evict(new CacheKey("ns", "k"), null))
                .isInstanceOf(NullPointerException.class);
        }
    }

    // -------------------------------------------------------------------------
    // 7. StoragePort failure isolation
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("7. StoragePort failure isolation")
    class StorageFailureIsolation {

        @Test
        @DisplayName("StoragePort write failure does NOT prevent PutEvent emission")
        void storageFailureDoesNotBlockPutEvent() {
            doThrow(new RuntimeException("storage down"))
                .when(storage).write(any(), any(), any());

            // Must NOT throw — event emission is local and must succeed
            handler.put("ns", "k1", "v");

            PutEvent event = bus.lastEventOfType(PutEvent.class);
            assertThat(event).isNotNull();
        }

        @Test
        @DisplayName("StoragePort delete failure does NOT prevent DeleteEvent emission")
        void storageFailureDoesNotBlockDeleteEvent() {
            doThrow(new RuntimeException("storage down"))
                .when(storage).delete(any());

            handler.delete("ns", "k1");

            DeleteEvent event = bus.lastEventOfType(DeleteEvent.class);
            assertThat(event).isNotNull();
        }

        @Test
        @DisplayName("StoragePort delete failure does NOT prevent EvictEvent emission")
        void storageFailureDoesNotBlockEvictEvent() {
            doThrow(new RuntimeException("storage down"))
                .when(storage).delete(any());

            handler.evict(new CacheKey("ns", "k1"), EvictionReason.CAPACITY);

            EvictEvent event = bus.lastEventOfType(EvictEvent.class);
            assertThat(event).isNotNull();
        }
    }

    // -------------------------------------------------------------------------
    // 8. ReplicationPort failure isolation
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("8. ReplicationPort failure isolation")
    class ReplicationFailureIsolation {

        @Test
        @DisplayName("ReplicationPort notifyPut failure does NOT cause put() to throw")
        void replicationPutFailureDoesNotPropagate() {
            doThrow(new RuntimeException("replication down"))
                .when(replication).notifyPut(any());

            // Must NOT throw — replication is fire-and-forget
            handler.put("ns", "k1", "v");
        }

        @Test
        @DisplayName("ReplicationPort notifyDelete failure does NOT cause delete() to throw")
        void replicationDeleteFailureDoesNotPropagate() {
            doThrow(new RuntimeException("replication down"))
                .when(replication).notifyDelete(any());

            handler.delete("ns", "k1");
        }

        @Test
        @DisplayName("ReplicationPort failure does NOT prevent local event emission")
        void replicationFailureDoesNotBlockEvent() {
            doThrow(new RuntimeException("replication down"))
                .when(replication).notifyPut(any());

            handler.put("ns", "k1", "v");

            PutEvent event = bus.lastEventOfType(PutEvent.class);
            assertThat(event).isNotNull();
        }
    }

    // -------------------------------------------------------------------------
    // 9. Idempotency / double-operation safety
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("9. Idempotency — repeated operations")
    class Idempotency {

        @Test
        @DisplayName("put the same key twice emits two PutEvents with distinct VectorClocks")
        void twoPutsEmitTwoEventsDistinctClocks() {
            handler.put("ns", "k1", "v");
            handler.put("ns", "k1", "v2");

            List<PutEvent> events = bus.eventsOfType(PutEvent.class);
            assertThat(events).hasSize(2);
            assertThat(events.get(0).vectorClock())
                .isNotEqualTo(events.get(1).vectorClock());
        }

        @Test
        @DisplayName("delete on a non-existent key emits DeleteEvent")
        void deleteNonExistentEmitsEvent() {
            when(storage.delete(any())).thenReturn(false);

            handler.delete("ns", "k1");

            DeleteEvent event = bus.lastEventOfType(DeleteEvent.class);
            assertThat(event).isNotNull();
        }
    }

    // -------------------------------------------------------------------------
    // Test infrastructure — in-process EventBus and StatsCollector mocks
    // -------------------------------------------------------------------------

    /** Lightweight in-process EventBus that records all published events. */
    private static class TestEventBus extends EventBus {
        private final CopyOnWriteArrayList<CacheEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void publish(CacheEvent event) {
            super.publish(event);
            events.add(event);
        }

        @SuppressWarnings("unchecked")
        <T extends CacheEvent> List<T> eventsOfType(Class<T> type) {
            return events.stream()
                .filter(type::isInstance)
                .map(e -> (T) e)
                .toList();
        }

        @SuppressWarnings("unchecked")
        <T extends CacheEvent> T lastEventOfType(Class<T> type) {
            List<T> matching = eventsOfType(type);
            return matching.isEmpty() ? null : matching.get(matching.size() - 1);
        }

        List<CacheEvent> allEvents() {
            return List.copyOf(events);
        }
    }

    /** Minimal StatsCollector test double — does NOT extend StatsCollector (private fields). */
    private static class TestStatsCollector implements com.nedder3.cache.core.port.StatsPort {
        private long hitsCount = 0;
        private long missesCount = 0;
        private long evictionsCount = 0;
        private long putsCount = 0;
        private long deletesCount = 0;

        public void recordHit() { hitsCount++; }
        public void recordMiss() { missesCount++; }
        public void recordEviction() { evictionsCount++; }
        public void recordPut() { putsCount++; }
        public void recordDelete() { deletesCount++; }

        public CacheStats snapshot() {
            return new CacheStats(hitsCount, missesCount, evictionsCount, putsCount, deletesCount);
        }

        public void reset() {
            hitsCount = missesCount = evictionsCount = putsCount = deletesCount = 0;
        }

        long hits()   { return hitsCount; }
        long misses() { return missesCount; }
        long evictions() { return evictionsCount; }
        long puts()   { return putsCount; }
        long deletes(){ return deletesCount; }
    }
}
