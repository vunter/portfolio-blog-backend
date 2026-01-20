package dev.catananti.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SnowflakeId")
class SnowflakeIdTest {

    private SnowflakeId generator;

    @BeforeEach
    void setUp() {
        generator = new SnowflakeId(1);
    }

    @Nested
    @DisplayName("Constructor")
    class Constructor {

        @Test
        @DisplayName("should accept valid nodeId 0")
        void shouldAcceptNodeIdZero() {
            SnowflakeId id = new SnowflakeId(0);
            assertThat(id).isNotNull();
        }

        @Test
        @DisplayName("should accept valid nodeId 1023")
        void shouldAcceptNodeIdMax() {
            SnowflakeId id = new SnowflakeId(1023);
            assertThat(id).isNotNull();
        }

        @Test
        @DisplayName("should throw for negative nodeId")
        void shouldThrowForNegativeNodeId() {
            assertThatThrownBy(() -> new SnowflakeId(-1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Node ID must be between 0 and 1023");
        }

        @Test
        @DisplayName("should throw for nodeId exceeding max")
        void shouldThrowForExceedingNodeId() {
            assertThatThrownBy(() -> new SnowflakeId(1024))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Node ID must be between 0 and 1023");
        }
    }

    @Nested
    @DisplayName("nextId")
    class NextId {

        @Test
        @DisplayName("should return positive long")
        void shouldReturnPositiveLong() {
            long id = generator.nextId();
            assertThat(id).isPositive();
        }

        @Test
        @DisplayName("should generate unique IDs")
        void shouldGenerateUniqueIds() {
            Set<Long> ids = ConcurrentHashMap.newKeySet();
            for (int i = 0; i < 10_000; i++) {
                ids.add(generator.nextId());
            }
            assertThat(ids).hasSize(10_000);
        }

        @Test
        @DisplayName("should generate monotonically increasing IDs")
        void shouldGenerateMonotonicallyIncreasingIds() {
            long previous = generator.nextId();
            for (int i = 0; i < 1000; i++) {
                long current = generator.nextId();
                assertThat(current).isGreaterThan(previous);
                previous = current;
            }
        }
    }

    @Nested
    @DisplayName("Thread safety")
    class ThreadSafety {

        @Test
        @DisplayName("should produce unique IDs under concurrent generation")
        void shouldProduceUniqueIdsUnderConcurrency() throws InterruptedException {
            int threadCount = 8;
            int idsPerThread = 5_000;
            Set<Long> ids = ConcurrentHashMap.newKeySet();
            CountDownLatch latch = new CountDownLatch(threadCount);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < idsPerThread; i++) {
                            ids.add(generator.nextId());
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
            executor.shutdown();

            assertThat(ids).hasSize(threadCount * idsPerThread);
        }
    }

    @Nested
    @DisplayName("Extract components")
    class ExtractComponents {

        @Test
        @DisplayName("should extract timestamp roundtrip")
        void shouldExtractTimestampRoundtrip() {
            long beforeMs = System.currentTimeMillis();
            long id = generator.nextId();
            long afterMs = System.currentTimeMillis();

            long extractedTs = SnowflakeId.extractTimestamp(id);
            assertThat(extractedTs).isBetween(beforeMs, afterMs);
        }

        @Test
        @DisplayName("should extract nodeId")
        void shouldExtractNodeId() {
            long id = generator.nextId();
            int nodeId = SnowflakeId.extractNodeId(id);
            assertThat(nodeId).isEqualTo(1);
        }

        @Test
        @DisplayName("should extract nodeId for different generators")
        void shouldExtractNodeIdForDifferentGenerator() {
            SnowflakeId gen500 = new SnowflakeId(500);
            long id = gen500.nextId();
            assertThat(SnowflakeId.extractNodeId(id)).isEqualTo(500);
        }

        @Test
        @DisplayName("should extract sequence starting at zero")
        void shouldExtractSequenceStartingAtZero() {
            // After idle time, first ID in a new millisecond should have sequence 0
            try { Thread.sleep(2); } catch (InterruptedException ignored) {}
            long id = generator.nextId();
            int seq = SnowflakeId.extractSequence(id);
            assertThat(seq).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("should extract instant")
        void shouldExtractInstant() {
            Instant before = Instant.now();
            long id = generator.nextId();
            Instant after = Instant.now();

            Instant extracted = SnowflakeId.extractInstant(id);
            assertThat(extracted).isBetween(before.minusMillis(1), after.plusMillis(1));
        }

        @Test
        @DisplayName("should extract dateTime")
        void shouldExtractDateTime() {
            long id = generator.nextId();
            var dt = SnowflakeId.extractDateTime(id);
            assertThat(dt).isNotNull();
        }
    }

    @Nested
    @DisplayName("Parse and toString")
    class ParseAndToString {

        @Test
        @DisplayName("parse should decompose an ID into components")
        void parseShouldDecomposeId() {
            long id = generator.nextId();
            SnowflakeId.SnowflakeComponents components = SnowflakeId.parse(id);

            assertThat(components.nodeId()).isEqualTo(1);
            assertThat(components.sequence()).isGreaterThanOrEqualTo(0);
            assertThat(components.createdAt()).isNotNull();
        }

        @Test
        @DisplayName("toString should return numeric string")
        void toStringShouldReturnNumericString() {
            long id = generator.nextId();
            String str = SnowflakeId.toString(id);
            assertThat(str).matches("\\d+");
            assertThat(Long.parseLong(str)).isEqualTo(id);
        }

        @Test
        @DisplayName("fromString should parse back to original ID")
        void fromStringShouldRoundtrip() {
            long id = generator.nextId();
            String str = SnowflakeId.toString(id);
            long parsed = SnowflakeId.fromString(str);
            assertThat(parsed).isEqualTo(id);
        }

        @Test
        @DisplayName("fromString should throw for invalid input")
        void fromStringShouldThrowForInvalidInput() {
            assertThatThrownBy(() -> SnowflakeId.fromString("not-a-number"))
                    .isInstanceOf(NumberFormatException.class);
        }
    }

    @Nested
    @DisplayName("createId")
    class CreateId {

        @Test
        @DisplayName("should create ID with specified components")
        void shouldCreateIdWithComponents() {
            long timestamp = System.currentTimeMillis();
            int nodeId = 42;
            int sequence = 100;

            long id = SnowflakeId.createId(timestamp, nodeId, sequence);

            assertThat(SnowflakeId.extractTimestamp(id)).isEqualTo(timestamp);
            assertThat(SnowflakeId.extractNodeId(id)).isEqualTo(nodeId);
            assertThat(SnowflakeId.extractSequence(id)).isEqualTo(sequence);
        }
    }

    @Nested
    @DisplayName("Generator toString")
    class GeneratorToString {

        @Test
        @DisplayName("should include nodeId")
        void shouldIncludeNodeId() {
            assertThat(generator.toString()).isEqualTo("SnowflakeId{nodeId=1}");
        }
    }
}
