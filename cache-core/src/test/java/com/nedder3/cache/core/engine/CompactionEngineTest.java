package com.nedder3.cache.core.engine;

import com.nedder3.cache.core.event.CacheEvent;
import com.nedder3.cache.core.event.CompactionEvent;
import com.nedder3.cache.core.event.EventBus;
import com.nedder3.cache.core.model.CacheKey;
import com.nedder3.cache.core.port.StoragePort;
import com.nedder3.cache.core.snapshot.SnapshotStrategy;
import com.nedder3.cache.core.snapshot.ThresholdSnapshotStrategy;
import com.nedder3.cache.core.store.InMemoryEventStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CompactionEngine")
class CompactionEngineTest {

    private static final long T0 = 1_700_000_000_000L;

    private static Clock fixedClock(long millis) {
        return Clock.fixed(Instant.ofEpochMilli(millis), ZoneId.of("UTC"));
    }

    private InMemoryEventStore storage;
    private EventBus bus;
    private StatsCollector stats;
    private CompactionEngine engine;

    @BeforeEach
    void setUp() {
        storage = new InMemoryEventStore();
        bus = new EventBus();
        stats = new StatsCollector();
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    private static class DummyEvent implements CacheEvent {
        private final long ts;
        DummyEvent(long ts) { this.ts = ts; }
        @Override public long timestamp() { return ts; }
    }

    @Nested
    @DisplayName("Snapshot and Compaction Triggering")
    class TriggerTests {

        @Test
        void triggers_compaction_when_threshold_strategy_matches() {
            var strategy = new ThresholdSnapshotStrategy(3, 60_000L, fixedClock(T0));
            engine = new CompactionEngine(storage, strategy, bus, stats, fixedClock(T0), java.util.concurrent.Executors.newSingleThreadScheduledExecutor());

            storage.append(new DummyEvent(T0 - 100));
            storage.append(new DummyEvent(T0 - 50));
            storage.append(new DummyEvent(T0));

            assertThat(engine.getPendingEventCount()).isEqualTo(3);

            boolean triggered = engine.checkAndExecute();
            assertThat(triggered).isTrue();

            // After compaction, pending count reset and old events pruned
            assertThat(engine.getPendingEventCount()).isEqualTo(0);
            assertThat(storage.readAll()).isEmpty();
            assertThat(engine.getLastSnapshotTime()).isEqualTo(T0);
        }

        @Test
        void does_not_trigger_when_threshold_not_reached() {
            var strategy = new ThresholdSnapshotStrategy(5, 60_000L, fixedClock(T0));
            engine = new CompactionEngine(storage, strategy, bus, stats, fixedClock(T0), java.util.concurrent.Executors.newSingleThreadScheduledExecutor());

            storage.append(new DummyEvent(T0));
            storage.append(new DummyEvent(T0 + 1));

            boolean triggered = engine.checkAndExecute();
            assertThat(triggered).isFalse();
            assertThat(engine.getPendingEventCount()).isEqualTo(2);
            assertThat(storage.readAll()).hasSize(2);
        }

        @Test
        void force_snapshot_executes_immediately() {
            var strategy = new ThresholdSnapshotStrategy(100, 60_000L, fixedClock(T0));
            engine = new CompactionEngine(storage, strategy, bus, stats, fixedClock(T0), java.util.concurrent.Executors.newSingleThreadScheduledExecutor());

            storage.append(new DummyEvent(T0 - 20));
            storage.append(new DummyEvent(T0 - 10));

            engine.forceSnapshotAndCompaction();

            assertThat(engine.getPendingEventCount()).isEqualTo(0);
            assertThat(storage.readAll()).isEmpty();
            assertThat(engine.getLastSnapshotTime()).isEqualTo(T0);
        }
    }

    @Nested
    @DisplayName("Event Model and Stats Tracking")
    class ObservabilityTests {

        @Test
        void publishes_compaction_event_on_bus() {
            var strategy = new ThresholdSnapshotStrategy(2, 60_000L, fixedClock(T0));
            engine = new CompactionEngine(storage, strategy, bus, stats, fixedClock(T0), java.util.concurrent.Executors.newSingleThreadScheduledExecutor());

            List<CompactionEvent> received = new ArrayList<>();
            bus.subscribe(CompactionEvent.class, received::add);

            storage.append(new DummyEvent(T0 - 10));
            storage.append(new DummyEvent(T0));

            engine.checkAndExecute();

            assertThat(received).hasSize(1);
            assertThat(received.get(0).eventsPruned()).isEqualTo(2);
            assertThat(received.get(0).cutoffTimestamp()).isEqualTo(T0);
        }
    }

    @Nested
    @DisplayName("Background Execution Thread")
    class BackgroundSchedulerTests {

        @Test
        void background_task_executes_periodically() throws InterruptedException {
            var strategy = new ThresholdSnapshotStrategy(2, 60_000L);
            engine = new CompactionEngine(storage, strategy, bus, stats);

            CountDownLatch latch = new CountDownLatch(1);
            bus.subscribe(CompactionEvent.class, event -> latch.countDown());

            engine.start(10); // Check every 10ms

            storage.append(new DummyEvent(System.currentTimeMillis()));
            storage.append(new DummyEvent(System.currentTimeMillis()));

            boolean notified = latch.await(2, TimeUnit.SECONDS);
            assertThat(notified).isTrue();
            assertThat(engine.isRunning()).isTrue();
        }

        @Test
        void start_validates_period() {
            var strategy = new ThresholdSnapshotStrategy(5, 60_000L);
            engine = new CompactionEngine(storage, strategy, bus, stats);

            assertThatThrownBy(() -> engine.start(0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> engine.start(-5))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
