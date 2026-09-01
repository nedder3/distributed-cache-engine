package com.nedder3.cache.store.serializer;

import com.nedder3.cache.core.clock.VectorClock;
import com.nedder3.cache.core.model.CacheEntry;
import com.nedder3.cache.core.model.CacheKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BinaryCacheEntrySerializer (TDD RED / Task 2.1)")
class BinaryCacheEntrySerializerTest {

    private BinaryCacheEntrySerializer serializer;

    @BeforeEach
    void setUp() {
        serializer = new BinaryCacheEntrySerializer();
    }

    @Nested
    @DisplayName("CacheEntry Binary Serialization Protocol")
    class BinaryProtocolTests {

        @Test
        @DisplayName("encodes and decodes full CacheEntry with byte[] payload, VectorClock and TTL")
        void roundTripFullEntry() {
            CacheKey key = new CacheKey("users", "usr_100");
            byte[] payload = "raw-binary-data".getBytes();
            VectorClock clock = new VectorClock(Map.of("node-1", 3L, "node-2", 1L));
            long now = System.currentTimeMillis();
            OptionalLong expiresAt = OptionalLong.of(now + 60_000L);

            CacheEntry<byte[]> entry = new CacheEntry<>(key, payload, clock, now, expiresAt);

            byte[] encoded = serializer.serialize(entry);
            assertThat(encoded).isNotNull().isNotEmpty();

            CacheEntry<byte[]> decoded = serializer.deserialize(encoded);
            assertThat(decoded.key()).isEqualTo(key);
            assertThat(decoded.value()).isEqualTo(payload);
            assertThat(decoded.version()).isEqualTo(clock);
            assertThat(decoded.createdAt()).isEqualTo(now);
            assertThat(decoded.expiresAt()).isEqualTo(expiresAt);
        }

        @Test
        @DisplayName("encodes and decodes CacheEntry without TTL (empty OptionalLong)")
        void roundTripEntryWithoutTtl() {
            CacheKey key = new CacheKey("products", "prod_99");
            byte[] payload = "catalog-item".getBytes();
            VectorClock clock = new VectorClock(Map.of("node-1", 1L));
            long now = System.currentTimeMillis();

            CacheEntry<byte[]> entry = new CacheEntry<>(key, payload, clock, now, OptionalLong.empty());

            byte[] encoded = serializer.serialize(entry);
            CacheEntry<byte[]> decoded = serializer.deserialize(encoded);

            assertThat(decoded.key()).isEqualTo(key);
            assertThat(decoded.value()).isEqualTo(payload);
            assertThat(decoded.expiresAt()).isEmpty();
        }

        @Test
        @DisplayName("throws IllegalArgumentException when decoding corrupted header/magic byte")
        void corruptedMagicByteThrows() {
            byte[] invalid = new byte[]{0x00, 0x01, 0x02};
            assertThatThrownBy(() -> serializer.deserialize(invalid))
                    .isInstanceOf(RuntimeException.class);
        }
    }
}
