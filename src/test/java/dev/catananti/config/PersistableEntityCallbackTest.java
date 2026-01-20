package dev.catananti.config;

import dev.catananti.entity.NewRecordAware;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.data.relational.core.sql.SqlIdentifier;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PersistableEntityCallback Tests")
class PersistableEntityCallbackTest {

    private final PersistableEntityCallback callback = new PersistableEntityCallback();

    @Test
    @DisplayName("Should set newRecord=false for NewRecordAware entity after convert")
    void shouldSetNewRecordFalseForNewRecordAwareEntity() {
        TestNewRecordAwareEntity entity = new TestNewRecordAwareEntity();
        entity.setNewRecord(true);
        assertThat(entity.isNewRecord()).isTrue();

        Publisher<Object> result = callback.onAfterConvert(entity, SqlIdentifier.unquoted("test_table"));

        StepVerifier.create(Mono.from(result))
                .assertNext(obj -> {
                    assertThat(obj).isInstanceOf(TestNewRecordAwareEntity.class);
                    assertThat(((TestNewRecordAwareEntity) obj).isNewRecord()).isFalse();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return entity unchanged when not NewRecordAware")
    void shouldReturnUnchangedForNonNewRecordAwareEntity() {
        String plainObject = "not a NewRecordAware";

        Publisher<Object> result = callback.onAfterConvert(plainObject, SqlIdentifier.unquoted("test_table"));

        StepVerifier.create(Mono.from(result))
                .assertNext(obj -> assertThat(obj).isEqualTo("not a NewRecordAware"))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle entity that already has newRecord=false")
    void shouldHandleAlreadyFalseNewRecord() {
        TestNewRecordAwareEntity entity = new TestNewRecordAwareEntity();
        entity.setNewRecord(false);

        Publisher<Object> result = callback.onAfterConvert(entity, SqlIdentifier.unquoted("test_table"));

        StepVerifier.create(Mono.from(result))
                .assertNext(obj -> assertThat(((TestNewRecordAwareEntity) obj).isNewRecord()).isFalse())
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle null-safe pattern matching with non-entity types")
    void shouldHandleIntegerEntity() {
        Integer numericEntity = 42;

        Publisher<Object> result = callback.onAfterConvert(numericEntity, SqlIdentifier.unquoted("numbers"));

        StepVerifier.create(Mono.from(result))
                .assertNext(obj -> assertThat(obj).isEqualTo(42))
                .verifyComplete();
    }

    /**
     * Simple test entity implementing NewRecordAware for unit testing.
     */
    static class TestNewRecordAwareEntity implements NewRecordAware {
        private boolean newRecord = true;

        @Override
        public void setNewRecord(boolean newRecord) {
            this.newRecord = newRecord;
        }

        public boolean isNewRecord() {
            return this.newRecord;
        }
    }
}
