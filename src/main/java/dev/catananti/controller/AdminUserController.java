package dev.catananti.controller;

import dev.catananti.dto.MessageResponse;
import dev.catananti.dto.PageResponse;
import dev.catananti.dto.ProfileUpdateRequest;
import dev.catananti.dto.RoleUpdateRequest;
import dev.catananti.dto.RoleUpgradeRequestDto;
import dev.catananti.dto.RoleUpgradeRequestResponse;
import dev.catananti.dto.UserRequest;
import dev.catananti.dto.UserResponse;
import dev.catananti.dto.UserStatsResponse;
import dev.catananti.service.RoleUpgradeRequestService;
import dev.catananti.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Users", description = "User management endpoints")
@SecurityRequirement(name = "Bearer Authentication")
@Slf4j
public class AdminUserController {

    private final UserService userService;
    private final RoleUpgradeRequestService roleUpgradeRequestService;

    @GetMapping
    @Operation(summary = "List all users", description = "Get paginated list of all users, optionally filtered by search query")
    public Mono<ResponseEntity<PageResponse<UserResponse>>> getAllUsers(
            @Parameter(description = "Page number (0-based)")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (max 100)")
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "Search by name, email or role")
            @RequestParam(required = false, defaultValue = "") String search) {
        log.debug("Admin fetching users: page={}, size={}, search={}", page, size, search);

        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        String trimmedSearch = search == null ? "" : search.trim();

        Flux<UserResponse> usersFlux;
        Mono<Long> countMono;

        if (trimmedSearch.isEmpty()) {
            usersFlux = userService.getAllUsers(safePage, safeSize);
            countMono = userService.getTotalUsers();
        } else {
            usersFlux = userService.searchUsers(trimmedSearch, safePage, safeSize);
            countMono = userService.countSearchUsers(trimmedSearch);
        }

        return usersFlux
                .collectList()
                .zipWith(countMono)
                .map(tuple -> {
                    var users = tuple.getT1();
                    var total = tuple.getT2();
                    int totalPages = (int) Math.ceil((double) total / safeSize);
                    
                    var response = PageResponse.<UserResponse>builder()
                            .content(users)
                            .page(safePage)
                            .size(safeSize)
                            .totalElements(total)
                            .totalPages(totalPages)
                            .first(safePage == 0)
                            .last(safePage >= totalPages - 1)
                            .build();
                    
                    return ResponseEntity.ok(response);
                });
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get current user", description = "Get the currently authenticated user's information")
    public Mono<ResponseEntity<UserResponse>> getCurrentUser(Authentication authentication) {
        log.debug("Fetching current user profile");
        return userService.getUserByEmail(authentication.getName())
                .map(ResponseEntity::ok);
    }

    @PutMapping("/me")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Update current user profile", description = "Update the currently authenticated user's profile information")
    public Mono<ResponseEntity<UserResponse>> updateCurrentUser(
            @Valid @RequestBody ProfileUpdateRequest request,
            Authentication authentication) {
        log.info("Updating current user profile");
        String currentEmail = authentication.getName();
        boolean emailChanging = request.email() != null && !request.email().isBlank()
                && !request.email().equalsIgnoreCase(currentEmail);
        return userService.updateProfile(currentEmail, request)
                .map(response -> {
                    if (emailChanging) {
                        return ResponseEntity.accepted()
                                .header("X-Email-Change-Pending", "true")
                                .body(response);
                    }
                    return ResponseEntity.ok(response);
                });
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get user by ID", description = "Get detailed information about a specific user")
    public Mono<ResponseEntity<UserResponse>> getUserById(
            @PathVariable Long id) {
        log.debug("Admin fetching user by id: {}", id);
        return userService.getUserById(id)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/email/{email}")
    @Operation(summary = "Get user by email", description = "Get user information by email address")
    public Mono<ResponseEntity<UserResponse>> getUserByEmail(
            @PathVariable String email) {
        log.debug("Admin fetching user by email: {}", email);
        return userService.getUserByEmail(email)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/role/{role}")
    @Operation(summary = "Get users by role", description = "Get all users with a specific role")
    public Mono<ResponseEntity<PageResponse<UserResponse>>> getUsersByRole(
            @PathVariable String role) {
        log.debug("Admin fetching users by role: {}", role);
        return userService.getUsersByRole(role.toUpperCase())
                .collectList()
                .zipWith(userService.countUsersByRole(role.toUpperCase()))
                .map(tuple -> {
                    var users = tuple.getT1();
                    var count = tuple.getT2();
                    
                    var response = PageResponse.<UserResponse>builder()
                            .content(users)
                            .page(0)
                            .size(users.size())
                            .totalElements(count)
                            .totalPages(1)
                            .first(true)
                            .last(true)
                            .build();
                    
                    return ResponseEntity.ok(response);
                });
    }

    @PostMapping
    @Operation(summary = "Create new user", description = "Create a new user account")
    public Mono<ResponseEntity<UserResponse>> createUser(
            @Valid @RequestBody UserRequest request) {
        log.info("Creating new user");
        return userService.createUser(request)
                .map(user -> ResponseEntity.status(HttpStatus.CREATED).body(user));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update user", description = "Update an existing user's information")
    public Mono<ResponseEntity<UserResponse>> updateUser(
            @PathVariable Long id,
            @Valid @RequestBody UserRequest request) {
        log.info("Updating user: id={}", id);
        return userService.updateUser(id, request)
                .map(ResponseEntity::ok);
    }

    @PutMapping("/{id}/role")
    @Operation(summary = "Update user role", description = "Change a user's role")
    public Mono<ResponseEntity<UserResponse>> updateUserRole(
            @PathVariable Long id,
            @Valid @RequestBody RoleUpdateRequest request,
            Authentication authentication) {
        log.info("Updating role for user: id={}", id);
        return userService.updateUserRoleSafe(id, request, authentication.getName())
                .map(ResponseEntity::ok);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete user", description = "Soft-delete a user account (deactivates instead of removing)")
    public Mono<ResponseEntity<UserResponse>> deleteUser(
            @PathVariable Long id,
            Authentication authentication) {
        log.info("Soft-deleting (deactivating) user: id={}", id);
        return userService.deactivateUser(id, authentication.getName())
                .map(ResponseEntity::ok);
    }

    @GetMapping("/stats")
    @Operation(summary = "Get user statistics", description = "Get user count by role")
    public Mono<ResponseEntity<UserStatsResponse>> getUserStats() {
        log.debug("Fetching user statistics");
        return Mono.zip(
                userService.getTotalUsers(),
                userService.countUsersByRole("ADMIN"),
                userService.countUsersByRole("DEV"),
                userService.countUsersByRole("EDITOR"),
                userService.countUsersByRole("VIEWER")
        ).map(tuple -> ResponseEntity.ok(
                UserStatsResponse.builder()
                        .total(tuple.getT1())
                        .admins(tuple.getT2())
                        .devs(tuple.getT3())
                        .editors(tuple.getT4())
                        .viewers(tuple.getT5())
                        .build()
        ));
    }

    @PutMapping("/{id}/activate")
    @Operation(summary = "Activate user", description = "Activate a deactivated user account")
    public Mono<ResponseEntity<UserResponse>> activateUser(@PathVariable Long id) {
        log.info("Activating user: id={}", id);
        return userService.activateUser(id)
                .map(ResponseEntity::ok);
    }

    @PutMapping("/{id}/deactivate")
    @Operation(summary = "Deactivate user", description = "Deactivate a user account")
    public Mono<ResponseEntity<UserResponse>> deactivateUser(
            @PathVariable Long id,
            Authentication authentication) {
        log.info("Deactivating user: id={}", id);
        return userService.deactivateUser(id, authentication.getName())
                .map(ResponseEntity::ok);
    }

    /**
     * F-140: Get user activity summary.
     * Note: Full activity tracking (last_login_at, action_count columns) requires a schema migration.
     * Currently returns data available from audit logs.
     */
    @GetMapping("/{id}/activity")
    @Operation(summary = "Get user activity", description = "Get user activity summary from audit logs")
    public Mono<ResponseEntity<java.util.Map<String, Object>>> getUserActivity(@PathVariable Long id) {
        log.debug("Fetching activity for user: id={}", id);
        return userService.getUserById(id)
                .flatMap(user -> {
                    // Derive activity from audit logs
                    var result = new java.util.HashMap<String, Object>();
                    result.put("userId", user.getId());
                    result.put("email", user.getEmail());
                    result.put("active", user.getActive());
                    result.put("createdAt", user.getCreatedAt());
                    result.put("note", "Full activity tracking (last_login_at, action_count) requires schema migration to add columns to users table");
                    return Mono.just(ResponseEntity.ok((java.util.Map<String, Object>) result));
                });
    }

    // ============================================
    // Role Upgrade Requests
    // ============================================

    @PostMapping("/me/role-request")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Submit role upgrade request", description = "Submit a request to upgrade the user's role")
    public Mono<ResponseEntity<RoleUpgradeRequestResponse>> submitRoleUpgradeRequest(
            @Valid @RequestBody RoleUpgradeRequestDto request,
            Authentication authentication) {
        log.info("Role upgrade request submitted by {}", authentication.getName());
        return roleUpgradeRequestService.submitRequest(authentication.getName(), request)
                .map(resp -> ResponseEntity.status(HttpStatus.CREATED).body(resp));
    }

    @GetMapping("/me/role-request")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get my latest role upgrade request", description = "Get the current user's latest role upgrade request status")
    public Mono<ResponseEntity<RoleUpgradeRequestResponse>> getMyRoleUpgradeRequest(
            Authentication authentication) {
        return roleUpgradeRequestService.getMyLatestRequest(authentication.getName())
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.noContent().build());
    }

    @GetMapping("/role-requests")
    @Operation(summary = "List pending role upgrade requests", description = "List all pending role upgrade requests (admin only)")
    public Mono<ResponseEntity<java.util.List<RoleUpgradeRequestResponse>>> getPendingRoleRequests() {
        return roleUpgradeRequestService.getAllPending()
                .collectList()
                .map(ResponseEntity::ok);
    }

    @PutMapping("/role-requests/{id}/approve")
    @Operation(summary = "Approve role upgrade request", description = "Approve and execute a role upgrade request")
    public Mono<ResponseEntity<RoleUpgradeRequestResponse>> approveRoleRequest(
            @PathVariable Long id,
            Authentication authentication) {
        log.info("Approving role upgrade request: id={}", id);
        return roleUpgradeRequestService.approveRequest(id, authentication.getName())
                .map(ResponseEntity::ok);
    }

    @PutMapping("/role-requests/{id}/reject")
    @Operation(summary = "Reject role upgrade request", description = "Reject a role upgrade request")
    public Mono<ResponseEntity<RoleUpgradeRequestResponse>> rejectRoleRequest(
            @PathVariable Long id,
            Authentication authentication) {
        log.info("Rejecting role upgrade request: id={}", id);
        return roleUpgradeRequestService.rejectRequest(id, authentication.getName())
                .map(ResponseEntity::ok);
    }
}
