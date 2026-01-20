package dev.catananti.config;

import dev.catananti.entity.User;
import dev.catananti.repository.UserRepository;
import dev.catananti.service.IdService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminUserInitializer Tests")
class AdminUserInitializerTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private IdService idService;

    @InjectMocks
    private AdminUserInitializer initializer;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(initializer, "adminEmail", "admin@example.com");
        ReflectionTestUtils.setField(initializer, "adminPassword", "strongPassword123!");
        ReflectionTestUtils.setField(initializer, "adminName", "Administrator");
    }

    @Test
    @DisplayName("Should create admin user when none exists")
    void shouldCreateAdminWhenNoneExists() {
        when(userRepository.existsByEmail("admin@example.com")).thenReturn(Mono.just(false));
        when(passwordEncoder.encode(anyString())).thenReturn("encoded-password");
        when(idService.nextId()).thenReturn(1L);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            return Mono.just(user);
        });

        initializer.initializeAdminUser();

        // Give async subscribe time to execute
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        verify(userRepository).existsByEmail("admin@example.com");
        verify(userRepository).save(any(User.class));
        verify(passwordEncoder).encode("strongPassword123!");
    }

    @Test
    @DisplayName("Should skip creation when admin already exists")
    void shouldSkipWhenAdminExists() {
        when(userRepository.existsByEmail("admin@example.com")).thenReturn(Mono.just(true));

        initializer.initializeAdminUser();

        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        verify(userRepository).existsByEmail("admin@example.com");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Should skip when email is blank")
    void shouldSkipWhenEmailIsBlank() {
        ReflectionTestUtils.setField(initializer, "adminEmail", "");

        initializer.initializeAdminUser();

        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("Should skip when email is null")
    void shouldSkipWhenEmailIsNull() {
        ReflectionTestUtils.setField(initializer, "adminEmail", null);

        initializer.initializeAdminUser();

        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("Should skip when password is blank")
    void shouldSkipWhenPasswordIsBlank() {
        ReflectionTestUtils.setField(initializer, "adminPassword", "");

        initializer.initializeAdminUser();

        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("Should skip when password is null")
    void shouldSkipWhenPasswordIsNull() {
        ReflectionTestUtils.setField(initializer, "adminPassword", null);

        initializer.initializeAdminUser();

        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("Should skip when password is too short (less than 12 chars)")
    void shouldSkipWhenPasswordTooShort() {
        ReflectionTestUtils.setField(initializer, "adminPassword", "short");

        initializer.initializeAdminUser();

        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("Should handle error during admin creation gracefully")
    void shouldHandleErrorGracefully() {
        when(userRepository.existsByEmail("admin@example.com")).thenReturn(Mono.just(false));
        when(passwordEncoder.encode(anyString())).thenReturn("encoded-password");
        when(idService.nextId()).thenReturn(1L);
        when(userRepository.save(any(User.class)))
                .thenReturn(Mono.error(new RuntimeException("Database error")));

        // Should not throw - error is handled in subscribe
        initializer.initializeAdminUser();

        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("Should handle error from existsByEmail gracefully")
    void shouldHandleExistsByEmailError() {
        when(userRepository.existsByEmail("admin@example.com"))
                .thenReturn(Mono.error(new RuntimeException("Connection refused")));

        // Should not throw
        initializer.initializeAdminUser();

        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        verify(userRepository).existsByEmail("admin@example.com");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Should accept password exactly 12 characters long")
    void shouldAcceptPasswordExactly12Chars() {
        ReflectionTestUtils.setField(initializer, "adminPassword", "exactly12chr");
        when(userRepository.existsByEmail("admin@example.com")).thenReturn(Mono.just(false));
        when(passwordEncoder.encode(anyString())).thenReturn("encoded-password");
        when(idService.nextId()).thenReturn(1L);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        initializer.initializeAdminUser();

        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("Should use configured admin name")
    void shouldUseConfiguredAdminName() {
        ReflectionTestUtils.setField(initializer, "adminName", "Custom Admin");
        when(userRepository.existsByEmail("admin@example.com")).thenReturn(Mono.just(false));
        when(passwordEncoder.encode(anyString())).thenReturn("encoded-password");
        when(idService.nextId()).thenReturn(42L);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        initializer.initializeAdminUser();

        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        verify(userRepository).save(argThat(user ->
                "Custom Admin".equals(user.getName()) && "ADMIN".equals(user.getRole())
        ));
    }
}
