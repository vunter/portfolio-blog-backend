package dev.catananti.entity;

/**
 * Marker interface for entities that track their persistence state
 * via a {@code newRecord} flag. All entities using Snowflake IDs
 * with {@link org.springframework.data.domain.Persistable} should
 * implement this to support reflection-free state management in
 * {@link dev.catananti.config.PersistableEntityCallback}.
 */
public interface NewRecordAware {
    void setNewRecord(boolean newRecord);
}
