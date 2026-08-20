package dev.catananti.service;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.AddressNotFoundException;
import com.maxmind.geoip2.exception.GeoIp2Exception;
import com.maxmind.geoip2.model.CountryResponse;
import com.maxmind.geoip2.record.Country;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GeoIPServiceTest {

    private GeoIPService geoIPService;
    private DatabaseReader mockReader;

    @BeforeEach
    void setUp() {
        geoIPService = new GeoIPService();
        mockReader = mock(DatabaseReader.class);
    }

    /**
     * Build a real CountryResponse carrying the given ISO code.
     * <p>
     * geoip2 5.x turned CountryResponse and Country into Java records, so the
     * previous {@code mock(CountryResponse.class)} + {@code when(...getCountry())}
     * stubbing no longer applies — records are final and their accessors are
     * generated, not overridable. Constructing the real records via their
     * canonical constructors is both simpler and a stronger test: it exercises
     * the actual accessor chain the service calls.
     */
    private static CountryResponse countryResponseWithIsoCode(String isoCode) {
        Country country = new Country(
                List.of(),   // locales
                null,        // confidence
                null,        // geonameId
                false,       // isInEuropeanUnion
                isoCode,
                Map.of());   // names
        return new CountryResponse(
                null,        // continent
                country,
                null,        // maxmind
                null,        // registeredCountry
                null,        // representedCountry
                null);       // traits
    }

    /**
     * Helper to inject the volatile reader field via reflection.
     */
    private void setReader(DatabaseReader reader) throws Exception {
        Field readerField = GeoIPService.class.getDeclaredField("reader");
        readerField.setAccessible(true);
        readerField.set(geoIPService, reader);
    }

    /**
     * Helper to inject the databasePath field via reflection.
     */
    private void setDatabasePath(String path) throws Exception {
        Field pathField = GeoIPService.class.getDeclaredField("databasePath");
        pathField.setAccessible(true);
        pathField.set(geoIPService, path);
    }

    // ==================== isAvailable ====================

    @Nested
    @DisplayName("isAvailable")
    class IsAvailable {

        @Test
        @DisplayName("Should return false when reader is not initialized")
        void isAvailable_ShouldReturnFalse_WhenReaderIsNull() {
            assertThat(geoIPService.isAvailable()).isFalse();
        }

        @Test
        @DisplayName("Should return true when reader is initialized")
        void isAvailable_ShouldReturnTrue_WhenReaderIsSet() throws Exception {
            setReader(mockReader);
            assertThat(geoIPService.isAvailable()).isTrue();
        }
    }

    // ==================== getCountryCode ====================

    @Nested
    @DisplayName("getCountryCode")
    class GetCountryCode {

        @Test
        @DisplayName("Should return empty Mono when reader is null")
        void getCountryCode_ShouldReturnEmpty_WhenReaderIsNull() {
            StepVerifier.create(geoIPService.getCountryCode("8.8.8.8"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return empty Mono when IP address is null")
        void getCountryCode_ShouldReturnEmpty_WhenIpIsNull() throws Exception {
            setReader(mockReader);

            StepVerifier.create(geoIPService.getCountryCode(null))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return empty Mono when IP address is blank")
        void getCountryCode_ShouldReturnEmpty_WhenIpIsBlank() throws Exception {
            setReader(mockReader);

            StepVerifier.create(geoIPService.getCountryCode("  "))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return empty Mono when IP address is empty string")
        void getCountryCode_ShouldReturnEmpty_WhenIpIsEmpty() throws Exception {
            setReader(mockReader);

            StepVerifier.create(geoIPService.getCountryCode(""))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return empty Mono for loopback address")
        void getCountryCode_ShouldReturnEmpty_ForLoopbackAddress() throws Exception {
            setReader(mockReader);

            StepVerifier.create(geoIPService.getCountryCode("127.0.0.1"))
                    .verifyComplete();

            verifyNoInteractions(mockReader);
        }

        @Test
        @DisplayName("Should return empty Mono for site-local address")
        void getCountryCode_ShouldReturnEmpty_ForSiteLocalAddress() throws Exception {
            setReader(mockReader);

            StepVerifier.create(geoIPService.getCountryCode("192.168.1.1"))
                    .verifyComplete();

            verifyNoInteractions(mockReader);
        }

        @Test
        @DisplayName("Should return empty Mono for link-local address")
        void getCountryCode_ShouldReturnEmpty_ForLinkLocalAddress() throws Exception {
            setReader(mockReader);

            StepVerifier.create(geoIPService.getCountryCode("169.254.1.1"))
                    .verifyComplete();

            verifyNoInteractions(mockReader);
        }

        @Test
        @DisplayName("Should return country code for valid public IP")
        void getCountryCode_ShouldReturnCountryCode_ForValidPublicIp() throws Exception {
            setReader(mockReader);

            when(mockReader.country(any(InetAddress.class)))
                    .thenReturn(countryResponseWithIsoCode("US"));

            StepVerifier.create(geoIPService.getCountryCode("8.8.8.8"))
                    .assertNext(code -> assertThat(code).isEqualTo("US"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should trim whitespace from IP address before lookup")
        void getCountryCode_ShouldTrimIpAddress() throws Exception {
            setReader(mockReader);

            when(mockReader.country(any(InetAddress.class)))
                    .thenReturn(countryResponseWithIsoCode("BR"));

            StepVerifier.create(geoIPService.getCountryCode("  8.8.8.8  "))
                    .assertNext(code -> assertThat(code).isEqualTo("BR"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return empty Mono when country code is null in response")
        void getCountryCode_ShouldReturnEmpty_WhenCountryCodeIsNull() throws Exception {
            setReader(mockReader);

            when(mockReader.country(any(InetAddress.class)))
                    .thenReturn(countryResponseWithIsoCode(null));

            StepVerifier.create(geoIPService.getCountryCode("8.8.8.8"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return empty Mono on GeoIp2Exception")
        void getCountryCode_ShouldReturnEmpty_OnGeoIp2Exception() throws Exception {
            setReader(mockReader);

            when(mockReader.country(any(InetAddress.class)))
                    .thenThrow(new AddressNotFoundException("Not found"));

            StepVerifier.create(geoIPService.getCountryCode("8.8.8.8"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return empty Mono on IOException during lookup")
        void getCountryCode_ShouldReturnEmpty_OnIOException() throws Exception {
            setReader(mockReader);

            when(mockReader.country(any(InetAddress.class)))
                    .thenThrow(new IOException("Database read error"));

            StepVerifier.create(geoIPService.getCountryCode("8.8.8.8"))
                    .verifyComplete();
        }
    }

    // ==================== init ====================

    @Nested
    @DisplayName("init")
    class Init {

        @Test
        @DisplayName("Should not load database when path is null")
        void init_ShouldNotLoadDatabase_WhenPathIsNull() throws Exception {
            setDatabasePath(null);
            geoIPService.init();
            assertThat(geoIPService.isAvailable()).isFalse();
        }

        @Test
        @DisplayName("Should not load database when path is blank")
        void init_ShouldNotLoadDatabase_WhenPathIsBlank() throws Exception {
            setDatabasePath("   ");
            geoIPService.init();
            assertThat(geoIPService.isAvailable()).isFalse();
        }

        @Test
        @DisplayName("Should not load database when file does not exist")
        void init_ShouldNotLoadDatabase_WhenFileDoesNotExist() throws Exception {
            setDatabasePath("/nonexistent/path/GeoLite2-Country.mmdb");
            geoIPService.init();
            assertThat(geoIPService.isAvailable()).isFalse();
        }
    }

    // ==================== destroy ====================

    @Nested
    @DisplayName("destroy")
    class Destroy {

        @Test
        @DisplayName("Should close reader on destroy")
        void destroy_ShouldCloseReader() throws Exception {
            setReader(mockReader);

            geoIPService.destroy();

            verify(mockReader).close();
        }

        @Test
        @DisplayName("Should handle null reader gracefully on destroy")
        void destroy_ShouldHandleNullReader() {
            // No reader set, should not throw
            geoIPService.destroy();
        }

        @Test
        @DisplayName("Should handle IOException when closing reader")
        void destroy_ShouldHandleIOException_WhenClosingReader() throws Exception {
            setReader(mockReader);
            doThrow(new IOException("Close failed")).when(mockReader).close();

            // Should not throw
            geoIPService.destroy();

            verify(mockReader).close();
        }
    }

    // ==================== reload ====================

    @Nested
    @DisplayName("reload")
    class Reload {

        @Test
        @DisplayName("Should not throw when database path is not configured")
        void reload_ShouldNotThrow_WhenPathNotConfigured() throws Exception {
            setDatabasePath("/nonexistent/path/GeoLite2-Country.mmdb");
            // Should not throw, just log a warning
            geoIPService.reload();
            assertThat(geoIPService.isAvailable()).isFalse();
        }
    }
}
