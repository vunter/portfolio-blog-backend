package dev.catananti.config;

import dev.catananti.util.SnowflakeId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetAddress;
import java.net.NetworkInterface;

/**
 * Configuration for Snowflake ID generator.
 * 
 * <p>The node ID can be configured via:</p>
 * <ul>
 *   <li>Environment variable: SNOWFLAKE_NODE_ID</li>
 *   <li>Application property: app.snowflake.node-id</li>
 *   <li>Auto-detection from MAC address (default)</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@Slf4j
public class SnowflakeIdConfig {

    @Value("${app.snowflake.node-id:#{null}}")
    private Long configuredNodeId;

    @Bean
    public SnowflakeId snowflakeId() {
        long nodeId = resolveNodeId();
        log.info("Initialized Snowflake ID generator with node ID: {}", nodeId);
        return new SnowflakeId(nodeId);
    }

    /**
     * Resolves the node ID from configuration or auto-generates from network interface.
     */
    private long resolveNodeId() {
        // First, try configured node ID
        if (configuredNodeId != null) {
            log.debug("Using configured Snowflake node ID: {}", configuredNodeId);
            return configuredNodeId;
        }

        // Auto-generate from MAC address
        try {
            InetAddress localHost = InetAddress.getLocalHost();
            NetworkInterface networkInterface = NetworkInterface.getByInetAddress(localHost);
            
            if (networkInterface != null) {
                byte[] mac = networkInterface.getHardwareAddress();
                if (mac != null && mac.length >= 2) {
                    // Use last 10 bits of MAC address hash
                    int hash = ((mac[mac.length - 2] & 0xFF) << 8) | (mac[mac.length - 1] & 0xFF);
                    long nodeId = hash & 0x3FF; // 10 bits = 1023 max
                    log.debug("Auto-generated Snowflake node ID from MAC address: {}", nodeId);
                    return nodeId;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to auto-generate node ID from network interface: {}", e.getMessage());
        }

        // Fallback: use hash of hostname
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            long nodeId = Math.abs(hostname.hashCode()) & 0x3FF;
            log.debug("Auto-generated Snowflake node ID from hostname '{}': {}", hostname, nodeId);
            return nodeId;
        } catch (Exception e) {
            log.warn("Failed to get hostname, using default node ID 0");
            return 0;
        }
    }
}
