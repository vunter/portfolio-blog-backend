package dev.catananti.service;

import dev.catananti.entity.RefreshToken;
import dev.catananti.exception.ResourceNotFoundException;
import dev.catananti.repository.RefreshTokenRepository;
import dev.catananti.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private UserRepository userRepository;
    @Mock private IdService idService;

    @InjectMocks
    private RefreshTokenService refreshTokenService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(refreshTokenService, "refreshTokenExpirationMs", 604800000L);
    }

    @Nested
    @DisplayName("createRefreshToken")
    class CreateRefreshToken {

        @Test
        @DisplayName("Should create new refresh token and revoke old ones")
        void shouldCreateToken() {
            when(refreshTokenRepository.revokeAllByUserId(1001L)).thenReturn(Mono.empty());
            when(idService.nextId()).thenReturn(7001L);
            when(refreshTokenRepository.save(any(RefreshToken.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(refreshTokenService.createRefreshToken(1001L))
                    .assertNext(token -> {
                        assertThat(token.getUserId()).isEqualTo(1001L);
                        assertThat(token.getToken()).isNotBlank();
                        assertThat(token.getToken().length()).isGreaterThan(20);
                        assertThat(token.isRevoked()).isFalse();
                        assertThat(token.getExpiresAt()).isAfter(LocalDateTime.now());
                    })
                    .verifyComplete();

            verify(refreshTokenRepository).revokeAllByUserId(1001L);
        }
    }

    @Nested
    @DisplayName("verifyAndRotate")
    class VerifyAndRotate {

        @Test
        @DisplayName("Should rotate valid token: revoke old and create new")
        void shouldRotateValidToken() {
            RefreshToken existing = RefreshToken.builder()
                    .id(7001L)
                    .userId(1001L)
                    .token("valid-token-abc")
                    .expiresAt(LocalDateTime.now().plusDays(5))
                    .revoked(false)
                    .createdAt(LocalDateTime.now().minusDays(2))
                    .build();

            when(refreshTokenRepository.findByToken(anyString()))
                    .thenReturn(Mono.just(existing));
            when(refreshTokenRepository.save(any(RefreshToken.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(refreshTokenRepository.revokeAllByUserId(1001L)).thenReturn(Mono.empty());
            when(idService.nextId()).thenReturn(7002L);

            StepVerifier.create(refreshTokenService.verifyAndRotate("valid-token-abc"))
                    .assertNext(newToken -> {
                        assertThat(newToken.getUserId()).isEqualTo(1001L);
                        assertThat(newToken.getToken()).isNotEqualTo("valid-token-abc");
                        assertThat(newToken.isRevoked()).isFalse();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should detect token reuse and revoke all user tokens")
        void shouldDetectTokenReuse() {
            RefreshToken revoked = RefreshToken.builder()
                    .id(7001L)
                    .userId(1001L)
                    .token("reused-token")
                    .revoked(true)
                    .build();

            when(refreshTokenRepository.findByToken(anyString()))
                    .thenReturn(Mono.just(revoked));
            when(refreshTokenRepository.revokeAllByUserId(1001L)).thenReturn(Mono.empty());

            StepVerifier.create(refreshTokenService.verifyAndRotate("reused-token"))
                    .expectError(SecurityException.class)
                    .verify();

            verify(refreshTokenRepository).revokeAllByUserId(1001L);
        }

        @Test
        @DisplayName("Should reject expired token")
        void shouldRejectExpiredToken() {
            RefreshToken expired = RefreshToken.builder()
                    .id(7001L)
                    .userId(1001L)
                    .token("expired-token")
                    .expiresAt(LocalDateTime.now().minusDays(1))
                    .revoked(false)
                    .createdAt(LocalDateTime.now().minusDays(10))
                    .build();

            when(refreshTokenRepository.findByToken(anyString()))
                    .thenReturn(Mono.just(expired));

            StepVerifier.create(refreshTokenService.verifyAndRotate("expired-token"))
                    .expectError(SecurityException.class)
                    .verify();
        }

        @Test
        @DisplayName("Should throw for nonexistent token")
        void shouldThrowForNonexistentToken() {
            when(refreshTokenRepository.findByToken(anyString()))
                    .thenReturn(Mono.empty());

            StepVerifier.create(refreshTokenService.verifyAndRotate("nonexistent"))
                    .expectError(ResourceNotFoundException.class)
                    .verify();
        }
    }

    @Nested
    @DisplayName("revokeToken")
    class RevokeToken {

        @Test
        @DisplayName("Should revoke existing token")
        void shouldRevokeToken() {
            RefreshToken existing = RefreshToken.builder()
                    .id(7001L).userId(1001L).token("to-revoke")
                    .revoked(false).build();

            when(refreshTokenRepository.findByToken(anyString()))
                    .thenReturn(Mono.just(existing));
            when(refreshTokenRepository.save(any(RefreshToken.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(refreshTokenService.revokeToken("to-revoke"))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("revokeAllUserTokens")
    class RevokeAllUserTokens {

        @Test
        @DisplayName("Should revoke all tokens for a user")
        void shouldRevokeAll() {
            when(refreshTokenRepository.revokeAllByUserId(1001L)).thenReturn(Mono.empty());

            StepVerifier.create(refreshTokenService.revokeAllUserTokens(1001L))
                    .verifyComplete();

            verify(refreshTokenRepository).revokeAllByUserId(1001L);
        }
    }
}
