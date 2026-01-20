package dev.catananti.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

class IpAddressExtractorTest {

    @Nested
    @DisplayName("extractClientIp")
    class ExtractClientIp {

        @Test
        @DisplayName("Should extract rightmost non-trusted IP from X-Forwarded-For header")
        void shouldExtractFromXForwardedFor() {
            // SEC: Only read proxy headers when direct connection is from a trusted proxy
            MockServerHttpRequest request = MockServerHttpRequest.get("/")
                    .remoteAddress(new InetSocketAddress("127.0.0.1", 8080))
                    .header("X-Forwarded-For", "203.0.113.50, 70.41.3.18, 150.172.238.178")
                    .build();

            String ip = IpAddressExtractor.extractClientIp(request);
            // Rightmost non-trusted IP prevents spoofing
            assertThat(ip).isEqualTo("150.172.238.178");
        }

        @Test
        @DisplayName("Should extract single IP from X-Forwarded-For")
        void shouldExtractSingleIpFromXForwardedFor() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/")
                    .remoteAddress(new InetSocketAddress("127.0.0.1", 8080))
                    .header("X-Forwarded-For", "203.0.113.100")
                    .build();

            String ip = IpAddressExtractor.extractClientIp(request);
            assertThat(ip).isEqualTo("203.0.113.100");
        }

        @Test
        @DisplayName("Should extract IP from X-Real-IP when no X-Forwarded-For")
        void shouldExtractFromXRealIp() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/")
                    .remoteAddress(new InetSocketAddress("127.0.0.1", 8080))
                    .header("X-Real-IP", "203.0.113.10")
                    .build();

            String ip = IpAddressExtractor.extractClientIp(request);
            assertThat(ip).isEqualTo("203.0.113.10");
        }

        @Test
        @DisplayName("Should prefer X-Forwarded-For over X-Real-IP")
        void shouldPreferXForwardedFor() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/")
                    .remoteAddress(new InetSocketAddress("127.0.0.1", 8080))
                    .header("X-Forwarded-For", "1.2.3.4")
                    .header("X-Real-IP", "5.6.7.8")
                    .build();

            String ip = IpAddressExtractor.extractClientIp(request);
            assertThat(ip).isEqualTo("1.2.3.4");
        }

        @Test
        @DisplayName("Should reject invalid X-Forwarded-For and use X-Real-IP")
        void shouldRejectInvalidXForwardedFor() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/")
                    .remoteAddress(new InetSocketAddress("127.0.0.1", 8080))
                    .header("X-Forwarded-For", "<script>alert('xss')</script>")
                    .header("X-Real-IP", "203.0.113.10")
                    .build();

            String ip = IpAddressExtractor.extractClientIp(request);
            assertThat(ip).isEqualTo("203.0.113.10");
        }

        @Test
        @DisplayName("Should return direct remote address when not from trusted proxy")
        void shouldReturnDirectRemoteAddress() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/")
                    .remoteAddress(new InetSocketAddress("203.0.113.50", 8080))
                    .build();

            // Non-trusted remote address — proxy headers are ignored
            String ip = IpAddressExtractor.extractClientIp(request);
            assertThat(ip).isEqualTo("203.0.113.50");
        }

        @Test
        @DisplayName("Should fall back to remote address when no proxy headers")
        void shouldFallBackToRemoteAddress() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/")
                    .remoteAddress(new InetSocketAddress("127.0.0.1", 8080))
                    .build();

            // Trusted proxy but no proxy headers — fall back to remote address
            String ip = IpAddressExtractor.extractClientIp(request);
            assertThat(ip).isEqualTo("127.0.0.1");
        }

        @Test
        @DisplayName("Should handle IPv6 addresses")
        void shouldHandleIpv6() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/")
                    .remoteAddress(new InetSocketAddress("127.0.0.1", 8080))
                    .header("X-Forwarded-For", "2001:db8::1")
                    .build();

            String ip = IpAddressExtractor.extractClientIp(request);
            assertThat(ip).isEqualTo("2001:db8::1");
        }
    }

    @Nested
    @DisplayName("isValidIp")
    class IsValidIp {

        @Test
        @DisplayName("Should accept valid IPv4")
        void shouldAcceptIpv4() {
            assertThat(IpAddressExtractor.isValidIp("192.168.1.1")).isTrue();
            assertThat(IpAddressExtractor.isValidIp("10.0.0.1")).isTrue();
            assertThat(IpAddressExtractor.isValidIp("255.255.255.255")).isTrue();
        }

        @Test
        @DisplayName("Should accept valid IPv6")
        void shouldAcceptIpv6() {
            assertThat(IpAddressExtractor.isValidIp("2001:db8::1")).isTrue();
            assertThat(IpAddressExtractor.isValidIp("::1")).isTrue();
            assertThat(IpAddressExtractor.isValidIp("fe80::1")).isTrue();
        }

        @Test
        @DisplayName("Should reject null")
        void shouldRejectNull() {
            assertThat(IpAddressExtractor.isValidIp(null)).isFalse();
        }

        @Test
        @DisplayName("Should reject empty/blank strings")
        void shouldRejectEmpty() {
            assertThat(IpAddressExtractor.isValidIp("")).isFalse();
            assertThat(IpAddressExtractor.isValidIp("   ")).isFalse();
        }

        @Test
        @DisplayName("Should reject strings with script injection")
        void shouldRejectInjection() {
            assertThat(IpAddressExtractor.isValidIp("<script>")).isFalse();
            assertThat(IpAddressExtractor.isValidIp("'; DROP TABLE users;--")).isFalse();
        }

        @Test
        @DisplayName("Should reject overly long strings")
        void shouldRejectLongStrings() {
            String longString = "a".repeat(46);
            assertThat(IpAddressExtractor.isValidIp(longString)).isFalse();
        }
    }
}
