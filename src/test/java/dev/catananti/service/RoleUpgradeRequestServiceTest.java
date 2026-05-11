package dev.catananti.service;

import dev.catananti.config.PaginationConfig;
import dev.catananti.dto.RoleUpgradeRequestDto;
import dev.catananti.entity.RoleUpgradeRequest;
import dev.catananti.entity.RoleUpgradeRequestStatus;
import dev.catananti.entity.User;
import dev.catananti.entity.UserRole;
import dev.catananti.exception.ResourceNotFoundException;
import dev.catananti.repository.RoleUpgradeRequestRepository;
import dev.catananti.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RoleUpgradeRequestService")
class RoleUpgradeRequestServiceTest {

    @Mock private RoleUpgradeRequestRepository roleUpgradeRequestRepository;
    @Mock private UserRepository userRepository;
    @Mock private UserService userService;
    @Mock private IdService idService;
    @Mock private HtmlSanitizerService htmlSanitizerService;
    @Mock private EmailService emailService;
    @Mock private PaginationConfig paginationConfig;

    private RoleUpgradeRequestService service;

    private User viewerUser;
    private User adminUser;

    @BeforeEach
    void setUp() {
        lenient().when(paginationConfig.getBulkQueryMax()).thenReturn(1000);
        service = new RoleUpgradeRequestService(
                roleUpgradeRequestRepository, userRepository, userService,
                idService, htmlSanitizerService, emailService, paginationConfig);

        viewerUser = User.builder()
                .id(100L).email("viewer@test.com").name("Viewer User").role("VIEWER").build();
        adminUser = User.builder()
                .id(1L).email("admin@test.com").name("Admin User").role("ADMIN").build();

        lenient().when(htmlSanitizerService.stripHtml(anyString())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(idService.nextId()).thenReturn(5001L);
    }

    @Nested
    @DisplayName("submitRequest")
    class SubmitRequest {

        @Test
        @DisplayName("should create request when no pending exists")
        void shouldCreateRequest() {
            when(userRepository.findByEmail("viewer@test.com")).thenReturn(Mono.just(viewerUser));
            when(roleUpgradeRequestRepository.findPendingByUserId(100L)).thenReturn(Mono.empty());
            when(roleUpgradeRequestRepository.save(any(RoleUpgradeRequest.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(userRepository.findByRole(eq("ADMIN"), anyInt())).thenReturn(Flux.just(adminUser));
            when(emailService.sendRoleUpgradeNotification(anyString(), anyString(), anyString(), anyString(), any()))
                    .thenReturn(Mono.empty());

            var dto = new RoleUpgradeRequestDto("DEV", "I want to write articles");

            StepVerifier.create(service.submitRequest("viewer@test.com", dto))
                    .assertNext(result -> {
                        assertThat(result.getRequestedRole()).isEqualTo("DEV");
                        assertThat(result.getUserEmail()).isEqualTo("viewer@test.com");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should reject when user already has pending request")
        void shouldRejectDuplicatePendingRequest() {
            when(userRepository.findByEmail("viewer@test.com")).thenReturn(Mono.just(viewerUser));
            when(roleUpgradeRequestRepository.findPendingByUserId(100L))
                    .thenReturn(Mono.just(RoleUpgradeRequest.builder().id(1L).status(RoleUpgradeRequestStatus.PENDING).build()));

            var dto = new RoleUpgradeRequestDto("DEV", "reason");

            StepVerifier.create(service.submitRequest("viewer@test.com", dto))
                    .expectError(ResponseStatusException.class)
                    .verify();
        }

        @Test
        @DisplayName("should reject when user not found")
        void shouldRejectWhenUserNotFound() {
            when(userRepository.findByEmail("unknown@test.com")).thenReturn(Mono.empty());

            var dto = new RoleUpgradeRequestDto("DEV", "reason");

            StepVerifier.create(service.submitRequest("unknown@test.com", dto))
                    .expectError(ResourceNotFoundException.class)
                    .verify();
        }
    }

    @Nested
    @DisplayName("countPending")
    class CountPending {

        @Test
        @DisplayName("should return count of pending requests")
        void shouldReturnCount() {
            when(roleUpgradeRequestRepository.countPending()).thenReturn(Mono.just(5L));

            StepVerifier.create(service.countPending())
                    .expectNext(5L)
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("approveRequest")
    class ApproveRequest {

        @Test
        @DisplayName("should approve pending request and update user role")
        void shouldApprovePendingRequest() {
            var request = RoleUpgradeRequest.builder()
                    .id(5001L).userId(100L).requestedRole("DEV").status(RoleUpgradeRequestStatus.PENDING)
                    .createdAt(LocalDateTime.now()).build();

            when(userRepository.findByEmail("admin@test.com")).thenReturn(Mono.just(adminUser));
            when(roleUpgradeRequestRepository.findById(5001L)).thenReturn(Mono.just(request));
            when(roleUpgradeRequestRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(userService.updateUserRoleSafe(eq(100L), any(), eq("admin@test.com")))
                    .thenReturn(Mono.empty());
            when(userRepository.findById(100L)).thenReturn(Mono.just(
                    User.builder().id(100L).email("viewer@test.com").name("Viewer User").role("DEV").build()));
            when(emailService.sendRoleRequestApproved(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(Mono.empty());

            StepVerifier.create(service.approveRequest(5001L, "admin@test.com"))
                    .assertNext(result -> {
                        assertThat(result.getRequestedRole()).isEqualTo("DEV");
                    })
                    .verifyComplete();

            verify(userService).updateUserRoleSafe(eq(100L), any(), eq("admin@test.com"));
        }

        @Test
        @DisplayName("should reject approval of non-pending request")
        void shouldRejectApprovalOfNonPending() {
            var request = RoleUpgradeRequest.builder()
                    .id(5001L).userId(100L).requestedRole("DEV").status(RoleUpgradeRequestStatus.APPROVED)
                    .createdAt(LocalDateTime.now()).build();

            when(userRepository.findByEmail("admin@test.com")).thenReturn(Mono.just(adminUser));
            when(roleUpgradeRequestRepository.findById(5001L)).thenReturn(Mono.just(request));

            StepVerifier.create(service.approveRequest(5001L, "admin@test.com"))
                    .expectError(ResponseStatusException.class)
                    .verify();
        }
    }

    @Nested
    @DisplayName("rejectRequest")
    class RejectRequest {

        @Test
        @DisplayName("should reject pending request")
        void shouldRejectPendingRequest() {
            var request = RoleUpgradeRequest.builder()
                    .id(5001L).userId(100L).requestedRole("DEV").status(RoleUpgradeRequestStatus.PENDING)
                    .createdAt(LocalDateTime.now()).build();

            when(userRepository.findByEmail("admin@test.com")).thenReturn(Mono.just(adminUser));
            when(roleUpgradeRequestRepository.findById(5001L)).thenReturn(Mono.just(request));
            when(roleUpgradeRequestRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(userRepository.findById(100L)).thenReturn(Mono.just(viewerUser));
            when(emailService.sendRoleRequestRejected(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(Mono.empty());

            StepVerifier.create(service.rejectRequest(5001L, "admin@test.com"))
                    .assertNext(result -> assertThat(result.getRequestedRole()).isEqualTo("DEV"))
                    .verifyComplete();
        }
    }
}
