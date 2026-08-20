package dev.catananti.service;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.GeoIp2Exception;
import com.maxmind.geoip2.model.CountryResponse;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Optional;

/**
 * Resolves IP addresses to country codes using MaxMind GeoLite2-Country database.
 * The database is loaded once at startup and reloaded when the file changes.
 * Returns ISO 3166-1 alpha-2 country codes (e.g. "BR", "US", "DE").
 */
@Service
@Slf4j
public class GeoIPService {

    @Value("${geoip.database.path:#{null}}")
    private String databasePath;

    private volatile DatabaseReader reader;

    @PostConstruct
    public void init() {
        if (databasePath == null || databasePath.isBlank()) {
            log.info("GeoIP database path not configured (geoip.database.path). Geolocation disabled.");
            return;
        }
        loadDatabase();
    }

    private void loadDatabase() {
        try {
            File dbFile = new File(databasePath);
            if (!dbFile.exists()) {
                log.warn("GeoIP database not found at: {}. Geolocation disabled.", databasePath);
                return;
            }
            DatabaseReader newReader = new DatabaseReader.Builder(dbFile).build();
            DatabaseReader oldReader = this.reader;
            this.reader = newReader;
            if (oldReader != null) {
                oldReader.close();
            }
            log.info("GeoIP database loaded: {} ({} bytes)", databasePath, dbFile.length());
        } catch (IOException e) {
            log.error("Failed to load GeoIP database from {}: {}", databasePath, e.getMessage());
        }
    }

    /**
     * Reload the database from disk (called by scheduled update task).
     */
    public void reload() {
        loadDatabase();
    }

    /**
     * Resolve an IP address string to a country code (reactive).
     * Returns empty Mono if the database is not loaded, the IP is private/invalid,
     * or no country mapping exists.
     */
    public Mono<String> getCountryCode(String ipAddress) {
        if (reader == null || ipAddress == null || ipAddress.isBlank()) {
            return Mono.empty();
        }
        // F-ASYNC-03: Offload InetAddress.getByName() DNS lookup from Netty event loop
        return Mono.fromCallable(() -> {
            InetAddress addr = InetAddress.getByName(ipAddress.trim());
            if (addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()) {
                return Optional.<String>empty();
            }
            // geoip2 5.x: responses and records are Java records. The 4.x
            // getCountry()/getIsoCode() getters still exist but are deprecated
            // for removal in 6.0 — use the record accessors.
            CountryResponse response = reader.country(addr);
            String code = response.country().isoCode();
            return Optional.ofNullable(code);
        })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(Mono::justOrEmpty)
        .onErrorResume(GeoIp2Exception.class, e -> Mono.empty())
        .onErrorResume(IOException.class, e -> {
            log.debug("GeoIP lookup failed for {}: {}", ipAddress, e.getMessage());
            return Mono.empty();
        });
    }

    public boolean isAvailable() {
        return reader != null;
    }

    @PreDestroy
    public void destroy() {
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException e) {
                log.debug("Error closing GeoIP reader: {}", e.getMessage());
            }
        }
    }
}
