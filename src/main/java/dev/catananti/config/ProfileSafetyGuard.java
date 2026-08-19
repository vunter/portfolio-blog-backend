package dev.catananti.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A5/H3: Fails startup when a development-only profile is active in a
 * production context.
 * <p>
 * Two situations are rejected:
 * <ol>
 *   <li>a dev-only profile ({@code dev}, {@code e2e}) is combined with a
 *       production profile ({@code prod}, {@code cloud}, {@code cluster});</li>
 *   <li>a dev-only profile is active while Doppler environment variables
 *       ({@code DOPPLER_ENVIRONMENT}, {@code DOPPLER_CONFIG}) indicate a
 *       managed production environment (e.g. {@code prd}).</li>
 * </ol>
 * Plain {@code dev} on a laptop (no Doppler prod markers) keeps working —
 * this guard only reacts to explicit production signals, so it cannot flake
 * local development.
 */
@Component
@Slf4j
public class ProfileSafetyGuard {

    static final Set<String> DEV_ONLY_PROFILES = Set.of("dev", "e2e");
    static final Set<String> PRODUCTION_PROFILES = Set.of("prod", "cloud", "cluster");

    /** Doppler environment/config names that mark a managed production environment. */
    private static final Pattern PROD_DOPPLER_VALUE = Pattern.compile("^(prd|prod|production)(_.*)?$");

    private final Environment environment;

    public ProfileSafetyGuard(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void enforce() {
        Set<String> active = activeProfiles();
        Set<String> devOnly = intersection(active, DEV_ONLY_PROFILES);
        if (devOnly.isEmpty()) {
            return;
        }

        Set<String> production = intersection(active, PRODUCTION_PROFILES);
        if (!production.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start: development-only profile(s) " + devOnly
                    + " are active together with production profile(s) " + production
                    + ". The dev/e2e profiles carry insecure defaults (committed JWT secret,"
                    + " admin credentials, in-memory H2) and must never run alongside a"
                    + " production profile. Fix SPRING_PROFILES_ACTIVE.");
        }

        for (String var : new String[]{"DOPPLER_ENVIRONMENT", "DOPPLER_CONFIG"}) {
            String value = environment.getProperty(var);
            if (value != null && PROD_DOPPLER_VALUE.matcher(value.toLowerCase(Locale.ROOT)).matches()) {
                throw new IllegalStateException(
                        "Refusing to start: development-only profile(s) " + devOnly
                        + " are active but " + var + "=" + value
                        + " indicates a managed production environment."
                        + " Set SPRING_PROFILES_ACTIVE=cloud (or remove the dev/e2e profile).");
            }
        }

        log.debug("ProfileSafetyGuard: dev-only profile(s) {} accepted (no production signals)", devOnly);
    }

    private Set<String> activeProfiles() {
        String[] profiles = environment.getActiveProfiles();
        if (profiles.length == 0) {
            profiles = environment.getDefaultProfiles();
        }
        return new LinkedHashSet<>(Arrays.asList(profiles));
    }

    private static Set<String> intersection(Set<String> a, Set<String> b) {
        Set<String> result = new LinkedHashSet<>(a);
        result.retainAll(b);
        return result;
    }
}
