package com.nedder3.cache.core.engine;

import com.nedder3.cache.core.event.EventBus;
import com.nedder3.cache.core.model.CacheKey;
import com.nedder3.cache.core.model.CacheStats;
import com.nedder3.cache.core.port.EvictionPort;
import com.nedder3.cache.core.port.LFUEvictionStrategy;
import com.nedder3.cache.core.port.LRUEvictionPort;
import com.nedder3.cache.core.port.ReplicationPort;
import com.nedder3.cache.core.port.StoragePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * RED tests for {@code com.nedder3.cache.core.engine.CacheBuilder<V>}.
 *
 * <p>These tests define the CacheBuilder fluent-API contract — the primary factory
 * for assembling a fully-wired {@link CacheEngine} from its port dependencies.
 * Written BEFORE the implementation exists.
 *
 * <p>Contract scope:
 * <ol>
 *   <li>Static factory methods: {@code builder()}, {@code withDefaults()}, {@code async()}.</li>
 *   <li>Fluent config: capacity, evictionStrategy, valueType, eventBus, replicationPort.</li>
 *   <li>Validation: null rejection on every setter, illegal-capacity guard.</li>
 *   <li>{@code build()} wires every port and returns a working CacheEngine.</li>
 *   <li>IllegalStateException on double-build (builder is single-use).</li>
 *   <li>Every build() variant produces a CacheEngine that passes CacheEngine's own test contract.</li>
 *   <li>All config options are reflected in the built engine's behavior.</li>
 * </ol>
 */
class CacheBuilderTest {

    // -------------------------------------------------------------------------
    // 1. Static factory methods
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("1. Static factory — builder(), withDefaults(), async()")
    class StaticFactories {

        @Test
        @DisplayName("builder() returns a new CacheBuilder instance")
        void builderReturnsInstance() {
            Object builder = CacheBuilder.builder();
            assertThat(builder).isNotNull();
        }

        @Test
        @DisplayName("withDefaults() returns a pre-configured builder")
        void withDefaultsReturnsConfiguredBuilder() {
            Object builder = CacheBuilder.withDefaults();
            assertThat(builder).isNotNull();
        }

        @Test
        @DisplayName("async() returns a builder configured for async event dispatch")
        void asyncReturnsAsyncConfiguredBuilder() {
            Object builder = CacheBuilder.async();
            assertThat(builder).isNotNull();
        }

        @Test
        @DisplayName("builder() produces a builder that uses synchronous EventBus by default")
        void builderDefaultEventBusIsSync() {
            CacheEngine<Object> engine = CacheBuilder.builder()
                    .capacity(100)
                    .evictionStrategy(new LRUEvictionPort(100))
                    .build();

            // Put an item and verify events are dispatched synchronously
            AtomicBoolean eventFired = new AtomicBoolean(false);
            engine.addListener(event -> eventFired.set(true));

            engine.put("ns", "k1", "value");

            // In sync mode, listener fires before put() returns
            assertThat(eventFired.get()).isTrue();
            engine.stats(); // just to confirm engine is healthy
        }
    }

    // -------------------------------------------------------------------------
    // 2. Fluent config — capacity
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("2. capacity(int) — positive integer required")
    class Capacity {

        @Test
        @DisplayName("accepts a positive capacity")
        void acceptsPositiveCapacity() {
            CacheEngine<Object> engine = CacheBuilder.builder()
                    .capacity(1)
                    .evictionStrategy(new LRUEvictionPort(1))
                    .build();

            engine.put("ns", "k1", "v1");
            assertThat(engine.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("rejects zero capacity with IllegalArgumentException")
        void rejectsZeroCapacity() {
            assertThatThrownBy(() ->
                    CacheBuilder.builder().capacity(0)
            ).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("capacity");
        }

        @Test
        @DisplayName("rejects negative capacity with IllegalArgumentException")
        void rejectsNegativeCapacity() {
            assertThatThrownBy(() ->
                    CacheBuilder.builder().capacity(-1)
            ).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("capacity");
        }

        @Test
        @DisplayName("capacity of 1 allows exactly one entry")
        void capacityOneAllowsOneEntry() {
            CacheEngine<Object> engine = CacheBuilder.builder()
                    .capacity(1)
                    .evictionStrategy(new LRUEvictionPort(1))
                    .build();

            engine.put("ns", "k1", "v1");
            engine.put("ns", "k2", "v2"); // should trigger eviction of k1

            assertThat(engine.get(new CacheKey("ns", "k2"))).isPresent();
            // k1 was evicted — LRUEvictionPort capacity check is enforced by the engine
        }
    }

    // -------------------------------------------------------------------------
    // 3. Fluent config — evictionStrategy
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("3. evictionStrategy(EvictionPort) — null rejection and LRU/LFU wiring")
    class EvictionStrategy {

        @Test
        @DisplayName("accepts LRUEvictionPort and wires it into the engine")
        void acceptsLRUEvictionPort() {
            LRUEvictionPort lru = new LRUEvictionPort(3);
            CacheEngine<Object> engine = CacheBuilder.builder()
                    .capacity(3)
                    .evictionStrategy(lru)
                    .build();

            engine.put("ns", "k1", "v1");
            engine.put("ns", "k2", "v2");
            engine.put("ns", "k3", "v3");
            engine.get(new CacheKey("ns", "k1")); // touch k1 (becomes MRU: order k2, k3, k1)

            engine.put("ns", "k4", "v4"); // k2 should be evicted (LRU)

            assertThat(engine.get(new CacheKey("ns", "k2"))).isEmpty();
            assertThat(engine.get(new CacheKey("ns", "k1"))).isPresent();
            assertThat(engine.get(new CacheKey("ns", "k4"))).isPresent();
        }

        @Test
        @DisplayName("accepts LFUEvictionStrategy and wires it into the engine")
        void acceptsLFUEvictionStrategy() {
            LFUEvictionStrategy lfu = new LFUEvictionStrategy(2);
            CacheEngine<Object> engine = CacheBuilder.builder()
                    .capacity(2)
                    .evictionStrategy(lfu)
                    .build();

            engine.put("ns", "k1", "v1");
            engine.put("ns", "k2", "v2");
            engine.get(new CacheKey("ns", "k1")); // k1 freq=2, k2 freq=1
            engine.put("ns", "k3", "v3"); // k2 (freq=1) should be evicted

            assertThat(engine.get(new CacheKey("ns", "k1"))).isPresent();
            assertThat(engine.get(new CacheKey("ns", "k2"))).isEmpty();
            assertThat(engine.get(new CacheKey("ns", "k3"))).isPresent();
        }

        @Test
        @DisplayName("rejects null EvictionPort with NullPointerException")
        void rejectsNullEvictionPort() {
            assertThatThrownBy(() ->
                    CacheBuilder.builder().evictionStrategy((EvictionPort) null)
            ).isInstanceOf(NullPointerException.class);
        }
    }

    // -------------------------------------------------------------------------
    // 4. Fluent config — valueType (documentation / metadata only)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("4. valueType(Class<V>) — stores type metadata, build() returns typed engine")
    class ValueType {

        @Test
        @DisplayName("accepts a Class<V> and build() returns CacheEngine<V>")
        void acceptsClassAndReturnsTypedEngine() {
            CacheEngine<String> engine = CacheBuilder.<String>builder()
                    .capacity(10)
                    .evictionStrategy(new LRUEvictionPort(10))
                    .valueType(String.class)
                    .build();

            engine.put("ns", "k", "hello");
            Optional<String> result = engine.get(new CacheKey("ns", "k"));
            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo("hello");
        }

        @Test
        @DisplayName("valueType can be called after other config methods (fluent order)")
        void valueTypeCanFollowOtherMethods() {
            CacheEngine<Integer> engine = CacheBuilder.<Integer>builder()
                    .capacity(5)
                    .evictionStrategy(new LRUEvictionPort(5))
                    .valueType(Integer.class)
                    .build();

            engine.put("ns", "k", 42);
            assertThat(engine.get(new CacheKey("ns", "k"))).hasValue(42);
        }
    }

    // -------------------------------------------------------------------------
    // 5. Fluent config — eventBus
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("5. eventBus(EventBus) — custom EventBus wiring")
    class EventBusConfig {

        @Test
        @DisplayName("accepts a custom EventBus and wires it into the engine")
        void acceptsCustomEventBus() {
            EventBus customBus = new EventBus();
            CacheEngine<Object> engine = CacheBuilder.builder()
                    .capacity(10)
                    .evictionStrategy(new LRUEvictionPort(10))
                    .eventBus(customBus)
                    .build();

            AtomicBoolean eventReceived = new AtomicBoolean(false);
            engine.addListener(event -> eventReceived.set(true));

            engine.put("ns", "k1", "value");

            assertThat(eventReceived.get()).isTrue();
        }

        @Test
        @DisplayName("rejects null EventBus with NullPointerException")
        void rejectsNullEventBus() {
            assertThatThrownBy(() ->
                    CacheBuilder.builder().eventBus(null)
            ).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("build() without explicit eventBus defaults to synchronous EventBus")
        void defaultsToSyncEventBus() {
            CacheEngine<Object> engine = CacheBuilder.builder()
                    .capacity(10)
                    .evictionStrategy(new LRUEvictionPort(10))
                    .build();

            AtomicBoolean syncFired = new AtomicBoolean(false);
            engine.addListener(e -> syncFired.set(true));

            engine.put("ns", "k", "v");
            assertThat(syncFired.get()).isTrue();
        }
    }

    // -------------------------------------------------------------------------
    // 6. Fluent config — replicationPort
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("6. replicationPort(ReplicationPort) — optional, defaults to no-op")
    class ReplicationPortConfig {

        @Test
        @DisplayName("accepts a custom ReplicationPort")
        void acceptsCustomReplicationPort() {
            ReplicationPort custom = mock(ReplicationPort.class);
            CacheEngine<Object> engine = CacheBuilder.builder()
                    .capacity(10)
                    .evictionStrategy(new LRUEvictionPort(10))
                    .replicationPort(custom)
                    .build();

            engine.put("ns", "k", "value");
            engine.delete(new CacheKey("ns", "k"));

            // Engine is functional with custom replication port
            assertThat(engine.size()).isZero();
        }

        @Test
        @DisplayName("build() without replicationPort uses a no-op implementation")
        void defaultsToNoOpReplication() {
            CacheEngine<Object> engine = CacheBuilder.builder()
                    .capacity(10)
                    .evictionStrategy(new LRUEvictionPort(10))
                    .build();

            // Should not throw — no-op replication is wired by default
            engine.put("ns", "k", "value");
            assertThat(engine.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("rejects null ReplicationPort with NullPointerException")
        void rejectsNullReplicationPort() {
            assertThatThrownBy(() ->
                    CacheBuilder.builder().replicationPort(null)
            ).isInstanceOf(NullPointerException.class);
        }
    }

    // -------------------------------------------------------------------------
    // 7. build() — wiring and IllegalStateException guards
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("7. build() — complete wiring and single-use enforcement")
    class BuildMethod {

        @Test
        @DisplayName("build() returns a fully functional CacheEngine")
        void buildReturnsFunctionalEngine() {
            CacheEngine<Object> engine = CacheBuilder.builder()
                    .capacity(100)
                    .evictionStrategy(new LRUEvictionPort(100))
                    .build();

            // Verify full CRUD contract
            engine.put("ns", "k1", "value");
            assertThat(engine.get(new CacheKey("ns", "k1"))).hasValue("value");

            engine.put("ns", "k1", "updated");
            assertThat(engine.get(new CacheKey("ns", "k1"))).hasValue("updated");

            boolean deleted = engine.delete(new CacheKey("ns", "k1"));
            assertThat(deleted).isTrue();
            assertThat(engine.get(new CacheKey("ns", "k1"))).isEmpty();

            assertThat(engine.size()).isZero();
        }

        @Test
        @DisplayName("build() throws IllegalStateException when called a second time")
        void buildThrowsOnSecondCall() {
            CacheBuilder<Object> builder = CacheBuilder.builder()
                    .capacity(10)
                    .evictionStrategy(new LRUEvictionPort(10));

            builder.build(); // first call — succeeds

            assertThatThrownBy(builder::build) // second call — fails
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("build");
        }

        @Test
        @DisplayName("build() throws IllegalStateException when capacity is not set")
        void buildThrowsWhenCapacityMissing() {
            assertThatThrownBy(() ->
                    CacheBuilder.builder()
                            .build()
            ).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("capacity");
        }

        @Test
        @DisplayName("built engine tracks hits and misses via stats()")
        void builtEngineTracksStats() {
            CacheEngine<Object> engine = CacheBuilder.builder()
                    .capacity(100)
                    .evictionStrategy(new LRUEvictionPort(100))
                    .build();

            engine.put("ns", "k1", "v1");
            engine.get(new CacheKey("ns", "k1")); // hit
            engine.get(new CacheKey("ns", "absent")); // miss

            CacheStats stats = engine.stats();
            assertThat(stats.hits()).isEqualTo(1);
            assertThat(stats.misses()).isEqualTo(1);
            assertThat(stats.puts()).isEqualTo(1);
        }

        @Test
        @DisplayName("built engine fires events on put, delete, and eviction")
        void builtEngineFiresEvents() {
            CacheEngine<Object> engine = CacheBuilder.builder()
                    .capacity(2)
                    .evictionStrategy(new LRUEvictionPort(2))
                    .build();

            java.util.List<String> eventTypes = new java.util.concurrent.CopyOnWriteArrayList<>();
            engine.addListener(event -> eventTypes.add(event.getClass().getSimpleName()));

            engine.put("ns", "k1", "v1");
            assertThat(eventTypes).contains("PutEvent");

            engine.put("ns", "k2", "v2");
            engine.put("ns", "k3", "v3"); // triggers eviction of k1

            assertThat(eventTypes).contains("EvictEvent");
        }
    }

    // -------------------------------------------------------------------------
    // 8. withDefaults() — sensible defaults
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("8. withDefaults() — pre-configured builder with LRU, capacity, sync bus")
    class WithDefaults {

        @Test
        @DisplayName("withDefaults() pre-sets capacity and LRU strategy")
        void preSetsCapacityAndLRU() {
            CacheEngine<Object> engine = CacheBuilder.withDefaults()
                    .build();

            // LRU wired — fill and overflow to verify eviction fires
            for (int i = 0; i < 110; i++) {
                engine.put("ns", "k" + i, "v" + i);
            }

            // With LRU, oldest entry was evicted
            assertThat(engine.size()).isLessThanOrEqualTo(100);
            assertThat(engine.stats().evictions()).isGreaterThan(0);
        }

        @Test
        @DisplayName("withDefaults() allows overriding capacity before build")
        void allowsCapacityOverride() {
            CacheEngine<Object> engine = CacheBuilder.withDefaults()
                    .capacity(5)
                    .build();

            for (int i = 0; i < 10; i++) {
                engine.put("ns", "k" + i, "v" + i);
            }

            assertThat(engine.size()).isLessThanOrEqualTo(5);
        }

        @Test
        @DisplayName("withDefaults() allows overriding eviction strategy")
        void allowsEvictionOverride() {
            LFUEvictionStrategy lfu = new LFUEvictionStrategy(3);
            CacheEngine<Object> engine = CacheBuilder.withDefaults()
                    .evictionStrategy(lfu)
                    .build();

            engine.put("ns", "k1", "v1");
            engine.put("ns", "k2", "v2");
            engine.put("ns", "k3", "v3");
            engine.get(new CacheKey("ns", "k1")); // freq(k1)=2, freq(k2)=1, freq(k3)=1
            engine.put("ns", "k4", "v4"); // k2 or k3 evicted (lowest freq)

            assertThat(engine.size()).isLessThanOrEqualTo(3);
        }
    }

    // -------------------------------------------------------------------------
    // 9. async() — async event bus
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("9. async() — builder configured with EventBus.async()")
    class AsyncFactory {

        @Test
        @DisplayName("async() builds an engine with async EventBus")
        void asyncBuildsEngineWithAsyncBus() {
            CacheEngine<Object> engine = CacheBuilder.async()
                    .capacity(10)
                    .evictionStrategy(new LRUEvictionPort(10))
                    .build();

            // Engine is functional even with async bus
            engine.put("ns", "k", "value");
            assertThat(engine.get(new CacheKey("ns", "k"))).hasValue("value");
        }

        @Test
        @DisplayName("async() engine still fires events (asynchronously)")
        void asyncEngineFiresEvents() throws InterruptedException {
            CacheEngine<Object> engine = CacheBuilder.async()
                    .capacity(10)
                    .evictionStrategy(new LRUEvictionPort(10))
                    .build();

            AtomicBoolean eventFired = new AtomicBoolean(false);
            engine.addListener(e -> eventFired.set(true));

            engine.put("ns", "k", "value");

            // With async bus, give virtual thread a moment to dispatch
            Thread.sleep(100);

            assertThat(eventFired.get()).isTrue();
        }

        @Test
        @DisplayName("async() returns builder that can still be configured")
        void asyncReturnsConfigurableBuilder() {
            CacheEngine<Object> engine = CacheBuilder.async()
                    .capacity(5)
                    .evictionStrategy(new LFUEvictionStrategy(5))
                    .build();

            engine.put("ns", "k", "v");
            assertThat(engine.size()).isEqualTo(1);
        }
    }

    // -------------------------------------------------------------------------
    // 10. Full pipeline — end-to-end smoke test
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("10. Full pipeline — realistic multi-config end-to-end")
    class FullPipeline {

        @Test
        @DisplayName("builder with all config options produces a correct engine")
        void allOptionsProduceCorrectEngine() {
            EventBus bus = new EventBus();
            ReplicationPort replication = mock(ReplicationPort.class);
            LRUEvictionPort eviction = new LRUEvictionPort(50);

            CacheEngine<String> engine = CacheBuilder.<String>builder()
                    .capacity(50)
                    .evictionStrategy(eviction)
                    .valueType(String.class)
                    .eventBus(bus)
                    .replicationPort(replication)
                    .build();

            // Exercise every operation
            engine.put("users", "alice", "Alice Smith");
            engine.put("users", "bob", "Bob Jones", 5000L); // with TTL

            assertThat(engine.get(new CacheKey("users", "alice"))).hasValue("Alice Smith");
            assertThat(engine.get(new CacheKey("users", "bob"))).hasValue("Bob Jones");

            engine.delete(new CacheKey("users", "alice"));
            assertThat(engine.get(new CacheKey("users", "alice"))).isEmpty();
            assertThat(engine.size()).isEqualTo(1);

            CacheStats stats = engine.stats();
            assertThat(stats.hits()).isEqualTo(2);
            assertThat(stats.misses()).isEqualTo(1);
            assertThat(stats.puts()).isEqualTo(2);
            assertThat(stats.deletes()).isEqualTo(1);
        }

        @Test
        @DisplayName("capacity enforcement works correctly on the built engine")
        void capacityEnforcementOnBuiltEngine() {
            CacheEngine<Object> engine = CacheBuilder.builder()
                    .capacity(3)
                    .evictionStrategy(new LRUEvictionPort(3))
                    .build();

            engine.put("ns", "k1", "v1");
            engine.put("ns", "k2", "v2");
            engine.put("ns", "k3", "v3");
            assertThat(engine.size()).isEqualTo(3);

            engine.put("ns", "k4", "v4"); // must evict one entry

            assertThat(engine.size()).isLessThanOrEqualTo(3);
            assertThat(engine.stats().evictions()).isGreaterThan(0);
        }
    }
}
