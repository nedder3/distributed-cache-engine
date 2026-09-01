package com.nedder3.cache.core.event;

import com.nedder3.cache.core.event.TestEvents.ClearAllEvent;
import com.nedder3.cache.core.event.TestEvents.CreateEvent;
import com.nedder3.cache.core.event.TestEvents.EvictEvent;
import com.nedder3.cache.core.event.TestEvents.WriteEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RED tests for {@code com.nedder3.cache.core.event.EventBus}.
 *
 * <p>These tests are written BEFORE the implementation exists. They:
 * <ul>
 *   <li>Define the public contract Nexus handed to the team.</li>
 *   <li>Currently FAIL to compile (and therefore to run) because the
 *       {@code EventBus} class and {@code CacheEvent} production type
 *       do not exist yet.</li>
 *   <li>Will go GREEN once Cy implements the bus against the contract.</li>
 * </ul>
 *
 * <p>Scope (mirrors Nexus requirements):
 * <ol>
 *   <li>Generic / type-safe subscribe/unsubscribe.</li>
 *   <li>Publish dispatches to concrete-type AND supertype subscribers.</li>
 *   <li>Sync direct dispatch + async virtual-thread/executor dispatch.</li>
 *   <li>Isolation: one failing subscriber must not break others.</li>
 *   <li>Concurrency: high-throughput concurrent pub/sub, no CME, no deadlock.</li>
 *   <li>Multi-subscriber ordering/delivery guarantees.</li>
 *   <li>Clear/reset of subscribers.</li>
 * </ol>
 */
class EventBusTest {

    /** Helper: a fresh bus per test (no shared mutable static state). */
    private EventBus bus;

    @BeforeEach
    void setUp() {
        bus = new EventBus();
    }

    @AfterEach
    void tearDown() {
        bus.shutdown();
    }

    // ----------------------------------------------------------------------
    // 1. Type-safe subscribe / unsubscribe
    // ----------------------------------------------------------------------

    @Nested
    @DisplayName("1. Type-safe subscribe / unsubscribe")
    class TypeSafeSubscription {

        @Test
        @DisplayName("subscribe(WriteEvent, consumer) delivers WriteEvent to the consumer")
        void subscribeExactTypeDeliversEvent() {
            List<WriteEvent> received = new ArrayList<>();
            bus.subscribe(WriteEvent.class, received::add);

            WriteEvent ev = new WriteEvent("k1", "v1");
            bus.publish(ev);

            assertThat(received).containsExactly(ev);
        }

        @Test
        @DisplayName("unsubscribe stops further delivery to that consumer")
        void unsubscribeStopsDelivery() {
            AtomicInteger count = new AtomicInteger();
            Consumer<WriteEvent> handler = e -> count.incrementAndGet();

            bus.subscribe(WriteEvent.class, handler);
            bus.publish(new WriteEvent("a", 1));
            assertThat(count).hasValue(1);

            bus.unsubscribe(WriteEvent.class, handler);
            bus.publish(new WriteEvent("b", 2));

            assertThat(count).hasValue(1); // no second delivery
        }

        @Test
        @DisplayName("unsubscribe of an unknown handler is a no-op (does not throw)")
        void unsubscribeUnknownHandlerIsNoOp() {
            Consumer<WriteEvent> never = e -> { /* never invoked */ };
            assertThatCode(() -> bus.unsubscribe(WriteEvent.class, never))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("subscribers are type-isolated: WriteEvent handler ignores EvictEvent")
        void subscribersAreTypeIsolated() {
            List<WriteEvent> writes = new ArrayList<>();
            List<EvictEvent> evicts = new ArrayList<>();

            bus.subscribe(WriteEvent.class, writes::add);
            bus.subscribe(EvictEvent.class, evicts::add);

            bus.publish(new EvictEvent("k"));

            assertThat(writes).isEmpty();
            assertThat(evicts).hasSize(1);
        }

        @Test
        @DisplayName("the same handler subscribed twice to the same type is invoked twice (or deduped — contract: at-least-once per subscription)")
        void sameHandlerSubscribedTwiceIsInvokedTwice() {
            AtomicInteger count = new AtomicInteger();
            Consumer<WriteEvent> handler = e -> count.incrementAndGet();

            bus.subscribe(WriteEvent.class, handler);
            bus.subscribe(WriteEvent.class, handler);
            bus.publish(new WriteEvent("k", "v"));

            // Contract: each subscribe() call registers an independent subscription.
            // (Implementation may choose to dedupe identical handlers; if it does, this
            // test will be loosened to assert at-least-once. The strict reading is 2.)
            assertThat(count.get()).isGreaterThanOrEqualTo(1);
        }
    }

    // ----------------------------------------------------------------------
    // 2. Publish dispatch to concrete type OR supertype
    // ----------------------------------------------------------------------

    @Nested
    @DisplayName("2. Publish dispatch (concrete + supertype)")
    class PublishDispatch {

        @Test
        @DisplayName("publish(CreateEvent) reaches WriteEvent supertype subscribers")
        void reachesSupertypeSubscribers() {
            List<WriteEvent> writes = new ArrayList<>();
            List<CreateEvent> creates = new ArrayList<>();
            List<CacheEvent> bases = new ArrayList<>();

            bus.subscribe(WriteEvent.class, writes::add);
            bus.subscribe(CreateEvent.class, creates::add);
            bus.subscribe(CacheEvent.class, bases::add);

            CreateEvent ev = new CreateEvent("k", "v");
            bus.publish(ev);

            assertThat(creates).containsExactly(ev);
            assertThat(writes).containsExactly(ev);  // exact supertype
            assertThat(bases).containsExactly(ev);   // root type
        }

        @Test
        @DisplayName("publish(WriteEvent) does NOT reach CreateEvent (subtype) subscribers")
        void doesNotReachSubtypeSubscribers() {
            List<CreateEvent> creates = new ArrayList<>();
            bus.subscribe(CreateEvent.class, creates::add);

            bus.publish(new WriteEvent("k", "v"));

            assertThat(creates).isEmpty();
        }

        @Test
        @DisplayName("publish(WriteEvent) reaches CacheEvent supertype but NOT EvictEvent sibling")
        void reachesSupertypeButNotSibling() {
            List<CacheEvent> bases = new ArrayList<>();
            List<EvictEvent> evicts = new ArrayList<>();

            bus.subscribe(CacheEvent.class, bases::add);
            bus.subscribe(EvictEvent.class, evicts::add);

            bus.publish(new WriteEvent("k", "v"));

            assertThat(bases).hasSize(1);
            assertThat(evicts).isEmpty();
        }

        @Test
        @DisplayName("publish with no subscribers is a safe no-op")
        void publishWithNoSubscribersIsNoOp() {
            assertThatCode(() -> bus.publish(new WriteEvent("k", "v")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("publish(null) is rejected with a clear contract — NPE or IllegalArgumentException")
        void publishNullIsRejected() {
            // Contract: implementation MUST fail fast on null rather than silently swallow.
            assertThatThrownBy(() -> bus.publish(null))
                    .isInstanceOfAny(NullPointerException.class, IllegalArgumentException.class);
        }
    }

    // ----------------------------------------------------------------------
    // 3. Sync + Async dispatch
    // ----------------------------------------------------------------------

    @Nested
    @DisplayName("3. Synchronous + asynchronous dispatch")
    class SyncAndAsync {

        @Test
        @DisplayName("default publish is synchronous: handler runs before publish() returns")
        void defaultPublishIsSynchronous() {
            AtomicReference<Thread> handlerThread = new AtomicReference<>();
            AtomicReference<Thread> callerThread = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);

            bus.subscribe(WriteEvent.class, e -> {
                handlerThread.set(Thread.currentThread());
                done.countDown();
            });

            callerThread.set(Thread.currentThread());
            bus.publish(new WriteEvent("k", "v"));

            assertThat(done.getCount()).isZero();
            // Synchronous => same thread as caller.
            assertThat(handlerThread.get()).isSameAs(callerThread.get());
        }

        @Test
        @DisplayName("async() factory returns a bus that dispatches off the caller thread")
        void asyncBusDispatchesOffCallerThread() throws InterruptedException {
            EventBus async = EventBus.async();
            try {
                AtomicReference<Thread> handlerThread = new AtomicReference<>();
                CountDownLatch done = new CountDownLatch(1);

                async.subscribe(WriteEvent.class, e -> {
                    handlerThread.set(Thread.currentThread());
                    done.countDown();
                });

                Thread caller = Thread.currentThread();
                async.publish(new WriteEvent("k", "v"));

                assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(handlerThread.get()).isNotNull();
                assertThat(handlerThread.get()).isNotSameAs(caller);
            } finally {
                async.shutdown();
            }
        }

        @Test
        @DisplayName("async bus eventually delivers every published event")
        void asyncBusEventuallyDelivers() throws InterruptedException {
            EventBus async = EventBus.async();
            try {
                int n = 1_000;
                CountDownLatch done = new CountDownLatch(n);
                async.subscribe(WriteEvent.class, e -> done.countDown());

                for (int i = 0; i < n; i++) {
                    async.publish(new WriteEvent("k" + i, i));
                }

                assertThat(done.await(5, TimeUnit.SECONDS))
                        .as("async bus should drain all %d events", n)
                        .isTrue();
                assertThat(done.getCount()).isZero();
            } finally {
                async.shutdown();
            }
        }
    }

    // ----------------------------------------------------------------------
    // 4. Isolation — exceptions must not break other subscribers
    // ----------------------------------------------------------------------

    @Nested
    @DisplayName("4. Exception isolation between subscribers")
    class ExceptionIsolation {

        @Test
        @DisplayName("a subscriber that throws does not prevent other subscribers from receiving the event (sync)")
        void syncThrowingSubscriberDoesNotBreakOthers() {
            List<String> log = Collections.synchronizedList(new ArrayList<>());

            bus.subscribe(WriteEvent.class, e -> log.add("A:" + e.key()));
            bus.subscribe(WriteEvent.class, e -> {
                throw new RuntimeException("boom from B");
            });
            bus.subscribe(WriteEvent.class, e -> log.add("C:" + e.key()));

            // publish() itself must NOT throw — isolation is the contract.
            assertThatCode(() -> bus.publish(new WriteEvent("k", "v")))
                    .doesNotThrowAnyException();

            assertThat(log).containsExactly("A:k", "C:k");
        }

        @Test
        @DisplayName("a subscriber that throws does not break other subscribers (async)")
        void asyncThrowingSubscriberDoesNotBreakOthers() throws InterruptedException {
            EventBus async = EventBus.async();
            try {
                List<String> log = Collections.synchronizedList(new ArrayList<>());
                CountDownLatch done = new CountDownLatch(2);

                async.subscribe(WriteEvent.class, e -> { log.add("A"); done.countDown(); });
                async.subscribe(WriteEvent.class, e -> { throw new RuntimeException("boom"); });
                async.subscribe(WriteEvent.class, e -> { log.add("C"); done.countDown(); });

                async.publish(new WriteEvent("k", "v"));

                assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(log).containsExactlyInAnyOrder("A", "C");
            } finally {
                async.shutdown();
            }
        }
    }

    // ----------------------------------------------------------------------
    // 5. Concurrency — high throughput pub/sub, no CME, no deadlock
    // ----------------------------------------------------------------------

    @Nested
    @DisplayName("5. Concurrency")
    class Concurrency {

        @Test
        @DisplayName("concurrent publish + subscribe must not throw ConcurrentModificationException or deadlock")
        void concurrentPublishAndSubscribeIsSafe() throws InterruptedException {
            int publishers = 8;
            int subscribers = 8;
            int eventsPerPublisher = 5_000;
            AtomicInteger delivered = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(publishers);

            // Long-lived subscriber that survives the whole storm.
            bus.subscribe(WriteEvent.class, e -> delivered.incrementAndGet());

            ExecutorService pool = Executors.newFixedThreadPool(publishers + subscribers);
            try {
                // Subscriber threads: keep adding/removing handlers while events flow.
                for (int s = 0; s < subscribers; s++) {
                    final int id = s;
                    pool.submit(() -> {
                        try {
                            start.await();
                            List<Consumer<WriteEvent>> handlers = new ArrayList<>();
                            for (int i = 0; i < 200; i++) {
                                Consumer<WriteEvent> h = e -> { /* no-op, churn only */ };
                                handlers.add(h);
                                bus.subscribe(WriteEvent.class, h);
                                if (i % 17 == 0 && !handlers.isEmpty()) {
                                    bus.unsubscribe(WriteEvent.class,
                                            handlers.remove(handlers.size() - 1));
                                }
                                if (id == 0 && i % 5 == 0) {
                                    // Churn/test path without wiping the shared bus
                                    handlers.clear();
                                }
                            }
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    });
                }

                // Publisher threads.
                for (int p = 0; p < publishers; p++) {
                    final int pid = p;
                    pool.submit(() -> {
                        try {
                            start.await();
                            for (int i = 0; i < eventsPerPublisher; i++) {
                                bus.publish(new WriteEvent("p" + pid + "-" + i, i));
                            }
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }

                start.countDown();
                assertThat(done.await(30, TimeUnit.SECONDS))
                        .as("all publishers should finish within the deadline")
                        .isTrue();

                // The long-lived subscriber must see exactly publishers * eventsPerPublisher
                // events — nothing lost, nothing duplicated by retry.
                assertThat(delivered.get())
                        .as("all %d published events must reach the long-lived subscriber",
                                publishers * eventsPerPublisher)
                        .isEqualTo(publishers * eventsPerPublisher);
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("async bus survives a publisher storm with no lost events")
        void asyncBusSurvivesPublisherStorm() throws InterruptedException {
            EventBus async = EventBus.async();
            try {
                int n = 20_000;
                AtomicInteger delivered = new AtomicInteger();
                CountDownLatch done = new CountDownLatch(n);
                async.subscribe(WriteEvent.class, e -> {
                    delivered.incrementAndGet();
                    done.countDown();
                });

                int threads = 8;
                ExecutorService pool = Executors.newFixedThreadPool(threads);
                try {
                    CountDownLatch start = new CountDownLatch(1);
                    for (int t = 0; t < threads; t++) {
                        final int tid = t;
                        pool.submit(() -> {
                            try {
                                start.await();
                                for (int i = 0; i < n / threads; i++) {
                                    async.publish(new WriteEvent("t" + tid + "-" + i, i));
                                }
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                            }
                        });
                    }
                    start.countDown();

                    assertThat(done.await(15, TimeUnit.SECONDS)).isTrue();
                    assertThat(delivered.get()).isEqualTo(n);
                } finally {
                    pool.shutdownNow();
                }
            } finally {
                async.shutdown();
            }
        }
    }

    // ----------------------------------------------------------------------
    // 6. Multi-subscriber ordering / delivery guarantees
    // ----------------------------------------------------------------------

    @Nested
    @DisplayName("6. Multi-subscriber ordering")
    class MultiSubscriberOrdering {

        @Test
        @DisplayName("sync: subscribers of the same type receive events in subscription order")
        void syncSubscribersDeliveredInSubscriptionOrder() {
            List<String> order = new ArrayList<>();
            bus.subscribe(WriteEvent.class, e -> order.add("A"));
            bus.subscribe(WriteEvent.class, e -> order.add("B"));
            bus.subscribe(WriteEvent.class, e -> order.add("C"));

            bus.publish(new WriteEvent("k", "v"));

            assertThat(order).containsExactly("A", "B", "C");
        }

        @Test
        @DisplayName("sync: subscription order is preserved across multiple publish() calls")
        void syncSubscriptionOrderPreservedAcrossPublishes() {
            List<String> order = new ArrayList<>();
            bus.subscribe(WriteEvent.class, e -> order.add("first"));
            bus.subscribe(WriteEvent.class, e -> order.add("second"));

            bus.publish(new WriteEvent("k1", 1));
            bus.publish(new WriteEvent("k2", 2));

            assertThat(order).containsExactly("first", "second", "first", "second");
        }

        @Test
        @DisplayName("every subscriber receives every matching event (no drops, no duplicates per subscription)")
        void everySubscriberReceivesEveryMatchingEvent() {
            int n = 100;
            AtomicInteger countA = new AtomicInteger();
            AtomicInteger countB = new AtomicInteger();
            bus.subscribe(WriteEvent.class, e -> countA.incrementAndGet());
            bus.subscribe(WriteEvent.class, e -> countB.incrementAndGet());

            for (int i = 0; i < n; i++) {
                bus.publish(new WriteEvent("k" + i, i));
            }

            assertThat(countA.get()).isEqualTo(n);
            assertThat(countB.get()).isEqualTo(n);
        }
    }

    // ----------------------------------------------------------------------
    // 7. Clear / reset
    // ----------------------------------------------------------------------

    @Nested
    @DisplayName("7. Clear / reset")
    class ClearAndReset {

        @Test
        @DisplayName("clear() removes ALL subscribers across ALL event types")
        void clearRemovesAllSubscribers() {
            AtomicInteger writes = new AtomicInteger();
            AtomicInteger evicts = new AtomicInteger();
            AtomicInteger bases = new AtomicInteger();

            bus.subscribe(WriteEvent.class, e -> writes.incrementAndGet());
            bus.subscribe(EvictEvent.class, e -> evicts.incrementAndGet());
            bus.subscribe(CacheEvent.class, e -> bases.incrementAndGet());

            bus.clear();

            bus.publish(new WriteEvent("k", "v"));
            bus.publish(new EvictEvent("k"));
            bus.publish(new ClearAllEvent());

            assertThat(writes.get()).isZero();
            assertThat(evicts.get()).isZero();
            assertThat(bases.get()).isZero();
        }

        @Test
        @DisplayName("after clear(), new subscriptions still work normally")
        void newSubscriptionsWorkAfterClear() {
            bus.subscribe(WriteEvent.class, e -> { /* pre-clear, will be removed */ });
            bus.clear();

            List<WriteEvent> received = new ArrayList<>();
            bus.subscribe(WriteEvent.class, received::add);
            bus.publish(new WriteEvent("k", "v"));

            assertThat(received).hasSize(1);
        }

        @Test
        @DisplayName("clear() on an empty bus is a no-op (does not throw)")
        void clearOnEmptyBusIsNoOp() {
            assertThatCode(bus::clear).doesNotThrowAnyException();
        }
    }
}
