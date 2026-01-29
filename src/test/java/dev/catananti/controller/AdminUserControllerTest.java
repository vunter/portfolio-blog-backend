package dev.catananti.controller;

import dev.catananti.dto.*;
import dev.catananti.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminUserControllerTest {

    @Mock
    private UserService userService;

    @InjectMocks
    private AdminUserController controller;

    private UserResponse adminUser;
    private UserResponse devUser;
    private UserResponse editorUser;
    private UserResponse viewerUser;
    private Authentication adminAuth;
    private Long adminId;
    private Long devId;

    @BeforeEach
    void setUp() {
        adminId = 1001L;
        devId = 1002L;

        adminUser = UserResponse.builder()
                .id(String.valueOf(adminId))
                .name("Admin User")
                .email("admin@catananti.dev")
                .role("ADMIN")
                .active(true)
                .createdAt(LocalDateTime.now().minusDays(30))
                .updatedAt(LocalDateTime.now())
                .build();

        devUser = UserResponse.builder()
                .id(String.valueOf(devId))
                .name("Dev User")
                .email("dev@catananti.dev")
                .role("DEV")
                .active(true)
                .createdAt(LocalDateTime.now().minusDays(15))
                .updatedAt(LocalDateTime.now())
                .build();

        editorUser = UserResponse.builder()
                .id("1003")
                .name("Editor User")
                .email("editor@catananti.dev")
                .role("EDITOR")
                .active(true)
                .createdAt(LocalDateTime.now().minusDays(7))
                .updatedAt(LocalDateTime.now())
                .build();

        viewerUser = UserResponse.builder()
                .id("1004")
                .name("Viewer User")
                .email("viewer@catananti.dev")
                .role("VIEWER")
                .active(false)
                .createdAt(LocalDateTime.now().minusDays(3))
                .updatedAt(LocalDateTime.now())
                .build();

        adminAuth = new UsernamePasswordAuthenticationToken(
                "admin@catananti.dev", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
    }

    @Nested
    @DisplayName("GET /api/v1/admin/users - List Users")
    class ListUsers {

        @Test
        @DisplayName("Should return paginated list of all users")
        void shouldReturnPaginatedUsers() {
            when(userService.getAllUsers(0, 20))
                    .thenReturn(Flux.just(adminUser, devUser, editorUser, viewerUser));
            when(userService.getTotalUsers()).thenReturn(Mono.just(4L));

            StepVerifier.create(controller.getAllUsers(0, 20, ""))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                        var body = response.getBody();
                        assertThat(body).isNotNull();
                        assertThat(body.getContent()).hasSize(4);
                        assertThat(body.getTotalElements()).isEqualTo(4);
                        assertThat(body.getPage()).isEqualTo(0);
                        assertThat(body.getSize()).isEqualTo(20);
                        assertThat(body.isFirst()).isTrue();
                        assertThat(body.isLast()).isTrue();
                    })
                    .verifyComplete();

            verify(userService).getAllUsers(0, 20);
            verify(userService).getTotalUsers();
        }

        @Test
        @DisplayName("Should handle empty user list")
        void shouldHandleEmptyUserList() {
            when(userService.getAllUsers(0, 20)).thenReturn(Flux.empty());
            when(userService.getTotalUsers()).thenReturn(Mono.just(0L));

            StepVerifier.create(controller.getAllUsers(0, 20, ""))
                    .assertNext(response -> {
                        var body = response.getBody();
                        assertThat(body).isNotNull();
                        assertThat(body.getContent()).isEmpty();
                        assertThat(body.getTotalElements()).isZero();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should calculate totalPages correctly with pagination")
        void shouldCalculateTotalPagesCorrectly() {
            when(userService.getAllUsers(0, 2))
                    .thenReturn(Flux.just(adminUser, devUser));
            when(userService.getTotalUsers()).thenReturn(Mono.just(5L));

            StepVerifier.create(controller.getAllUsers(0, 2, ""))
                    .assertNext(response -> {
                        var body = response.getBody();
                        assertThat(body).isNotNull();
                        assertThat(body.getTotalPages()).isEqualTo(3);
                        assertThat(body.isFirst()).isTrue();
                        assertThat(body.isLast()).isFalse();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should search users by name, email or role")
        void shouldSearchUsers() {
            when(userService.searchUsers("admin", 0, 20))
                    .thenReturn(Flux.just(adminUser));
            when(userService.countSearchUsers("admin")).thenReturn(Mono.just(1L));

            StepVerifier.create(controller.getAllUsers(0, 20, "admin"))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                        var body = response.getBody();
                        assertThat(body).isNotNull();
                        assertThat(body.getContent()).hasSize(1);
                        assertThat(body.getTotalElements()).isEqualTo(1);
                        assertThat(body.getTotalPages()).isEqualTo(1);
                    })
                    .verifyComplete();

            verify(userService).searchUsers("admin", 0, 20);
            verify(userService).countSearchUsers("admin");
            verify(userService, never()).getAllUsers(anyInt(), anyInt());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/users/me - Current User")
    class GetCurrentUser {

        @Test
        @DisplayName("Should return current authenticated user")
        void shouldReturnCurrentUser() {
            when(userService.getUserByEmail("admin@catananti.dev"))
                    .thenReturn(Mono.just(adminUser));

            StepVerifier.create(controller.getCurrentUser(adminAuth))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                        assertThat(response.getBody()).isNotNull();
                        assertThat(response.getBody().getEmail()).isEqualTo("admin@catananti.dev");
                        assertThat(response.getBody().getRole()).isEqualTo("ADMIN");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/users/{id} - Get by ID")
    class GetUserById {

        @Test
        @DisplayName("Should return user by ID")
        void shouldReturnUserById() {
            when(userService.getUserById(adminId)).thenReturn(Mono.just(adminUser));

            StepVerifier.create(controller.getUserById(adminId))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                        assertThat(response.getBody()).isNotNull();
                        assertThat(response.getBody().getName()).isEqualTo("Admin User");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/users/email/{email} - Get by Email")
    class GetUserByEmail {

        @Test
        @DisplayName("Should return user by email")
        void shouldReturnUserByEmail() {
            when(userService.getUserByEmail("dev@catananti.dev"))
                    .thenReturn(Mono.just(devUser));

            StepVerifier.create(controller.getUserByEmail("dev@catananti.dev"))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                        assertThat(response.getBody()).isNotNull();
                        assertThat(response.getBody().getRole()).isEqualTo("DEV");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/users/role/{role} - Get by Role")
    class GetUsersByRole {

        @Test
        @DisplayName("Should return users filtered by ADMIN role")
        void shouldReturnAdminUsers() {
            when(userService.getUsersByRole("ADMIN")).thenReturn(Flux.just(adminUser));
            when(userService.countUsersByRole("ADMIN")).thenReturn(Mono.just(1L));

            StepVerifier.create(controller.getUsersByRole("admin"))
                    .assertNext(response -> {
                        var body = response.getBody();
                        assertThat(body).isNotNull();
                        assertThat(body.getContent()).hasSize(1);
                        assertThat(body.getContent().getFirst().getRole()).isEqualTo("ADMIN");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return users filtered by DEV role")
        void shouldReturnDevUsers() {
            when(userService.getUsersByRole("DEV")).thenReturn(Flux.just(devUser));
            when(userService.countUsersByRole("DEV")).thenReturn(Mono.just(1L));

            StepVerifier.create(controller.getUsersByRole("dev"))
                    .assertNext(response -> {
                        var body = response.getBody();
                        assertThat(body).isNotNull();
                        assertThat(body.getContent()).hasSize(1);
                        assertThat(body.getContent().getFirst().getRole()).isEqualTo("DEV");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should convert role parameter to uppercase")
        void shouldConvertRoleToUppercase() {
            when(userService.getUsersByRole("VIEWER")).thenReturn(Flux.just(viewerUser));
            when(userService.countUsersByRole("VIEWER")).thenReturn(Mono.just(1L));

            StepVerifier.create(controller.getUsersByRole("viewer"))
                    .assertNext(response -> {
                        var body = response.getBody();
                        assertThat(body).isNotNull();
                        assertThat(body.getContent()).hasSize(1);
                    })
                    .verifyComplete();

            verify(userService).getUsersByRole("VIEWER");
        }
    }

    @Nested
    @DisplayName("POST /api/v1/admin/users - Create User")
    class CreateUser {

        @Test
        @DisplayName("Should create new user with VIEWER role")
        void shouldCreateViewerUser() {
            UserRequest request = UserRequest.builder()
                    .name("New Viewer")
                    .email("newviewer@example.com")
                    .password("Str0ng!Pass#12")
                    .role("VIEWER")
                    .build();

            when(userService.createUser(request)).thenReturn(Mono.just(viewerUser));

            StepVerifier.create(controller.createUser(request))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                        assertThat(response.getBody()).isNotNull();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should create new user with DEV role")
        void shouldCreateDevUser() {
            UserRequest request = UserRequest.builder()
                    .name("New Developer")
                    .email("newdev@catananti.dev")
                    .password("DevP@ssw0rd!123")
                    .role("DEV")
                    .build();

            when(userService.createUser(request)).thenReturn(Mono.just(devUser));

            StepVerifier.create(controller.createUser(request))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should create new user with EDITOR role")
        void shouldCreateEditorUser() {
            UserRequest request = UserRequest.builder()
                    .name("New Editor")
                    .email("neweditor@catananti.dev")
                    .password("Edit0r!Pass#999")
                    .role("EDITOR")
                    .build();

            when(userService.createUser(request)).thenReturn(Mono.just(editorUser));

            StepVerifier.create(controller.createUser(request))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("PUT /api/v1/admin/users/{id} - Update User")
    class UpdateUser {

        @Test
        @DisplayName("Should update user information")
        void shouldUpdateUser() {
            UserRequest request = UserRequest.builder()
                    .name("Updated Name")
                    .email("admin@catananti.dev")
                    .build();

            UserResponse updated = UserResponse.builder()
                    .id(String.valueOf(adminId))
                    .name("Updated Name")
                    .email("admin@catananti.dev")
                    .role("ADMIN")
                    .active(true)
                    .build();

            when(userService.updateUser(adminId, request)).thenReturn(Mono.just(updated));

            StepVerifier.create(controller.updateUser(adminId, request))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                        assertThat(response.getBody().getName()).isEqualTo("Updated Name");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("PUT /api/v1/admin/users/{id}/role - Update Role")
    class UpdateUserRole {

        @Test
        @DisplayName("Should update user role from EDITOR to DEV")
        void shouldUpdateRoleToDev() {
            RoleUpdateRequest request = new RoleUpdateRequest("DEV");
            UserResponse updated = UserResponse.builder()
                    .id("1003")
                    .name("Editor User")
                    .email("editor@catananti.dev")
                    .role("DEV")
                    .active(true)
                    .build();

            when(userService.updateUserRoleSafe(1003L, request, "admin@catananti.dev"))
                    .thenReturn(Mono.just(updated));

            StepVerifier.create(controller.updateUserRole(1003L, request, adminAuth))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                        assertThat(response.getBody().getRole()).isEqualTo("DEV");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should update user role from VIEWER to ADMIN")
        void shouldUpdateRoleToAdmin() {
            RoleUpdateRequest request = new RoleUpdateRequest("ADMIN");
            UserResponse updated = UserResponse.builder()
                    .id("1004")
                    .name("Viewer User")
                    .email("viewer@catananti.dev")
                    .role("ADMIN")
                    .active(true)
                    .build();

            when(userService.updateUserRoleSafe(1004L, request, "admin@catananti.dev"))
                    .thenReturn(Mono.just(updated));

            StepVerifier.create(controller.updateUserRole(1004L, request, adminAuth))
                    .assertNext(response -> {
                        assertThat(response.getBody().getRole()).isEqualTo("ADMIN");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/admin/users/{id} - Delete User")
    class DeleteUser {

        @Test
        @DisplayName("Should delete user and return success message")
        void shouldDeleteUser() {
            when(userService.deleteUserSafe(devId, "admin@catananti.dev"))
                    .thenReturn(Mono.empty());

            StepVerifier.create(controller.deleteUser(devId, adminAuth))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
                        assertThat(response.getBody()).isNull();
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/users/stats - User Statistics")
    class UserStats {

        @Test
        @DisplayName("Should return user statistics with all role counts")
        void shouldReturnUserStats() {
            when(userService.getTotalUsers()).thenReturn(Mono.just(25L));
            when(userService.countUsersByRole("ADMIN")).thenReturn(Mono.just(2L));
            when(userService.countUsersByRole("DEV")).thenReturn(Mono.just(5L));
            when(userService.countUsersByRole("EDITOR")).thenReturn(Mono.just(8L));
            when(userService.countUsersByRole("VIEWER")).thenReturn(Mono.just(10L));

            StepVerifier.create(controller.getUserStats())
                    .assertNext(response -> {
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                        var stats = response.getBody();
                        assertThat(stats).isNotNull();
                        assertThat(stats.getTotal()).isEqualTo(25);
                        assertThat(stats.getAdmins()).isEqualTo(2);
                        assertThat(stats.getDevs()).isEqualTo(5);
                        assertThat(stats.getEditors()).isEqualTo(8);
                        assertThat(stats.getViewers()).isEqualTo(10);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should handle zero users")
        void shouldHandleZeroUsers() {
            when(userService.getTotalUsers()).thenReturn(Mono.just(0L));
            when(userService.countUsersByRole("ADMIN")).thenReturn(Mono.just(0L));
            when(userService.countUsersByRole("DEV")).thenReturn(Mono.just(0L));
            when(userService.countUsersByRole("EDITOR")).thenReturn(Mono.just(0L));
            when(userService.countUsersByRole("VIEWER")).thenReturn(Mono.just(0L));

            StepVerifier.create(controller.getUserStats())
                    .assertNext(response -> {
                        var stats = response.getBody();
                        assertThat(stats).isNotNull();
                        assertThat(stats.getTotal()).isZero();
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("PUT /api/v1/admin/users/{id}/activate|deactivate")
    class ActivateDeactivate {

        @Test
        @DisplayName("Should activate user")
        void shouldActivateUser() {
            UserResponse activated = UserResponse.builder()
                    .id("1004")
                    .name("Viewer User")
                    .active(true)
                    .build();

            when(userService.activateUser(1004L)).thenReturn(Mono.just(activated));

            StepVerifier.create(controller.activateUser(1004L))
                    .assertNext(response -> {
                        assertThat(response.getBody()).isNotNull();
                        assertThat(response.getBody().getActive()).isTrue();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should deactivate user")
        void shouldDeactivateUser() {
            UserResponse deactivated = UserResponse.builder()
                    .id(String.valueOf(devId))
                    .name("Dev User")
                    .active(false)
                    .build();

            when(userService.deactivateUser(devId, "admin@catananti.dev"))
                    .thenReturn(Mono.just(deactivated));

            StepVerifier.create(controller.deactivateUser(devId, adminAuth))
                    .assertNext(response -> {
                        assertThat(response.getBody()).isNotNull();
                        assertThat(response.getBody().getActive()).isFalse();
                    })
                    .verifyComplete();
        }
    }
}
