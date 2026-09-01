package com.nedder3.cache.store.engine;

import com.nedder3.cache.core.event.DeleteEvent;
import com.nedder3.cache.core.event.PutEvent;
import com.nedder3.cache.core.model.CacheEntry;
import com.nedder3.cache.core.model.CacheKey;
import com.nedder3.cache.store.serializer.JavaNativeSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HybridDiskStorage (TDD / Task 2.3)")
class HybridDiskStorageTest {

    @TempDir
    Path tempDir;

    private HybridDiskStorage<String> storage;

    @BeforeEach
    void setUp() throws IOException {
        storage = new HybridDiskStorage<>(tempDir, 1024 * 1024, new JavaNativeSerializer<>());
    }

    @AfterEach
    void tearDown() throws IOException {
        if (storage != null) {
            storage.close();
        }
    }

    @Nested
    @DisplayName("Basic CRUD Operations")
    class BasicCrudTests {

        @Test
        @DisplayName("writes and reads cache entries accurately")
        void writeAndRead() {
            CacheKey key = new CacheKey("ns1", "user:100");
            storage.write(key, "Alice", OptionalLong.empty());

            Optional<CacheEntry<String>> result = storage.read(key);
            assertThat(result).isPresent();
            assertThat(result.get().value()).isEqualTo("Alice");
            assertThat(storage.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("deletes cache entries properly")
        void deleteEntry() {
            CacheKey key = new CacheKey("ns1", "user:100");
            storage.write(key, "Alice", OptionalLong.empty());

            boolean deleted = storage.delete(key);
            assertThat(deleted).isTrue();
            assertThat(storage.read(key)).isEmpty();
            assertThat(storage.size()).isEqualTo(0);
        }

        @Test
        @DisplayName("respects TTL expiration during read")
        void respectsTtlExpiration() throws InterruptedException {
            CacheKey key = new CacheKey("ns1", "temp-key");
            long expiresAt = System.currentTimeMillis() + 50;
            storage.write(key, "temp-val", OptionalLong.of(expiresAt));

            assertThat(storage.read(key)).isPresent();
            Thread.sleep(70);
            assertThat(storage.read(key)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Crash Recovery & State Rebuilding")
    class CrashRecoveryTests {

        @Test
        @DisplayName("recovers state from WAL upon reopen")
        void recoversFromWalOnReopen() throws IOException {
            CacheKey k1 = new CacheKey("ns", "k1");
            CacheKey k2 = new CacheKey("ns", "k2");
            storage.write(k1, "v1", OptionalLong.empty());
            storage.write(k2, "v2", OptionalLong.empty());
            storage.delete(k1);
            storage.close();

            // Reopen storage on same directory
            HybridDiskStorage<String> reopened = new HybridDiskStorage<>(tempDir, 1024 * 1024, new JavaNativeSerializer<>());
            assertThat(reopened.read(k1)).isEmpty();
            assertThat(reopened.read(k2)).isPresent();
            assertThat(reopened.read(k2).get().value()).isEqualTo("v2");
            reopened.close();
        }

        @Test
        @DisplayName("creates snapshot and recovers cleanly from snapshot + WAL delta")
        void recoversFromSnapshotAndWalDelta() throws IOException {
            CacheKey k1 = new CacheKey("ns", "k1");
            CacheKey k2 = new CacheKey("ns", "k2");
            storage.write(k1, "snap-val1", OptionalLong.empty());

            // Save snapshot
            JavaNativeSerializer<String> ser = new JavaNativeSerializer<>();
            storage.createSnapshot(Map.of(k1, ser.serialize("snap-val1")));

            // Post snapshot modification in WAL
            storage.write(k2, "wal-val2", OptionalLong.empty());
            storage.close();

            // Reopen
            HybridDiskStorage<String> reopened = new HybridDiskStorage<>(tempDir, 1024 * 1024, new JavaNativeSerializer<>());
            assertThat(reopened.read(k1)).isPresent();
            assertThat(reopened.read(k1).get().value()).isEqualTo("snap-val1");
            assertThat(reopened.read(k2)).isPresent();
            assertThat(reopened.read(k2).get().value()).isEqualTo("wal-val2");
            reopened.close();
        }
    }

    @Nested
    @DisplayName("Listeners and Notifications")
    class ListenerTests {

        @Test
        @DisplayName("notifies listeners on write and delete")
        void notifiesListeners() {
            AtomicInteger writeCount = new AtomicInteger(0);
            AtomicInteger deleteCount = new AtomicInteger(0);

            storage.addListener(event -> {
                if ("WRITE".equals(event.getType())) writeCount.incrementAndGet();
                if ("DELETE".equals(event.getType())) deleteCount.incrementAndGet();
            });

            CacheKey key = new CacheKey("ns", "k");
            storage.write(key, "val", OptionalLong.empty());
            storage.delete(key);

            assertThat(writeCount.get()).isEqualTo(1);
            assertThat(deleteCount.get()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Concurrent Workload")
    class ConcurrencyTests {

        @Test
        @DisplayName("handles concurrent reads and writes safely")
        void handlesConcurrentReadWrites() throws Exception {
            int threads = 8;
            int opsPerThread = 100;
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            CountDownLatch latch = new CountDownLatch(threads);

            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < opsPerThread; i++) {
                            CacheKey k = new CacheKey("ns-" + threadId, "k-" + i);
                            storage.write(k, "val-" + i, OptionalLong.empty());
                            storage.read(k);
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean ok = latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(ok).isTrue();
            assertThat(storage.size()).isEqualTo(threads * opsPerThread);
        }
    }
}
