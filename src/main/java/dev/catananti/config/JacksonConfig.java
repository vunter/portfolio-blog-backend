package dev.catananti.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;

@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

    /**
     * AUD19C-4: every {@link LocalDateTime} in this system holds a UTC instant — the
     * server and DB run UTC (pinned via -Duser.timezone=UTC in the Dockerfile) and the
     * columns are zoneless TIMESTAMPs. Serializing without an offset made browser
     * clients ({@code new Date("2026-08-18T10:00:00")}) interpret the value in the
     * viewer's LOCAL zone, shifting every displayed date. Appending an explicit 'Z'
     * makes the wire format an unambiguous UTC instant.
     *
     * <p>Deserialization intentionally keeps the default lenient
     * {@code LocalDateTimeDeserializer}, which accepts both the zoneless ISO form and
     * the 'Z'-suffixed form the frontend already sends (it strips the trailing 'Z').</p>
     *
     * <p>Note: this mapper governs the HTTP wire format because WebFluxConfig
     * explicitly registers it on the server codecs via Jackson2JsonEncoder/Decoder —
     * Spring Boot 4's default Jackson 3 (tools.jackson) codecs are overridden there.</p>
     */
    static final DateTimeFormatter UTC_INSTANT_FORMAT = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .appendLiteral('Z')
            .toFormatter();

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        JavaTimeModule javaTimeModule = new JavaTimeModule();
        javaTimeModule.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(UTC_INSTANT_FORMAT));
        objectMapper.registerModule(javaTimeModule);
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return objectMapper;
    }
}
