package dev.catananti.config;

import dev.catananti.entity.NewRecordAware;
import org.reactivestreams.Publisher;
import org.springframework.data.r2dbc.mapping.event.AfterConvertCallback;
import org.springframework.data.relational.core.sql.SqlIdentifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Callback that sets newRecord = false on all Persistable entities
 * after they are loaded from the database. This ensures that
 * entities with pre-assigned Snowflake IDs are correctly identified
 * as existing (not new) when loaded from DB, preventing INSERT
 * instead of UPDATE on save().
 *
 * Uses Java 21+ pattern matching instanceof instead of reflection.
 */
@Component
public class PersistableEntityCallback implements AfterConvertCallback<Object> {

    @Override
    public Publisher<Object> onAfterConvert(Object entity, SqlIdentifier table) {
        if (entity instanceof NewRecordAware aware) {
            aware.setNewRecord(false);
        }
        return Mono.just(entity);
    }
}
