package com.nedder3.cache.store.wal;

import com.nedder3.cache.core.event.CacheEvent;
import com.nedder3.cache.core.event.PutEvent;
import com.nedder3.cache.core.event.DeleteEvent;
import com.nedder3.cache.core.event.EvictEvent;
import com.nedder3.cache.core.model.CacheKey;
import com.nedder3.cache.core.model.EvictionReason;
import com.nedder3.cache.core.clock.VectorClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WriteAheadLog (TDD / Task 2.2)")
class WriteAheadLogTest {

    @TempDir
    Path tempDir;

    private WriteAheadLog wal;

    @BeforeEach
    void setUp() throws IOException {
        wal = new WriteAheadLog(tempDir, 1024 * 1024); // 1 MB segment size
    }

    @AfterEach
    void tearDown() throws IOException {
        if (wal != null) {
            wal.close();
        }
    }

    @Nested
    @DisplayName("Append & Sequential Replay")
    class AppendAndReplayTests {

        @Test
        @DisplayName("appends PutEvent and replays successfully")
        void appendAndReplayPutEvent() throws IOException {
            CacheKey key = new CacheKey("ns", "k1");
            VectorClock clock = new VectorClock(Map.of("node1", 1L));
            byte[] valBytes = "value1".getBytes(StandardCharsets.UTF_8);
            PutEvent event = new PutEvent(key, valBytes, clock, System.currentTimeMillis());

            wal.append(event);
            wal.flush();

            List<CacheEvent> replayed = wal.replayAll();
            assertThat(replayed).hasSize(1);
            assertThat(replayed.get(0)).isInstanceOf(PutEvent.class);
            PutEvent put = (PutEvent) replayed.get(0);
            assertThat(put.key()).isEqualTo(key);
            assertThat(new String(put.serializedValue(), StandardCharsets.UTF_8)).isEqualTo("value1");
        }

        @Test
        @DisplayName("appends mixed events (Put, Delete, Evict) in order")
        void appendMixedEventsInOrder() throws IOException {
            CacheKey k1 = new CacheKey("ns", "k1");
            VectorClock c1 = new VectorClock(Map.of("node1", 1L));
            VectorClock c2 = new VectorClock(Map.of("node1", 2L));

            wal.append(new PutEvent(k1, "v1".getBytes(StandardCharsets.UTF_8), c1, 1000L));
            wal.append(new DeleteEvent(k1, c2, 2000L));
            wal.append(new EvictEvent(k1, EvictionReason.CAPACITY, 3000L));
            wal.flush();

            List<CacheEvent> replayed = wal.replayAll();
            assertThat(replayed).hasSize(3);
            assertThat(replayed.get(0)).isInstanceOf(PutEvent.class);
            assertThat(replayed.get(1)).isInstanceOf(DeleteEvent.class);
            assertThat(replayed.get(2)).isInstanceOf(EvictEvent.class);
        }
    }

    @Nested
    @DisplayName("Segment Rolling & Multi-file Retention")
    class SegmentRollingTests {

        @Test
        @DisplayName("rolls segment when current file exceeds maxSegmentSizeBytes")
        void rollsSegmentOnOverflow() throws IOException {
            wal.close();
            // Tiny segment size: 100 bytes
            wal = new WriteAheadLog(tempDir, 100);

            for (int i = 0; i < 20; i++) {
                CacheKey key = new CacheKey("ns", "key-" + i);
                wal.append(new PutEvent(key, ("large-payload-string-padding-" + i).getBytes(StandardCharsets.UTF_8), new VectorClock(Map.of("node1", (long) i)), System.currentTimeMillis()));
            }
            wal.flush();

            File[] segmentFiles = tempDir.toFile().listFiles((dir, name) -> name.startsWith("wal-") && name.endsWith(".log"));
            assertThat(segmentFiles).isNotNull();
            assertThat(segmentFiles.length).isGreaterThan(1);

            List<CacheEvent> replayed = wal.replayAll();
            assertThat(replayed).hasSize(20);
        }
    }

    @Nested
    @DisplayName("Crash Recovery & Corrupted Record Truncation")
    class CrashRecoveryTests {

        @Test
        @DisplayName("recovers valid records and ignores / truncates corrupted trailing bytes safely")
        void recoversFromCorruptedTrailingBytes() throws IOException {
            wal.append(new PutEvent(new CacheKey("ns", "k1"), "val1".getBytes(StandardCharsets.UTF_8), new VectorClock(Map.of("n1", 1L)), 100L));
            wal.append(new PutEvent(new CacheKey("ns", "k2"), "val2".getBytes(StandardCharsets.UTF_8), new VectorClock(Map.of("n1", 2L)), 200L));
            wal.flush();
            wal.close();

            // Corrupt file by appending garbage bytes
            File[] files = tempDir.toFile().listFiles((dir, name) -> name.startsWith("wal-") && name.endsWith(".log"));
            assertThat(files).isNotEmpty();
            try (RandomAccessFile raf = new RandomAccessFile(files[0], "rw")) {
                raf.seek(raf.length());
                raf.write(new byte[]{ (byte) 0xFF, (byte) 0xAA, (byte) 0xBB }); // incomplete garbage
            }

            // Reopen WAL
            WriteAheadLog recoveredWal = new WriteAheadLog(tempDir, 1024 * 1024);
            List<CacheEvent> events = recoveredWal.replayAll();
            assertThat(events).hasSize(2);
            recoveredWal.close();
        }
    }

    @Nested
    @DisplayName("Thread Safety & Concurrency")
    class ConcurrencyTests {

        @Test
        @DisplayName("supports concurrent appends without corruption")
        void concurrentAppends() throws Exception {
            int threads = 8;
            int opsPerThread = 100;
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            CountDownLatch latch = new CountDownLatch(threads);
            AtomicInteger totalAppended = new AtomicInteger(0);

            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < opsPerThread; i++) {
                            CacheKey key = new CacheKey("t-" + threadId, "k-" + i);
                            wal.append(new PutEvent(key, ("data-" + i).getBytes(StandardCharsets.UTF_8), new VectorClock(Map.of("n" + threadId, (long) i)), System.currentTimeMillis()));
                            totalAppended.incrementAndGet();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean ok = latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();
            wal.flush();

            assertThat(ok).isTrue();
            assertThat(totalAppended.get()).isEqualTo(threads * opsPerThread);

            List<CacheEvent> replayed = wal.replayAll();
            assertThat(replayed).hasSize(threads * opsPerThread);
        }
    }
}
