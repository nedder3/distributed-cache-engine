package com.nedder3.cache.store.serializer;

import com.nedder3.cache.core.port.SerializerPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JavaNativeSerializer (TDD RED / Task 2.1)")
class JavaNativeSerializerTest {

    private JavaNativeSerializer<Object> serializer;

    static record Person(String name, int age) implements Serializable {}
    static record NonSerializablePayload(String secret) {}

    @BeforeEach
    void setUp() {
        serializer = new JavaNativeSerializer<>();
    }

    @Nested
    @DisplayName("Basic Types & Custom Record Serialization")
    class BasicTypesSerialization {

        @Test
        @DisplayName("serializes and deserializes String correctly")
        void stringRoundTrip() {
            String original = "Hello Distributed Cache Engine!";
            byte[] bytes = serializer.serialize(original);

            assertThat(bytes).isNotNull().isNotEmpty();
            Object deserialized = serializer.deserialize(bytes);
            assertThat(deserialized).isEqualTo(original);
        }

        @Test
        @DisplayName("serializes and deserializes Integer and numeric primitives")
        void numberRoundTrip() {
            Integer original = 42;
            byte[] bytes = serializer.serialize(original);

            assertThat(bytes).isNotNull().isNotEmpty();
            Object deserialized = serializer.deserialize(bytes);
            assertThat(deserialized).isEqualTo(original);
        }

        @Test
        @DisplayName("serializes and deserializes Java Record implementing Serializable")
        void recordRoundTrip() {
            Person person = new Person("Nedder", 30);
            byte[] bytes = serializer.serialize(person);

            assertThat(bytes).isNotNull().isNotEmpty();
            Object deserialized = serializer.deserialize(bytes);
            assertThat(deserialized).isEqualTo(person);
        }
    }

    @Nested
    @DisplayName("Edge Cases & Exception Handling")
    class EdgeCasesAndExceptions {

        @Test
        @DisplayName("serializes null value to null or empty marker")
        void handlesNullValue() {
            byte[] bytes = serializer.serialize(null);
            assertThat(bytes).isNotNull();
            assertThat(serializer.deserialize(bytes)).isNull();
        }

        @Test
        @DisplayName("throws IllegalArgumentException or SerializationException on corrupted bytes")
        void corruptedBytesThrows() {
            byte[] corrupted = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
            assertThatThrownBy(() -> serializer.deserialize(corrupted))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("throws RuntimeException when object is not Serializable")
        void nonSerializableObjectThrows() {
            NonSerializablePayload payload = new NonSerializablePayload("no-serial");
            assertThatThrownBy(() -> serializer.serialize(payload))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Nested
    @DisplayName("Serialization Listeners & Event Notification")
    class EventNotification {

        @Test
        @DisplayName("notifies listeners on serialize and deserialize")
        void notifiesListeners() {
            List<SerializerPort.SerializationEvent> events = new ArrayList<>();
            SerializerPort.SerializationEventListener listener = events::add;

            serializer.addListener(listener);

            byte[] bytes = serializer.serialize("test-event");
            serializer.deserialize(bytes);

            assertThat(events).hasSize(2);
            assertThat(events.get(0).getType()).isEqualTo("SERIALIZE");
            assertThat(events.get(1).getType()).isEqualTo("DESERIALIZE");

            serializer.removeListener(listener);
            serializer.serialize("after-removal");
            assertThat(events).hasSize(2);
        }
    }

    @Nested
    @DisplayName("Concurrency & Thread Safety")
    class ConcurrencyTests {

        @Test
        @DisplayName("handles concurrent serialization from multiple threads")
        void concurrentSerialization() throws InterruptedException {
            int threads = 10;
            int iterationsPerThread = 50;
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            CountDownLatch latch = new CountDownLatch(threads);
            AtomicInteger successCount = new AtomicInteger(0);

            for (int i = 0; i < threads; i++) {
                final int threadId = i;
                executor.submit(() -> {
                    try {
                        for (int j = 0; j < iterationsPerThread; j++) {
                            Person p = new Person("User-" + threadId, j);
                            byte[] b = serializer.serialize(p);
                            Object res = serializer.deserialize(b);
                            if (Objects.equals(p, res)) {
                                successCount.incrementAndGet();
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean finished = latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(finished).isTrue();
            assertThat(successCount.get()).isEqualTo(threads * iterationsPerThread);
        }
    }
}
