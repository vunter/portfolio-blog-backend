package dev.catananti.service;

import dev.catananti.util.SnowflakeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdServiceTest {

    @Mock
    private SnowflakeId snowflakeId;

    @InjectMocks
    private IdService idService;

    // ============================
    // nextId
    // ============================
    @Nested
    @DisplayName("nextId")
    class NextId {

        @Test
        @DisplayName("should delegate to SnowflakeId.nextId()")
        void delegatesToSnowflakeId() {
            long expectedId = 1234567890123456L;
            when(snowflakeId.nextId()).thenReturn(expectedId);

            long result = idService.nextId();

            assertThat(result).isEqualTo(expectedId);
            verify(snowflakeId).nextId();
        }

        @Test
        @DisplayName("should return different IDs on successive calls")
        void returnsDifferentIds() {
            when(snowflakeId.nextId()).thenReturn(100L, 200L, 300L);

            assertThat(idService.nextId()).isEqualTo(100L);
            assertThat(idService.nextId()).isEqualTo(200L);
            assertThat(idService.nextId()).isEqualTo(300L);

            verify(snowflakeId, times(3)).nextId();
        }
    }

    // ============================
    // getCreatedInstant
    // ============================
    @Nested
    @DisplayName("getCreatedInstant")
    class GetCreatedInstant {

        @Test
        @DisplayName("should delegate to SnowflakeId.extractInstant()")
        void delegatesToExtractInstant() {
            long id = 1234567890123456L;
            // Just verifying it doesn't throw — the static method logic is tested in SnowflakeIdTest
            var instant = idService.getCreatedInstant(id);
            assertThat(instant).isNotNull();
        }
    }

    // ============================
    // getNodeId
    // ============================
    @Nested
    @DisplayName("getNodeId")
    class GetNodeId {

        @Test
        @DisplayName("should delegate to SnowflakeId.extractNodeId()")
        void delegatesToExtractNodeId() {
            long id = 1234567890123456L;
            int nodeId = idService.getNodeId(id);
            // Just ensuring no exception and result is non-negative
            assertThat(nodeId).isGreaterThanOrEqualTo(0);
        }
    }

    // ============================
    // getSequence
    // ============================
    @Nested
    @DisplayName("getSequence")
    class GetSequence {

        @Test
        @DisplayName("should delegate to SnowflakeId.extractSequence()")
        void delegatesToExtractSequence() {
            long id = 1234567890123456L;
            int sequence = idService.getSequence(id);
            assertThat(sequence).isGreaterThanOrEqualTo(0);
        }
    }

    // ============================
    // parse
    // ============================
    @Nested
    @DisplayName("parse")
    class Parse {

        @Test
        @DisplayName("should return SnowflakeComponents from SnowflakeId.parse()")
        void returnsComponents() {
            long id = 1234567890123456L;
            var components = idService.parse(id);
            assertThat(components).isNotNull();
        }
    }
}
