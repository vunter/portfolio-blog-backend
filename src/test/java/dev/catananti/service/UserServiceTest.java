package dev.catananti.service;

import dev.catananti.dto.ProfileUpdateRequest;
import dev.catananti.dto.RoleUpdateRequest;
import dev.catananti.dto.UserRequest;
import dev.catananti.dto.UserResponse;
import dev.catananti.entity.User;
import dev.catananti.exception.ResourceNotFoundException;
import dev.catananti.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private IdService idService;

    @Mock
    private HtmlSanitizerService htmlSanitizerService;

    @Mock
    private CloudflareEmailRoutingService cfEmailRoutingService;

    @Mock
    private EmailService emailService;

    @InjectMocks
    private UserService userService;

    private User testUser;
    private Long testUserId;

    @BeforeEach
    void setUp() {
        lenient().when(htmlSanitizerService.stripHtml(anyString())).thenAnswer(inv -> inv.getArgument(0));

        testUserId = 1234567890123456789L;
        testUser = User.builder()
                .id(testUserId)
                .email("test@example.com")
                .name("Test User")
                .passwordHash("hashedPassword")
                .role("ADMIN")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("Should get user by ID")
    void shouldGetUserById() {
        when(userRepository.findById(testUserId)).thenReturn(Mono.just(testUser));

        StepVerifier.create(userService.getUserById(testUserId))
                .assertNext(response -> {
                    assertThat(response.getId()).isEqualTo(String.valueOf(testUserId));
                    assertThat(response.getEmail()).isEqualTo("test@example.com");
                    assertThat(response.getName()).isEqualTo("Test User");
                    assertThat(response.getRole()).isEqualTo("ADMIN");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should throw error when user not found by ID")
    void shouldThrowErrorWhenUserNotFoundById() {
        when(userRepository.findById(any(Long.class))).thenReturn(Mono.empty());

        StepVerifier.create(userService.getUserById(999L))
                .expectError(ResourceNotFoundException.class)
                .verify();
    }

    @Test
    @DisplayName("Should get user by email")
    void shouldGetUserByEmail() {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Mono.just(testUser));

        StepVerifier.create(userService.getUserByEmail("test@example.com"))
                .assertNext(response -> {
                    assertThat(response.getEmail()).isEqualTo("test@example.com");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should create new user")
    void shouldCreateNewUser() {
        UserRequest request = UserRequest.builder()
                .name("New User")
                .email("new@example.com")
                .password("password123")
                .role("EDITOR")
                .build();

        when(userRepository.existsByEmail("new@example.com")).thenReturn(Mono.just(false));
        when(passwordEncoder.encode("password123")).thenReturn("encodedPassword");
        when(idService.nextId()).thenReturn(987654321012345L);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            return Mono.just(user);
        });

        StepVerifier.create(userService.createUser(request))
                .assertNext(response -> {
                    assertThat(response.getName()).isEqualTo("New User");
                    assertThat(response.getEmail()).isEqualTo("new@example.com");
                    assertThat(response.getRole()).isEqualTo("EDITOR");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should throw error when email already exists")
    void shouldThrowErrorWhenEmailExists() {
        UserRequest request = UserRequest.builder()
                .name("New User")
                .email("existing@example.com")
                .password("password123")
                .build();

        when(userRepository.existsByEmail("existing@example.com")).thenReturn(Mono.just(true));

        StepVerifier.create(userService.createUser(request))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("Should update user role safely")
    void shouldUpdateUserRole() {
        RoleUpdateRequest request = new RoleUpdateRequest("VIEWER");
        String currentUserEmail = "admin@example.com";
        User adminUser = User.builder()
                .id(999L)
                .email(currentUserEmail)
                .role("ADMIN")
                .active(true)
                .build();

        when(userRepository.findByEmail(currentUserEmail)).thenReturn(Mono.just(adminUser));
        when(userRepository.findById(testUserId)).thenReturn(Mono.just(testUser));
        when(userRepository.countByRole("ADMIN")).thenReturn(Mono.just(2L));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(userService.updateUserRoleSafe(testUserId, request, currentUserEmail))
                .assertNext(response -> {
                    assertThat(response.getRole()).isEqualTo("VIEWER");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should delete user")
    void shouldDeleteUser() {
        when(userRepository.findById(testUserId)).thenReturn(Mono.just(testUser));
        when(userRepository.delete(testUser)).thenReturn(Mono.empty());

        StepVerifier.create(userService.deleteUser(testUserId))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should get all users with pagination")
    void shouldGetAllUsersWithPagination() {
        when(userRepository.findAllPaged(10, 0)).thenReturn(Flux.just(testUser));

        StepVerifier.create(userService.getAllUsers(0, 10).collectList())
                .assertNext(users -> {
                    assertThat(users).hasSize(1);
                    assertThat(users.get(0).getEmail()).isEqualTo("test@example.com");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should get users by role")
    void shouldGetUsersByRole() {
        when(userRepository.findByRole("ADMIN")).thenReturn(Flux.just(testUser));

        StepVerifier.create(userService.getUsersByRole("ADMIN").collectList())
                .assertNext(users -> {
                    assertThat(users).hasSize(1);
                    assertThat(users.get(0).getRole()).isEqualTo("ADMIN");
                })
                .verifyComplete();
    }

    // ==================== ADDED TESTS ====================

    @Test
    @DisplayName("Should get total users count")
    void shouldGetTotalUsers() {
        when(userRepository.countAll()).thenReturn(Mono.just(42L));

        StepVerifier.create(userService.getTotalUsers())
                .assertNext(count -> assertThat(count).isEqualTo(42L))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should throw error when email not found")
    void shouldThrowErrorWhenEmailNotFound() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Mono.empty());

        StepVerifier.create(userService.getUserByEmail("nobody@example.com"))
                .expectError(ResourceNotFoundException.class)
                .verify();
    }

    @Test
    @DisplayName("Should create user with default EDITOR role when role is null")
    void shouldCreateUserWithDefaultRole() {
        UserRequest request = UserRequest.builder()
                .name("Default Role User")
                .email("defaultrole@example.com")
                .password("pass123")
                .role(null)
                .build();

        when(userRepository.existsByEmail("defaultrole@example.com")).thenReturn(Mono.just(false));
        when(passwordEncoder.encode("pass123")).thenReturn("encodedPass");
        when(idService.nextId()).thenReturn(111L);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(userService.createUser(request))
                .assertNext(resp -> assertThat(resp.getRole()).isEqualTo("EDITOR"))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should reject createUser with invalid role")
    void shouldRejectCreateUserWithInvalidRole() {
        UserRequest request = UserRequest.builder()
                .name("Bad Role")
                .email("badrole@example.com")
                .password("pass")
                .role("SUPERUSER")
                .build();

        StepVerifier.create(userService.createUser(request))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("Should update user - same email")
    void shouldUpdateUserSameEmail() {
        UserRequest request = UserRequest.builder()
                .name("Updated Name")
                .email("test@example.com")
                .password("newpass")
                .role("ADMIN")
                .build();

        when(userRepository.findById(testUserId)).thenReturn(Mono.just(testUser));
        when(passwordEncoder.encode("newpass")).thenReturn("encodedNew");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(userService.updateUser(testUserId, request))
                .assertNext(r -> assertThat(r.getName()).isEqualTo("Updated Name"))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should update user - email change, new email available")
    void shouldUpdateUserEmailChange() {
        UserRequest request = UserRequest.builder()
                .name("Test User")
                .email("newemail@example.com")
                .password(null)
                .build();

        when(userRepository.findById(testUserId)).thenReturn(Mono.just(testUser));
        when(userRepository.existsByEmail("newemail@example.com")).thenReturn(Mono.just(false));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(userService.updateUser(testUserId, request))
                .assertNext(r -> assertThat(r.getEmail()).isEqualTo("newemail@example.com"))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should reject update when new email already exists")
    void shouldRejectUpdateWhenEmailConflict() {
        UserRequest request = UserRequest.builder()
                .name("Test User")
                .email("taken@example.com")
                .password(null)
                .build();

        when(userRepository.findById(testUserId)).thenReturn(Mono.just(testUser));
        when(userRepository.existsByEmail("taken@example.com")).thenReturn(Mono.just(true));

        StepVerifier.create(userService.updateUser(testUserId, request))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("Should reject update when user not found")
    void shouldRejectUpdateUserNotFound() {
        UserRequest request = UserRequest.builder().name("X").email("x@x.com").build();
        when(userRepository.findById(999L)).thenReturn(Mono.empty());

        StepVerifier.create(userService.updateUser(999L, request))
                .expectError(ResourceNotFoundException.class)
                .verify();
    }

    @Test
    @DisplayName("Should update user with invalid role returns error")
    void shouldRejectUpdateWithInvalidRole() {
        UserRequest request = UserRequest.builder()
                .name("Test")
                .email("test@example.com")
                .role("INVALID_ROLE")
                .build();

        when(userRepository.findById(testUserId)).thenReturn(Mono.just(testUser));

        StepVerifier.create(userService.updateUser(testUserId, request))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("Should update profile - name change only")
    void shouldUpdateProfileName() {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Mono.just(testUser));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        ProfileUpdateRequest request = new ProfileUpdateRequest("New Name", null, null, null, null, null, null);

        StepVerifier.create(userService.updateProfile("test@example.com", request))
                .assertNext(r -> assertThat(r.getName()).isEqualTo("New Name"))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should update profile - password change requires current password")
    void shouldRejectPasswordChangeWithoutCurrentPassword() {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Mono.just(testUser));

        ProfileUpdateRequest request = new ProfileUpdateRequest(null, null, null, null, null, null, "newPassword123!");

        StepVerifier.create(userService.updateProfile("test@example.com", request))
                .expectErrorMatches(e -> e instanceof org.springframework.web.server.ResponseStatusException
                        && e.getMessage().contains("current_password_required"))
                .verify();
    }

    @Test
    @DisplayName("Should update profile - reject wrong current password")
    void shouldRejectProfileWithWrongCurrentPassword() {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Mono.just(testUser));
        when(passwordEncoder.matches("wrongpass", "hashedPassword")).thenReturn(false);

        ProfileUpdateRequest request = new ProfileUpdateRequest(null, null, null, null, null, "wrongpass", "newPassword123!");

        StepVerifier.create(userService.updateProfile("test@example.com", request))
                .expectErrorMatches(e -> e instanceof org.springframework.web.server.ResponseStatusException
                        && e.getMessage().contains("current_password_incorrect"))
                .verify();
    }

    @Test
    @DisplayName("Should update profile - password change success")
    void shouldUpdateProfilePassword() {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Mono.just(testUser));
        when(passwordEncoder.matches("correctpass", "hashedPassword")).thenReturn(true);
        when(passwordEncoder.encode("newPassword123!")).thenReturn("newHashedPassword");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        ProfileUpdateRequest request = new ProfileUpdateRequest(null, null, null, null, null, "correctpass", "newPassword123!");

        StepVerifier.create(userService.updateProfile("test@example.com", request))
                .assertNext(r -> assertThat(r).isNotNull())
                .verifyComplete();
    }

    @Test
    @DisplayName("Should update profile - avatar URL validation rejects non-http")
    void shouldRejectBadAvatarUrl() {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Mono.just(testUser));

        ProfileUpdateRequest request = new ProfileUpdateRequest(null, null, null, "ftp://bad.url/avatar.png", null, null, null);

        StepVerifier.create(userService.updateProfile("test@example.com", request))
                .expectErrorMatches(e -> e instanceof org.springframework.web.server.ResponseStatusException
                        && e.getMessage().contains("Avatar URL"))
                .verify();
    }

    @Test
    @DisplayName("Should update profile - email change with conflict")
    void shouldRejectProfileEmailConflict() {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Mono.just(testUser));
        when(userRepository.existsByEmail("taken@example.com")).thenReturn(Mono.just(true));

        ProfileUpdateRequest request = new ProfileUpdateRequest(null, "taken@example.com", null, null, null, null, null);

        StepVerifier.create(userService.updateProfile("test@example.com", request))
                .expectErrorMatches(e -> e instanceof org.springframework.web.server.ResponseStatusException
                        && e.getMessage().contains("email_in_use"))
                .verify();
    }

    @Test
    @DisplayName("Should update profile - no changes returns existing user")
    void shouldReturnExistingUserWhenNoChanges() {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Mono.just(testUser));

        ProfileUpdateRequest request = new ProfileUpdateRequest(null, null, null, null, null, null, null);

        StepVerifier.create(userService.updateProfile("test@example.com", request))
                .assertNext(r -> assertThat(r.getEmail()).isEqualTo("test@example.com"))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should prevent self-demotion in updateUserRoleSafe")
    void shouldPreventSelfDemotion() {
        RoleUpdateRequest request = new RoleUpdateRequest("VIEWER");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Mono.just(testUser));

        StepVerifier.create(userService.updateUserRoleSafe(testUserId, request, "test@example.com"))
                .expectErrorMatches(e -> e instanceof IllegalArgumentException
                        && e.getMessage().contains("cannot_demote_self"))
                .verify();
    }

    @Test
    @DisplayName("Should prevent demoting last admin")
    void shouldPreventDemotingLastAdmin() {
        User targetAdmin = User.builder().id(testUserId).email("target@example.com").role("ADMIN").build();
        User callerAdmin = User.builder().id(999L).email("caller@example.com").role("ADMIN").build();

        when(userRepository.findByEmail("caller@example.com")).thenReturn(Mono.just(callerAdmin));
        when(userRepository.findById(testUserId)).thenReturn(Mono.just(targetAdmin));
        when(userRepository.countByRole("ADMIN")).thenReturn(Mono.just(1L));

        StepVerifier.create(userService.updateUserRoleSafe(testUserId, new RoleUpdateRequest("EDITOR"), "caller@example.com"))
                .expectErrorMatches(e -> e instanceof IllegalArgumentException
                        && e.getMessage().contains("cannot_demote_last_admin"))
                .verify();
    }

    @Test
    @DisplayName("Should delete user - not found")
    void shouldThrowWhenDeleteUserNotFound() {
        when(userRepository.findById(999L)).thenReturn(Mono.empty());

        StepVerifier.create(userService.deleteUser(999L))
                .expectError(ResourceNotFoundException.class)
                .verify();
    }

    @Test
    @DisplayName("Should prevent self-deletion in deleteUserSafe")
    void shouldPreventSelfDeletion() {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Mono.just(testUser));

        StepVerifier.create(userService.deleteUserSafe(testUserId, "test@example.com"))
                .expectErrorMatches(e -> e instanceof IllegalArgumentException
                        && e.getMessage().contains("cannot_delete_self"))
                .verify();
    }

    @Test
    @DisplayName("Should prevent deleting last admin in deleteUserSafe")
    void shouldPreventDeletingLastAdmin() {
        User callerAdmin = User.builder().id(999L).email("caller@example.com").role("ADMIN").build();
        User targetAdmin = User.builder().id(testUserId).email("target@example.com").role("ADMIN").build();

        when(userRepository.findByEmail("caller@example.com")).thenReturn(Mono.just(callerAdmin));
        when(userRepository.findById(testUserId)).thenReturn(Mono.just(targetAdmin));
        when(userRepository.countByRole("ADMIN")).thenReturn(Mono.just(1L));

        StepVerifier.create(userService.deleteUserSafe(testUserId, "caller@example.com"))
                .expectErrorMatches(e -> e instanceof IllegalArgumentException
                        && e.getMessage().contains("cannot_delete_last_admin"))
                .verify();
    }

    @Test
    @DisplayName("Should delete user safely when multiple admins exist")
    void shouldDeleteUserSafelyWhenMultipleAdmins() {
        User callerAdmin = User.builder().id(999L).email("caller@example.com").role("ADMIN").build();
        User targetAdmin = User.builder().id(testUserId).email("target@example.com").role("ADMIN").build();

        when(userRepository.findByEmail("caller@example.com")).thenReturn(Mono.just(callerAdmin));
        when(userRepository.findById(testUserId)).thenReturn(Mono.just(targetAdmin));
        when(userRepository.countByRole("ADMIN")).thenReturn(Mono.just(3L));
        when(userRepository.delete(targetAdmin)).thenReturn(Mono.empty());

        StepVerifier.create(userService.deleteUserSafe(testUserId, "caller@example.com"))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should delete non-admin user safely")
    void shouldDeleteNonAdminUserSafely() {
        User callerAdmin = User.builder().id(999L).email("caller@example.com").role("ADMIN").build();
        User targetEditor = User.builder().id(testUserId).email("editor@example.com").role("EDITOR").build();

        when(userRepository.findByEmail("caller@example.com")).thenReturn(Mono.just(callerAdmin));
        when(userRepository.findById(testUserId)).thenReturn(Mono.just(targetEditor));
        when(userRepository.delete(targetEditor)).thenReturn(Mono.empty());

        StepVerifier.create(userService.deleteUserSafe(testUserId, "caller@example.com"))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should count users by role")
    void shouldCountUsersByRole() {
        when(userRepository.countByRole("ADMIN")).thenReturn(Mono.just(5L));

        StepVerifier.create(userService.countUsersByRole("ADMIN"))
                .assertNext(count -> assertThat(count).isEqualTo(5L))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should activate user")
    void shouldActivateUser() {
        testUser.setActive(false);
        when(userRepository.findById(testUserId)).thenReturn(Mono.just(testUser));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(emailService.sendAccountReactivated(anyString(), anyString())).thenReturn(Mono.empty());

        StepVerifier.create(userService.activateUser(testUserId))
                .assertNext(r -> assertThat(r.getActive()).isTrue())
                .verifyComplete();
    }

    @Test
    @DisplayName("Should activate user - not found")
    void shouldThrowWhenActivateUserNotFound() {
        when(userRepository.findById(999L)).thenReturn(Mono.empty());

        StepVerifier.create(userService.activateUser(999L))
                .expectError(ResourceNotFoundException.class)
                .verify();
    }

    @Test
    @DisplayName("Should deactivate user")
    void shouldDeactivateUser() {
        User callerAdmin = User.builder().id(999L).email("admin@example.com").role("ADMIN").build();
        testUser.setActive(true);

        when(userRepository.findByEmail("admin@example.com")).thenReturn(Mono.just(callerAdmin));
        when(userRepository.findById(testUserId)).thenReturn(Mono.just(testUser));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(emailService.sendAccountDeactivated(anyString(), anyString())).thenReturn(Mono.empty());

        StepVerifier.create(userService.deactivateUser(testUserId, "admin@example.com"))
                .assertNext(r -> assertThat(r.getActive()).isFalse())
                .verifyComplete();
    }

    @Test
    @DisplayName("Should prevent self-deactivation")
    void shouldPreventSelfDeactivation() {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Mono.just(testUser));

        StepVerifier.create(userService.deactivateUser(testUserId, "test@example.com"))
                .expectErrorMatches(e -> e instanceof IllegalArgumentException
                        && e.getMessage().contains("cannot_deactivate_self"))
                .verify();
    }

    @Test
    @DisplayName("Should update profile with username and bio")
    void shouldUpdateProfileUsernameAndBio() {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Mono.just(testUser));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        ProfileUpdateRequest request = new ProfileUpdateRequest(null, null, "newusername", null, "My bio", null, null);

        StepVerifier.create(userService.updateProfile("test@example.com", request))
                .assertNext(r -> assertThat(r).isNotNull())
                .verifyComplete();
    }

    @Test
    @DisplayName("Should update profile - email change success")
    void shouldUpdateProfileEmailSuccess() {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Mono.just(testUser));
        when(userRepository.existsByEmail("newemail@example.com")).thenReturn(Mono.just(false));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        ProfileUpdateRequest request = new ProfileUpdateRequest(null, "newemail@example.com", null, null, null, null, null);

        StepVerifier.create(userService.updateProfile("test@example.com", request))
                .assertNext(r -> assertThat(r.getEmail()).isEqualTo("newemail@example.com"))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should update profile - user not found")
    void shouldFailProfileUpdateWhenUserNotFound() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Mono.empty());

        ProfileUpdateRequest request = new ProfileUpdateRequest("Name", null, null, null, null, null, null);

        StepVerifier.create(userService.updateProfile("ghost@example.com", request))
                .expectError(org.springframework.web.server.ResponseStatusException.class)
                .verify();
    }

    @Test
    @DisplayName("Should accept valid https avatar URL")
    void shouldAcceptValidAvatarUrl() {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Mono.just(testUser));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        ProfileUpdateRequest request = new ProfileUpdateRequest(null, null, null, "https://example.com/avatar.png", null, null, null);

        StepVerifier.create(userService.updateProfile("test@example.com", request))
                .assertNext(r -> assertThat(r).isNotNull())
                .verifyComplete();
    }
}
