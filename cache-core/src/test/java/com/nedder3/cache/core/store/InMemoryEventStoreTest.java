package com.nedder3.cache.core.store;

import com.nedder3.cache.core.event.CacheEvent;
import com.nedder3.cache.core.event.DeleteEvent;
import com.nedder3.cache.core.event.EvictEvent;
import com.nedder3.cache.core.event.PutEvent;
import com.nedder3.cache.core.clock.VectorClock;
import com.nedder3.cache.core.model.CacheKey;
import com.nedder3.cache.core.model.EvictionReason;
import com.nedder3.cache.core.port.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryEventStoreTest {

    private InMemoryEventStore store;

    private static final CacheKey KEY_A = new CacheKey("ns", "a");
    private static final CacheKey KEY_B = new CacheKey("ns", "b");
    private static final VectorClock CLOCK = new VectorClock(Map.of("node-1", 1L));

    @BeforeEach
    void setUp() {
        store = new InMemoryEventStore();
    }

    // --- append & readAll ---

    @Test
    void append_singleEvent_appearsInReadAll() {
        var event = new PutEvent(KEY_A, new byte[]{1}, CLOCK, 1000L);
        store.append(event);

        List<CacheEvent> all = store.readAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0)).isEqualTo(event);
    }

    @Test
    void append_multipleEvents_preservesInsertionOrder() {
        var e1 = new PutEvent(KEY_A, new byte[]{1}, CLOCK, 1000L);
        var e2 = new DeleteEvent(KEY_B, CLOCK, 2000L);
        var e3 = new EvictEvent(KEY_A, EvictionReason.TTL, 3000L);

        store.append(e1);
        store.append(e2);
        store.append(e3);

        assertThat(store.readAll()).containsExactly(e1, e2, e3);
    }

    @Test
    void readAll_emptyStore_returnsEmptyList() {
        assertThat(store.readAll()).isEmpty();
    }

    @Test
    void readAll_returnsUnmodifiableList() {
        store.append(new PutEvent(KEY_A, new byte[]{}, CLOCK, 100L));
        List<CacheEvent> snapshot = store.readAll();

        assertThatThrownBy(() -> snapshot.add(new PutEvent(KEY_B, new byte[]{}, CLOCK, 200L)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void append_nullEvent_throwsIllegalArgument() {
        assertThatThrownBy(() -> store.append(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- readAfter ---

    @Test
    void readAfter_filtersCorrectly() {
        store.append(new PutEvent(KEY_A, new byte[]{}, CLOCK, 100L));
        store.append(new PutEvent(KEY_B, new byte[]{}, CLOCK, 200L));
        store.append(new DeleteEvent(KEY_A, CLOCK, 300L));
        store.append(new PutEvent(KEY_A, new byte[]{}, CLOCK, 400L));

        List<CacheEvent> after200 = store.readAfter(200L);
        assertThat(after200).hasSize(2);
        assertThat(after200).allMatch(e -> e.timestamp() > 200L);
    }

    @Test
    void readAfter_inclusiveBoundary_excludesExactTimestamp() {
        store.append(new PutEvent(KEY_A, new byte[]{}, CLOCK, 100L));
        store.append(new PutEvent(KEY_A, new byte[]{}, CLOCK, 100L));

        List<CacheEvent> after100 = store.readAfter(100L);
        assertThat(after100).isEmpty();
    }

    @Test
    void readAfter_noEventsAfterTimestamp_returnsEmpty() {
        store.append(new PutEvent(KEY_A, new byte[]{}, CLOCK, 100L));

        assertThat(store.readAfter(999L)).isEmpty();
    }

    @Test
    void readAfter_emptyStore_returnsEmpty() {
        assertThat(store.readAfter(0L)).isEmpty();
    }

    @Test
    void readAfter_returnsUnmodifiableList() {
        store.append(new PutEvent(KEY_A, new byte[]{}, CLOCK, 500L));
        List<CacheEvent> result = store.readAfter(0L);

        assertThatThrownBy(() -> result.add(new PutEvent(KEY_B, new byte[]{}, CLOCK, 600L)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // --- snapshot ---

    @Test
    void createSnapshot_thenLoadLatest_returnsSameState() {
        Map<CacheKey, byte[]> state = Map.of(
                KEY_A, new byte[]{10, 20},
                KEY_B, new byte[]{30}
        );
        store.createSnapshot(state);

        Map<CacheKey, byte[]> loaded = store.loadLatestSnapshot();
        assertThat(loaded).containsExactlyInAnyOrderEntriesOf(state);
    }

    @Test
    void loadLatestSnapshot_noSnapshot_returnsEmptyMap() {
        assertThat(store.loadLatestSnapshot()).isEmpty();
    }

    @Test
    void createSnapshot_replacesPrevious() {
        store.createSnapshot(Map.of(KEY_A, new byte[]{1}));
        store.createSnapshot(Map.of(KEY_B, new byte[]{2}));

        Map<CacheKey, byte[]> loaded = store.loadLatestSnapshot();
        assertThat(loaded).containsOnlyKeys(KEY_B);
    }

    @Test
    void createSnapshot_storesDefensiveCopy() {
        Map<CacheKey, byte[]> original = new HashMap<>();
        original.put(KEY_A, new byte[]{1});
        store.createSnapshot(original);

        // Mutating the original after snapshot should not affect stored state
        original.put(KEY_B, new byte[]{2});
        assertThat(store.loadLatestSnapshot()).containsOnlyKeys(KEY_A);
    }

    @Test
    void createSnapshot_nullState_throwsIllegalArgument() {
        assertThatThrownBy(() -> store.createSnapshot(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- listeners ---

    @Test
    void addListener_notifiedOnAppend() {
        CopyOnWriteArrayList<StoragePort.StorageEvent> received = new CopyOnWriteArrayList<>();
        store.addListener(received::add);

        store.append(new PutEvent(KEY_A, new byte[]{}, CLOCK, 100L));

        assertThat(received).hasSize(1);
        assertThat(received.get(0).getType()).isEqualTo("APPEND");
    }

    @Test
    void removeListener_stopsNotification() {
        CopyOnWriteArrayList<StoragePort.StorageEvent> received = new CopyOnWriteArrayList<>();
        StoragePort.StorageEventListener listener = received::add;
        store.addListener(listener);
        store.removeListener(listener);

        store.append(new PutEvent(KEY_A, new byte[]{}, CLOCK, 100L));

        assertThat(received).isEmpty();
    }

    @Test
    void addListener_null_throwsIllegalArgument() {
        assertThatThrownBy(() -> store.addListener(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void multipleListeners_allNotified() {
        CopyOnWriteArrayList<String> log1 = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<String> log2 = new CopyOnWriteArrayList<>();
        store.addListener(e -> log1.add(e.getType()));
        store.addListener(e -> log2.add(e.getType()));

        store.append(new PutEvent(KEY_A, new byte[]{}, CLOCK, 100L));

        assertThat(log1).containsExactly("APPEND");
        assertThat(log2).containsExactly("APPEND");
    }

    // --- concurrent appends ---

    @Test
    void concurrentAppends_allEventsStoredExactlyOnce() throws Exception {
        int threadCount = 8;
        int eventsPerThread = 500;
        int totalExpected = threadCount * eventsPerThread;

        CyclicBarrier startGate = new CyclicBarrier(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger failures = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startGate.await(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    failures.incrementAndGet();
                    return;
                }
                for (int i = 0; i < eventsPerThread; i++) {
                    try {
                        var key = new CacheKey("ns", "t" + threadId + "-k" + i);
                        store.append(new PutEvent(key, new byte[]{(byte) i}, CLOCK, i));
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    }
                }
            });
        }

        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(failures.get()).isZero();

        List<CacheEvent> all = store.readAll();
        assertThat(all).hasSize(totalExpected);
    }

    @Test
    void concurrentAppends_readAllReturnsConsistentSnapshot() throws Exception {
        int threadCount = 4;
        int eventsPerThread = 200;

        CyclicBarrier startGate = new CyclicBarrier(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startGate.await(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    return;
                }
                for (int i = 0; i < eventsPerThread; i++) {
                    var key = new CacheKey("ns", "t" + threadId + "-k" + i);
                    store.append(new PutEvent(key, new byte[]{}, CLOCK, i));
                }
            });
        }

        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        // Two consecutive snapshots should be identical
        // (no writes happen between them)
        List<CacheEvent> snap1 = store.readAll();
        List<CacheEvent> snap2 = store.readAll();
        assertThat(snap2).isEqualTo(snap1);
    }

    @Test
    void concurrentAppends_readAfterFiltersCorrectly() throws Exception {
        int threadCount = 4;
        int eventsPerThread = 250;
        long cutoff = 100L;

        CyclicBarrier startGate = new CyclicBarrier(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startGate.await(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    return;
                }
                for (int i = 0; i < eventsPerThread; i++) {
                    var key = new CacheKey("ns", "t" + threadId + "-k" + i);
                    store.append(new PutEvent(key, new byte[]{}, CLOCK, i));
                }
            });
        }

        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        List<CacheEvent> after = store.readAfter(cutoff);
        assertThat(after).allMatch(e -> e.timestamp() > cutoff);
        // Every event with timestamp > cutoff must appear exactly once
        assertThat(after).hasSize(
                (int) store.readAll().stream().filter(e -> e.timestamp() > cutoff).count()
        );
    }

    @Test
    void concurrentAppends_noEventsLostUnderContention() throws Exception {
        int threadCount = 10;
        int eventsPerThread = 1000;
        int totalExpected = threadCount * eventsPerThread;

        CopyOnWriteArrayList<StoragePort.StorageEvent> listenerEvents = new CopyOnWriteArrayList<>();
        store.addListener(listenerEvents::add);

        CyclicBarrier startGate = new CyclicBarrier(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startGate.await(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    return;
                }
                for (int i = 0; i < eventsPerThread; i++) {
                    var key = new CacheKey("ns", "t" + threadId + "-k" + i);
                    store.append(new PutEvent(key, new byte[]{}, CLOCK, i));
                }
            });
        }

        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(store.readAll()).hasSize(totalExpected);
        assertThat(listenerEvents).hasSize(totalExpected);
    }

    // --- implements StoragePort ---

    @Test
    void implementsStoragePort() {
        assertThat(store).isInstanceOf(StoragePort.class);
    }
}
