package dev.catananti.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ProfileSafetyGuard")
class ProfileSafetyGuardTest {

    private ProfileSafetyGuard createGuard(String[] activeProfiles,
                                           String dopplerEnvironment,
                                           String dopplerConfig) {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(activeProfiles);
        when(env.getDefaultProfiles()).thenReturn(new String[]{"local"});
        when(env.getProperty("DOPPLER_ENVIRONMENT")).thenReturn(dopplerEnvironment);
        when(env.getProperty("DOPPLER_CONFIG")).thenReturn(dopplerConfig);
        return new ProfileSafetyGuard(env);
    }

    @Nested
    @DisplayName("safe combinations")
    class SafeCombinations {

        @Test
        @DisplayName("plain dev on a laptop keeps working")
        void plainDevPasses() {
            ProfileSafetyGuard guard = createGuard(new String[]{"dev"}, null, null);
            assertThatNoException().isThrownBy(guard::enforce);
        }

        @Test
        @DisplayName("dev with a Doppler dev config keeps working")
        void devWithDopplerDevConfigPasses() {
            ProfileSafetyGuard guard = createGuard(new String[]{"dev"}, "dev", "dev");
            assertThatNoException().isThrownBy(guard::enforce);
        }

        @Test
        @DisplayName("cloud alone is untouched")
        void cloudAlonePasses() {
            ProfileSafetyGuard guard = createGuard(new String[]{"cloud"}, "prd", "prd");
            assertThatNoException().isThrownBy(guard::enforce);
        }

        @Test
        @DisplayName("no active profiles falls back to default profiles (local)")
        void defaultProfilesPass() {
            ProfileSafetyGuard guard = createGuard(new String[]{}, null, null);
            assertThatNoException().isThrownBy(guard::enforce);
        }

        @Test
        @DisplayName("e2e alone keeps working")
        void e2eAlonePasses() {
            ProfileSafetyGuard guard = createGuard(new String[]{"e2e"}, null, null);
            assertThatNoException().isThrownBy(guard::enforce);
        }
    }

    @Nested
    @DisplayName("rejected combinations")
    class RejectedCombinations {

        @Test
        @DisplayName("dev + cloud fails startup")
        void devPlusCloudFails() {
            ProfileSafetyGuard guard = createGuard(new String[]{"dev", "cloud"}, null, null);
            assertThatThrownBy(guard::enforce)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("dev")
                    .hasMessageContaining("cloud");
        }

        @Test
        @DisplayName("e2e + prod fails startup")
        void e2ePlusProdFails() {
            ProfileSafetyGuard guard = createGuard(new String[]{"e2e", "prod"}, null, null);
            assertThatThrownBy(guard::enforce)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("e2e")
                    .hasMessageContaining("prod");
        }

        @Test
        @DisplayName("dev + cluster fails startup")
        void devPlusClusterFails() {
            ProfileSafetyGuard guard = createGuard(new String[]{"cluster", "dev"}, null, null);
            assertThatThrownBy(guard::enforce)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cluster");
        }

        @Test
        @DisplayName("dev with DOPPLER_ENVIRONMENT=prd fails startup")
        void devWithDopplerProdEnvironmentFails() {
            ProfileSafetyGuard guard = createGuard(new String[]{"dev"}, "prd", null);
            assertThatThrownBy(guard::enforce)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DOPPLER_ENVIRONMENT");
        }

        @Test
        @DisplayName("e2e with DOPPLER_CONFIG=production fails startup")
        void e2eWithDopplerProdConfigFails() {
            ProfileSafetyGuard guard = createGuard(new String[]{"e2e"}, null, "production");
            assertThatThrownBy(guard::enforce)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DOPPLER_CONFIG");
        }

        @Test
        @DisplayName("branch configs of prd (e.g. prd_backup) are treated as prod")
        void dopplerBranchConfigFails() {
            ProfileSafetyGuard guard = createGuard(new String[]{"dev"}, null, "prd_backup");
            assertThatThrownBy(guard::enforce)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("prd_backup");
        }
    }
}
