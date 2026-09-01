package com.nedder3.cache.replication.conflict;

import com.nedder3.cache.core.clock.Causality;
import com.nedder3.cache.core.clock.VectorClock;
import com.nedder3.cache.core.model.CacheEntry;

import java.util.Objects;

/**
 * Resolves concurrent modification conflicts using Vector Clocks and Last-Write-Wins (LWW) tie-breaking.
 */
public class ConflictResolver<V> {

    public enum ResolutionStrategy {
        VECTOR_CLOCK_LWW,
        LOCAL_WINS,
        REMOTE_WINS
    }

    private final ResolutionStrategy strategy;

    public ConflictResolver() {
        this(ResolutionStrategy.VECTOR_CLOCK_LWW);
    }

    public ConflictResolver(ResolutionStrategy strategy) {
        this.strategy = Objects.requireNonNull(strategy, "strategy cannot be null");
    }

    /**
     * Resolves conflict between a local cache entry and an incoming remote entry.
     *
     * @param local  the current local entry (can be null if key doesn't exist)
     * @param remote the incoming remote entry
     * @return the winning CacheEntry
     */
    public CacheEntry<V> resolve(CacheEntry<V> local, CacheEntry<V> remote) {
        if (local == null) {
            return remote;
        }
        if (remote == null) {
            return local;
        }

        if (strategy == ResolutionStrategy.LOCAL_WINS) {
            return local;
        }
        if (strategy == ResolutionStrategy.REMOTE_WINS) {
            return remote;
        }

        VectorClock localClock = local.version();
        VectorClock remoteClock = remote.version();

        Causality causality = remoteClock.causalityCheck(localClock);

        // 1. Check strict causality
        if (causality == Causality.AFTER) {
            return remote;
        }
        if (causality == Causality.BEFORE) {
            return local;
        }

        // 2. Concurrent updates: Tie-break with wall-clock timestamp (LWW)
        if (remote.createdAt() > local.createdAt()) {
            return new CacheEntry<>(
                    remote.key(),
                    remote.value(),
                    localClock.merge(remoteClock),
                    remote.createdAt(),
                    remote.expiresAt()
            );
        } else if (local.createdAt() > remote.createdAt()) {
            return new CacheEntry<>(
                    local.key(),
                    local.value(),
                    localClock.merge(remoteClock),
                    local.createdAt(),
                    local.expiresAt()
            );
        } else {
            // 3. Exact timestamp tie: Deterministic tie-breaker using value string comparison
            int cmp = String.valueOf(remote.value()).compareTo(String.valueOf(local.value()));
            if (cmp >= 0) {
                return new CacheEntry<>(
                        remote.key(),
                        remote.value(),
                        localClock.merge(remoteClock),
                        remote.createdAt(),
                        remote.expiresAt()
                );
            } else {
                return new CacheEntry<>(
                        local.key(),
                        local.value(),
                        localClock.merge(remoteClock),
                        local.createdAt(),
                        local.expiresAt()
                );
            }
        }
    }
}
