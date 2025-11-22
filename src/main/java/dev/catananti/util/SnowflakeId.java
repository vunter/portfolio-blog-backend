package dev.catananti.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Snowflake ID Generator - Twitter-style distributed unique ID generator.
 * 
 * <p>Generates 64-bit IDs with the following structure:</p>
 * <pre>
 * | 1 bit (unused) | 41 bits (timestamp) | 10 bits (node id) | 12 bits (sequence) |
 * </pre>
 * 
 * <h3>Benefits over UUID:</h3>
 * <ul>
 *   <li><b>Time-ordered:</b> IDs are sortable by creation time</li>
 *   <li><b>Compact:</b> 64-bit vs 128-bit UUID (better index performance)</li>
 *   <li><b>Extractable metadata:</b> Can extract timestamp, node ID from the ID</li>
 *   <li><b>High throughput:</b> 4096 IDs per millisecond per node</li>
 *   <li><b>No coordination:</b> Distributed generation without central authority</li>
 * </ul>
 * 
 * <h3>Capacity:</h3>
 * <ul>
 *   <li>41 bits timestamp: ~69 years from epoch</li>
 *   <li>10 bits node ID: up to 1024 nodes</li>
 *   <li>12 bits sequence: 4096 IDs per millisecond per node</li>
 * </ul>
 * 
 * @author Leonardo Catananti
 * @since 1.0
 */
public final class SnowflakeId {
    
    // Custom epoch: January 1, 2024 00:00:00 UTC
    // This gives us more years before overflow compared to Unix epoch
    private static final long CUSTOM_EPOCH = 1704067200000L;
    
    // Bit lengths
    private static final int NODE_ID_BITS = 10;
    private static final int SEQUENCE_BITS = 12;
    
    // Maximum values
    private static final long MAX_NODE_ID = (1L << NODE_ID_BITS) - 1;  // 1023
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1; // 4095
    
    // Bit shifts
    private static final int NODE_ID_SHIFT = SEQUENCE_BITS;
    private static final int TIMESTAMP_SHIFT = SEQUENCE_BITS + NODE_ID_BITS;
    
    // Masks for extraction
    private static final long SEQUENCE_MASK = MAX_SEQUENCE;
    private static final long NODE_ID_MASK = MAX_NODE_ID << NODE_ID_SHIFT;
    private static final long TIMESTAMP_MASK = ~0L << TIMESTAMP_SHIFT;
    
    private final long nodeId;
    private final AtomicLong lastState;
    
    /**
     * Creates a new Snowflake ID generator with the specified node ID.
     *
     * @param nodeId the node ID (0-1023)
     * @throws IllegalArgumentException if nodeId is out of valid range
     */
    public SnowflakeId(long nodeId) {
        if (nodeId < 0 || nodeId > MAX_NODE_ID) {
            throw new IllegalArgumentException(
                    "Node ID must be between 0 and " + MAX_NODE_ID + ", got: " + nodeId);
        }
        this.nodeId = nodeId;
        this.lastState = new AtomicLong(0);
    }
    
    /**
     * Generates the next unique Snowflake ID.
     * 
     * <p>This method is thread-safe and lock-free, using CAS operations
     * for high-performance concurrent ID generation.</p>
     *
     * @return a new unique 64-bit Snowflake ID
     * @throws IllegalStateException if clock moves backwards more than allowed tolerance
     */
    public long nextId() {
        long currentTimestamp = currentTimestamp();
        
        while (true) {
            long oldState = lastState.get();
            long oldTimestamp = oldState >>> SEQUENCE_BITS;
            long oldSequence = oldState & MAX_SEQUENCE;
            
            long newTimestamp;
            long newSequence;
            
            if (currentTimestamp > oldTimestamp) {
                // New millisecond - reset sequence
                newTimestamp = currentTimestamp;
                newSequence = 0;
            } else if (currentTimestamp == oldTimestamp) {
                // Same millisecond - increment sequence
                newSequence = (oldSequence + 1) & MAX_SEQUENCE;
                if (newSequence == 0) {
                    // Sequence overflow - wait for next millisecond
                    currentTimestamp = waitNextMillis(currentTimestamp);
                    newTimestamp = currentTimestamp;
                } else {
                    newTimestamp = currentTimestamp;
                }
            } else {
                // Clock moved backwards - tolerate small drift
                long drift = oldTimestamp - currentTimestamp;
                if (drift <= 5) {
                    // Small drift - use last timestamp and increment sequence
                    newTimestamp = oldTimestamp;
                    newSequence = (oldSequence + 1) & MAX_SEQUENCE;
                    if (newSequence == 0) {
                        newTimestamp = oldTimestamp + 1;
                    }
                } else {
                    throw new IllegalStateException(
                            "Clock moved backwards by " + drift + "ms. Refusing to generate ID.");
                }
            }
            
            long newState = (newTimestamp << SEQUENCE_BITS) | newSequence;
            
            if (lastState.compareAndSet(oldState, newState)) {
                return (newTimestamp << TIMESTAMP_SHIFT) | (nodeId << NODE_ID_SHIFT) | newSequence;
            }
            // CAS failed - retry with updated timestamp
            currentTimestamp = currentTimestamp();
        }
    }
    
    /**
     * Extracts the timestamp from a Snowflake ID.
     *
     * @param id the Snowflake ID
     * @return the timestamp as epoch milliseconds
     */
    public static long extractTimestamp(long id) {
        return ((id & TIMESTAMP_MASK) >>> TIMESTAMP_SHIFT) + CUSTOM_EPOCH;
    }
    
    /**
     * Extracts the creation instant from a Snowflake ID.
     *
     * @param id the Snowflake ID
     * @return the Instant when the ID was created
     */
    public static Instant extractInstant(long id) {
        return Instant.ofEpochMilli(extractTimestamp(id));
    }
    
    /**
     * Extracts the creation LocalDateTime (UTC) from a Snowflake ID.
     *
     * @param id the Snowflake ID
     * @return the LocalDateTime when the ID was created
     */
    public static LocalDateTime extractDateTime(long id) {
        return LocalDateTime.ofInstant(extractInstant(id), ZoneOffset.UTC);
    }
    
    /**
     * Extracts the node ID from a Snowflake ID.
     *
     * @param id the Snowflake ID
     * @return the node ID (0-1023)
     */
    public static int extractNodeId(long id) {
        return (int) ((id & NODE_ID_MASK) >>> NODE_ID_SHIFT);
    }
    
    /**
     * Extracts the sequence number from a Snowflake ID.
     *
     * @param id the Snowflake ID
     * @return the sequence number (0-4095)
     */
    public static int extractSequence(long id) {
        return (int) (id & SEQUENCE_MASK);
    }
    
    /**
     * Parses a Snowflake ID into its components.
     *
     * @param id the Snowflake ID
     * @return a record containing all components
     */
    public static SnowflakeComponents parse(long id) {
        return new SnowflakeComponents(
                extractInstant(id),
                extractNodeId(id),
                extractSequence(id)
        );
    }
    
    /**
     * Converts a Snowflake ID to its string representation.
     * Uses the raw long value as string for URL-safe, compact representation.
     *
     * @param id the Snowflake ID
     * @return string representation of the ID
     */
    public static String toString(long id) {
        return Long.toString(id);
    }
    
    /**
     * Parses a Snowflake ID from its string representation.
     *
     * @param idString the string representation
     * @return the Snowflake ID
     * @throws NumberFormatException if the string is not a valid long
     */
    public static long fromString(String idString) {
        return Long.parseLong(idString);
    }
    
    /**
     * Generates a Snowflake ID for a specific timestamp (for testing/migration).
     * 
     * @param timestamp the timestamp in epoch milliseconds
     * @param nodeId the node ID
     * @param sequence the sequence number
     * @return the generated Snowflake ID
     */
    public static long createId(long timestamp, int nodeId, int sequence) {
        long adjustedTimestamp = timestamp - CUSTOM_EPOCH;
        return (adjustedTimestamp << TIMESTAMP_SHIFT) | 
               ((long) nodeId << NODE_ID_SHIFT) | 
               sequence;
    }
    
    /**
     * Returns the current timestamp relative to custom epoch.
     */
    private long currentTimestamp() {
        return System.currentTimeMillis() - CUSTOM_EPOCH;
    }
    
    /**
     * Waits until the next millisecond.
     */
    private long waitNextMillis(long currentTimestamp) {
        long newTimestamp = currentTimestamp();
        while (newTimestamp <= currentTimestamp) {
            Thread.onSpinWait();
            newTimestamp = currentTimestamp();
        }
        return newTimestamp;
    }
    
    /**
     * Returns information about this generator.
     */
    @Override
    public String toString() {
        return "SnowflakeId{nodeId=" + nodeId + "}";
    }
    
    /**
     * Record containing parsed Snowflake ID components.
     */
    public record SnowflakeComponents(
            Instant createdAt,
            int nodeId,
            int sequence
    ) {
        @Override
        public String toString() {
            return "SnowflakeComponents{" +
                    "createdAt=" + createdAt +
                    ", nodeId=" + nodeId +
                    ", sequence=" + sequence +
                    '}';
        }
    }
}
