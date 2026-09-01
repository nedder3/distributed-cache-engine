package com.nedder3.cache.replication.conflict;

import com.nedder3.cache.core.clock.VectorClock;
import com.nedder3.cache.core.model.CacheEntry;
import com.nedder3.cache.core.model.CacheKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

class ConflictResolverTest {

    private ConflictResolver<String> resolver;
    private final CacheKey key = new CacheKey("default", "user:100");

    @BeforeEach
    void setUp() {
        resolver = new ConflictResolver<>();
    }

    @Test
    @DisplayName("Remote wins when its VectorClock happens-after local")
    void remoteWins_whenCausallyDominates() {
        VectorClock localClock = new VectorClock(Map.of("node1", 1L));
        VectorClock remoteClock = new VectorClock(Map.of("node1", 1L, "node2", 1L));

        CacheEntry<String> local = new CacheEntry<>(key, "Alice", localClock, 1000L, OptionalLong.empty());
        CacheEntry<String> remote = new CacheEntry<>(key, "Alice-Updated", remoteClock, 2000L, OptionalLong.empty());

        CacheEntry<String> winner = resolver.resolve(local, remote);

        assertThat(winner.value()).isEqualTo("Alice-Updated");
        assertThat(winner.version().counters()).containsEntry("node2", 1L);
    }

    @Test
    @DisplayName("Local wins when its VectorClock happens-after remote")
    void localWins_whenCausallyDominates() {
        VectorClock localClock = new VectorClock(Map.of("node1", 2L, "node2", 1L));
        VectorClock remoteClock = new VectorClock(Map.of("node1", 1L, "node2", 1L));

        CacheEntry<String> local = new CacheEntry<>(key, "LocalMaster", localClock, 1000L, OptionalLong.empty());
        CacheEntry<String> remote = new CacheEntry<>(key, "StaleRemote", remoteClock, 2000L, OptionalLong.empty());

        CacheEntry<String> winner = resolver.resolve(local, remote);

        assertThat(winner.value()).isEqualTo("LocalMaster");
    }

    @Test
    @DisplayName("LWW tie-breaker with clock merge when VectorClocks are concurrent")
    void lwwTieBreaker_whenConcurrentClocks() {
        VectorClock localClock = new VectorClock(Map.of("node1", 2L));
        VectorClock remoteClock = new VectorClock(Map.of("node2", 2L));

        CacheEntry<String> local = new CacheEntry<>(key, "ValA", localClock, 1000L, OptionalLong.empty());
        CacheEntry<String> remote = new CacheEntry<>(key, "ValB", remoteClock, 1500L, OptionalLong.empty());

        CacheEntry<String> winner = resolver.resolve(local, remote);

        assertThat(winner.value()).isEqualTo("ValB");
        assertThat(winner.version().counters())
                .containsEntry("node1", 2L)
                .containsEntry("node2", 2L);
    }
}
