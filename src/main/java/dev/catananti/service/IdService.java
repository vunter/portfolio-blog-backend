package dev.catananti.service;

import dev.catananti.util.SnowflakeId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Service for generating and parsing Snowflake IDs.
 * 
 * <p>Use this service to generate new IDs for entities:</p>
 * <pre>
 * Article article = Article.builder()
 *     .id(idService.nextId())
 *     .title("My Article")
 *     .build();
 * </pre>
 * 
 * <p>Extract metadata from existing IDs:</p>
 * <pre>
 * LocalDateTime createdAt = idService.getCreatedAt(article.getId());
 * int nodeId = idService.getNodeId(article.getId());
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdService {

    private final SnowflakeId snowflakeId;

    /**
     * Generates a new unique Snowflake ID.
     *
     * @return a new unique 64-bit ID
     */
    public long nextId() {
        return snowflakeId.nextId();
    }

    /**
     * Extracts the creation timestamp from a Snowflake ID.
     *
     * @param id the Snowflake ID
     * @return the Instant when the ID was created
     */
    public Instant getCreatedInstant(long id) {
        return SnowflakeId.extractInstant(id);
    }

    /**
     * Extracts the creation LocalDateTime (UTC) from a Snowflake ID.
     *
     * @param id the Snowflake ID
     * @return the LocalDateTime when the ID was created
     */
    public LocalDateTime getCreatedAt(long id) {
        return SnowflakeId.extractDateTime(id);
    }

    /**
     * Extracts the node ID from a Snowflake ID.
     *
     * @param id the Snowflake ID
     * @return the node ID that generated this ID
     */
    public int getNodeId(long id) {
        return SnowflakeId.extractNodeId(id);
    }

    /**
     * Extracts the sequence number from a Snowflake ID.
     *
     * @param id the Snowflake ID
     * @return the sequence number within the millisecond
     */
    public int getSequence(long id) {
        return SnowflakeId.extractSequence(id);
    }

    /**
     * Parses a Snowflake ID into its components.
     *
     * @param id the Snowflake ID
     * @return a record containing all components
     */
    public SnowflakeId.SnowflakeComponents parse(long id) {
        return SnowflakeId.parse(id);
    }

    /**
     * Converts a Snowflake ID to its string representation.
     *
     * @param id the Snowflake ID
     * @return string representation
     */
    public String toString(long id) {
        return SnowflakeId.toString(id);
    }

    /**
     * Parses a Snowflake ID from its string representation.
     *
     * @param idString the string representation
     * @return the Snowflake ID
     */
    public long fromString(String idString) {
        return SnowflakeId.fromString(idString);
    }
}
