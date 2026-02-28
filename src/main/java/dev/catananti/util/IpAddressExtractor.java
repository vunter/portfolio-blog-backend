package dev.catananti.util;

import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Utility class for extracting client IP addresses from HTTP requests,
 * handling reverse proxy headers (X-Forwarded-For, X-Real-IP).
 * Includes IP anonymization for GDPR/LGPD compliance (SEC-08).
 * <p>
 * Security: Only trusts proxy headers from known trusted proxy IPs.
 * Uses the rightmost untrusted IP in X-Forwarded-For to prevent spoofing.
 * </p>
 */
public final class IpAddressExtractor {

    private static final Pattern IP_PATTERN = Pattern.compile("^[0-9a-fA-F.:]+$");

    private IpAddressExtractor() {
        // Utility class
    }

    /**
     * Known trusted proxy IPs (loopback only by default).
     * Specific IPs can be added for the deployment environment.
     * In production, configure via SecurityConfig.trustedProxies.
     */
    private static volatile Set<String> TRUSTED_PROXIES = Set.of(
            "127.0.0.1", "::1", "0:0:0:0:0:0:0:1"
    );

    /**
     * Allow external configuration of trusted proxies on startup.
     */
    public static void setTrustedProxies(Set<String> proxies) {
        TRUSTED_PROXIES = Set.copyOf(proxies);
    }

    /** Docker/K8s bridge networks — only these specific subnets are trusted. */
    private static final String[] TRUSTED_PREFIXES = {
            "172.17.", "172.18.", "172.19.", "172.20.",
            "10.42.", "10.43."  // K3s pod/service ranges
    };

    /**
     * Check if an IP belongs to a trusted proxy network.
     * Only trusts loopback and specific container networking ranges,
     * NOT all of RFC 1918 to limit spoofing surface.
     */
    private static boolean isTrustedProxy(String ip) {
        if (ip == null || ip.isBlank()) return false;
        if (TRUSTED_PROXIES.contains(ip)) return true;
        for (String prefix : TRUSTED_PREFIXES) {
            if (ip.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * Extract client IP from a ServerHttpRequest, checking proxy headers first.
     * Uses the rightmost non-trusted IP from X-Forwarded-For to prevent spoofing.
     *
     * @param request the incoming HTTP request
     * @return the client IP address, or "unknown" if it cannot be determined
     */
    public static String extractClientIp(ServerHttpRequest request) {
        // Get the direct remote address first
        String remoteIp = Optional.ofNullable(request.getRemoteAddress())
                .map(InetSocketAddress::getAddress)
                .map(InetAddress::getHostAddress)
                .orElse("unknown");

        // Only trust proxy headers if the direct connection is from a trusted proxy
        if (!isTrustedProxy(remoteIp)) {
            return remoteIp;
        }

        // Check X-Forwarded-For header — use rightmost non-trusted IP
        String xForwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            String[] ips = xForwardedFor.split(",");
            // Walk from right to left, find the first non-trusted IP
            for (int i = ips.length - 1; i >= 0; i--) {
                String ip = ips[i].trim();
                if (isValidIp(ip) && !isTrustedProxy(ip)) {
                    return ip;
                }
            }
        }

        // Check X-Real-IP header (e.g., from nginx)
        String xRealIp = request.getHeaders().getFirst("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank() && isValidIp(xRealIp)) {
            return xRealIp;
        }

        // Fall back to remote address
        return remoteIp;
    }

    /**
     * Extract client IP from a ServerWebExchange (convenience overload for WebFilters).
     *
     * @param exchange the server web exchange
     * @return the client IP address, or "unknown" if it cannot be determined
     */
    public static String extractClientIp(ServerWebExchange exchange) {
        return extractClientIp(exchange.getRequest());
    }

    /**
     * SEC-08: Anonymize IP address for GDPR/LGPD compliance.
     * Truncates the last octet of IPv4 or keeps only the /64 prefix of IPv6.
     *
     * @param ip the full IP address
     * @return the anonymized IP address
     */
    public static String anonymizeIp(String ip) {
        if (ip == null || ip.isBlank() || "unknown".equals(ip)) {
            return "unknown";
        }
        // IPv4: replace last octet with 0
        if (ip.contains(".") && !ip.contains(":")) {
            int lastDot = ip.lastIndexOf('.');
            if (lastDot > 0) {
                return ip.substring(0, lastDot) + ".0";
            }
        }
        // IPv6: keep first 4 groups (/64 prefix) per standard anonymization
        if (ip.contains(":")) {
            String[] parts = ip.split(":");
            if (parts.length >= 4) {
                return parts[0] + ":" + parts[1] + ":" + parts[2] + ":" + parts[3] + "::";
            }
        }
        return "anonymized";
    }

    /**
     * Validate IP address format to prevent header injection.
     * Accepts IPv4 and IPv6 characters only.
     *
     * @param ip the IP address string to validate
     * @return true if the IP format is valid
     */
    public static boolean isValidIp(String ip) {
        return ip != null
                && !ip.isBlank()
                && ip.length() <= 45  // Max IPv6 length
                && IP_PATTERN.matcher(ip).matches();
    }
}
