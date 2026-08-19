package dev.catananti.service;

import dev.catananti.dto.ArticleRequest;
import dev.catananti.dto.ArticleResponse;
import dev.catananti.dto.PageResponse;
import dev.catananti.entity.Article;
import dev.catananti.entity.ArticleStatus;
import dev.catananti.entity.Tag;
import dev.catananti.entity.User;
import dev.catananti.exception.DuplicateResourceException;
import dev.catananti.exception.ResourceNotFoundException;
import dev.catananti.repository.ArticleRepository;
import dev.catananti.repository.ArticleReviewRepository;
import dev.catananti.repository.SubscriberRepository;
import dev.catananti.repository.TagRepository;
import dev.catananti.entity.Subscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ArticleAdminServiceTest {

    @Mock
    private ArticleRepository articleRepository;

    @Mock
    private ArticleReviewRepository articleReviewRepository;

    @Mock
    private TagRepository tagRepository;

    @Mock
    private R2dbcEntityTemplate r2dbcTemplate;

    @Mock
    private SubscriberRepository subscriberRepository;

    @Mock
    private EmailService emailService;

    @Mock
    private ArticleVersionService articleVersionService;

    @Mock
    private CurrentUserService currentUserService;

    @Mock
    private CacheService cacheService;

    @Mock
    private IdService idService;

    @Mock
    private NotificationEventService notificationEventService;

    @Mock
    private HtmlSanitizerService htmlSanitizerService;

    @Mock
    private ArticleService articleService;

    @Mock
    private dev.catananti.config.PaginationConfig paginationConfig;

    @Mock
    private org.springframework.transaction.reactive.TransactionalOperator transactionalOperator;

    @InjectMocks
    private ArticleAdminService articleAdminService;

    private <T> Mono<T> withAdminAuth(Mono<T> mono) {
        // ARCH-3: current-user resolution now flows through CurrentUserService instead of
        // UserRepository#findByEmail + the reactive security context. Stub the collaborator
        // directly. The security-context write is retained to document the auth intent.
        when(currentUserService.currentUser())
                .thenReturn(Mono.just(User.builder()
                        .id(1L).email("admin@test.com").name("Admin").role("ADMIN").build()));
        var auth = new UsernamePasswordAuthenticationToken("admin@test.com", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        return mono.contextWrite(ReactiveSecurityContextHolder.withSecurityContext(
                Mono.just(new SecurityContextImpl(auth))));
    }

    private Article testArticle;
    private ArticleResponse testArticleResponse;
    private Long articleId;

    @BeforeEach
    void setUp() {
        articleId = 1234567890123456L;

        // Every mutating op now invalidates the article read-through + feed caches (A6),
        // including create/update which previously did no cache invalidation.
        // lenient() because not all tests exercise a mutation path.
        lenient().when(cacheService.delete(anyString())).thenReturn(Mono.just(true));
        lenient().when(cacheService.invalidateArticle(anyString())).thenReturn(Mono.just(0L));
        lenient().when(cacheService.invalidateAllArticles()).thenReturn(Mono.just(0L));

        testArticle = Article.builder()
                .id(articleId)
                .slug("test-article")
                .title("Test Article")
                .subtitle("Sub")
                .content("Some content for the test article")
                .excerpt("Excerpt")
                .status(ArticleStatus.DRAFT)
                .readingTimeMinutes(1)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        testArticleResponse = ArticleResponse.builder()
                .id(articleId.toString())
                .slug("test-article")
                .title("Test Article")
                .status("DRAFT")
                .build();

        lenient().when(paginationConfig.getBulkQueryMax()).thenReturn(1000);

        // ARCH-3: default to "no authenticated user". This mirrors the pre-refactor behavior
        // where tests without an explicit security context had getCurrentUser() resolve empty,
        // so verifyOwnership(...) passes through (ADMIN/owner check is skipped). Tests that need
        // an admin override this via withAdminAuth(...).
        lenient().when(currentUserService.currentUser()).thenReturn(Mono.empty());

        // Pass-through TransactionalOperator: tests don't exercise rollback semantics
        lenient().when(transactionalOperator.transactional(any(Mono.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(paginationConfig.getFeedMaxItems()).thenReturn(100);

        // AUD19C-2: publish paths now claim the once-only notification CAS and fan out
        // to subscribers via the shared applyPublishSideEffects. Default: claim won,
        // no subscribers. Tests exercising the CAS gate override these.
        lenient().when(articleRepository.claimSubscriberNotification(anyLong(), any(LocalDateTime.class)))
                .thenReturn(Mono.just(1));
        lenient().when(subscriberRepository.findAllConfirmed(anyInt())).thenReturn(Flux.empty());
    }

    // ==================== getAllArticles ====================

    @Nested
    @DisplayName("getAllArticles")
    class GetAllArticles {

        // getAllArticles() captures getCurrentUser() eagerly at assembly time (the real
        // CurrentUserService.currentUser() defers to the reactive context, but the mock returns
        // a concrete Mono the moment it is called — before the withAdminAuth re-stub would apply).
        // Stub the admin user here so it is in place before each getAllArticles(...) call.
        @BeforeEach
        void authAsAdmin() {
            when(currentUserService.currentUser()).thenReturn(Mono.just(
                    User.builder().id(1L).email("admin@test.com").name("Admin").role("ADMIN").build()));
        }

        @Test
        @DisplayName("Should return all articles without status filter")
        void shouldReturnAllArticlesWithoutStatusFilter() {
            // Given
            when(articleRepository.findAllOrderByCreatedAtDesc(10, 0))
                    .thenReturn(Flux.just(testArticle));
            when(articleRepository.countAll()).thenReturn(Mono.just(1L));
            when(articleService.enrichArticlesWithMetadata(anyList()))
                    .thenReturn(Mono.just(List.of(testArticle)));
            when(articleService.mapToResponse(testArticle))
                    .thenReturn(testArticleResponse);

            // When & Then
            StepVerifier.create(articleAdminService.getAllArticles(0, 10, null, "newest"))
                    .assertNext(page -> {
                        assertThat(page.getContent()).hasSize(1);
                        assertThat(page.getTotalElements()).isEqualTo(1);
                        assertThat(page.getPage()).isZero();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should filter articles by status")
        void shouldFilterArticlesByStatus() {
            // Given
            when(articleRepository.findByStatusOrderByCreatedAtDesc("PUBLISHED", 10, 0))
                    .thenReturn(Flux.just(testArticle));
            when(articleRepository.countByStatus("PUBLISHED")).thenReturn(Mono.just(1L));
            when(articleService.enrichArticlesWithMetadata(anyList()))
                    .thenReturn(Mono.just(List.of(testArticle)));
            when(articleService.mapToResponse(testArticle))
                    .thenReturn(testArticleResponse);

            // When & Then
            StepVerifier.create(articleAdminService.getAllArticles(0, 10, "published", "newest"))
                    .assertNext(page -> {
                        assertThat(page.getContent()).hasSize(1);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return empty page when no articles")
        void shouldReturnEmptyPage() {
            // Given
            when(articleRepository.findAllOrderByCreatedAtDesc(10, 0))
                    .thenReturn(Flux.empty());
            when(articleRepository.countAll()).thenReturn(Mono.just(0L));
            when(articleService.enrichArticlesWithMetadata(anyList()))
                    .thenReturn(Mono.just(List.of()));

            // When & Then
            StepVerifier.create(articleAdminService.getAllArticles(0, 10, null, "newest"))
                    .assertNext(page -> {
                        assertThat(page.getContent()).isEmpty();
                        assertThat(page.getTotalElements()).isZero();
                    })
                    .verifyComplete();
        }
    }

    // ==================== getArticleById ====================

    @Nested
    @DisplayName("getArticleById")
    class GetArticleById {

        @Test
        @DisplayName("Should return article when found")
        void shouldReturnArticleWhenFound() {
            // Given
            when(articleRepository.findById(articleId)).thenReturn(Mono.just(testArticle));
            when(articleService.enrichArticleWithMetadata(testArticle))
                    .thenReturn(Mono.just(testArticle));
            when(articleService.mapToResponse(testArticle))
                    .thenReturn(testArticleResponse);

            // When & Then
            StepVerifier.create(withAdminAuth(articleAdminService.getArticleById(articleId)))
                    .assertNext(response -> {
                        assertThat(response.getSlug()).isEqualTo("test-article");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when not found")
        void shouldThrowWhenNotFound() {
            // Given
            when(articleRepository.findById(999L)).thenReturn(Mono.empty());

            // When & Then
            StepVerifier.create(articleAdminService.getArticleById(999L))
                    .expectError(ResourceNotFoundException.class)
                    .verify();
        }
    }

    // ==================== createArticle ====================

    @Nested
    @DisplayName("createArticle")
    class CreateArticle {

        private ArticleRequest draftRequest;

        @BeforeEach
        void setUpRequest() {
            draftRequest = ArticleRequest.builder()
                    .slug("new-article")
                    .title("New Article")
                    .subtitle("Subtitle")
                    .content("This is the content of the new article with enough words")
                    .excerpt("Excerpt")
                    .status("DRAFT")
                    .tagSlugs(List.of())
                    .build();
        }

        @Test
        @DisplayName("Should create draft article successfully")
        void shouldCreateDraftArticle() {
            // Given
            when(articleRepository.existsBySlug("new-article")).thenReturn(Mono.just(false));
            when(idService.nextId()).thenReturn(555L);
            when(htmlSanitizerService.stripHtml(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(htmlSanitizerService.sanitize(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(articleRepository.save(any(Article.class))).thenReturn(Mono.just(testArticle));
            when(articleService.enrichArticleWithMetadata(any(Article.class)))
                    .thenReturn(Mono.just(testArticle));
            when(articleService.mapToResponse(any(Article.class)))
                    .thenReturn(testArticleResponse);

            // When & Then
            StepVerifier.create(withAdminAuth(articleAdminService.createArticle(draftRequest)))
                    .assertNext(response -> {
                        assertThat(response.getSlug()).isEqualTo("test-article");
                    })
                    .verifyComplete();

            verify(articleRepository).save(any(Article.class));
        }

        @Test
        @DisplayName("Should auto-suffix slug on duplicate instead of throwing")
        void shouldThrowOnDuplicateSlug() {
            // Given - F-150: slug collision now auto-resolves with random suffix
            // existsBySlug("new-article") returns true, triggering auto-suffix
            when(articleRepository.existsBySlug(anyString())).thenReturn(Mono.just(true));
            when(articleRepository.existsBySlug("new-article")).thenReturn(Mono.just(true));
            when(idService.nextId()).thenReturn(556L);
            when(htmlSanitizerService.stripHtml(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(htmlSanitizerService.sanitize(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(articleRepository.save(any(Article.class))).thenReturn(Mono.just(testArticle));
            when(articleService.enrichArticleWithMetadata(any(Article.class)))
                    .thenReturn(Mono.just(testArticle));
            when(articleService.mapToResponse(any(Article.class)))
                    .thenReturn(testArticleResponse);

            // When & Then - should succeed with auto-suffixed slug
            StepVerifier.create(withAdminAuth(articleAdminService.createArticle(draftRequest)))
                    .assertNext(response -> {
                        assertThat(response).isNotNull();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should set SCHEDULED status when scheduledAt is provided")
        void shouldSetScheduledStatus() {
            // Given
            ArticleRequest scheduledRequest = ArticleRequest.builder()
                    .slug("scheduled-article")
                    .title("Scheduled")
                    .content("Content here for the scheduled article enough words now")
                    .status("DRAFT")
                    .scheduledAt(LocalDateTime.now().plusDays(1))
                    .tagSlugs(List.of())
                    .build();

            when(articleRepository.existsBySlug("scheduled-article")).thenReturn(Mono.just(false));
            when(idService.nextId()).thenReturn(556L);
            when(htmlSanitizerService.stripHtml(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(htmlSanitizerService.sanitize(anyString())).thenAnswer(inv -> inv.getArgument(0));

            Article savedArticle = Article.builder()
                    .id(556L)
                    .slug("scheduled-article")
                    .title("Scheduled")
                    .status(ArticleStatus.SCHEDULED)
                    .build();
            when(articleRepository.save(any(Article.class))).thenReturn(Mono.just(savedArticle));
            when(articleService.enrichArticleWithMetadata(any(Article.class)))
                    .thenReturn(Mono.just(savedArticle));

            ArticleResponse scheduledResponse = ArticleResponse.builder()
                    .slug("scheduled-article")
                    .status("SCHEDULED")
                    .build();
            when(articleService.mapToResponse(any(Article.class)))
                    .thenReturn(scheduledResponse);

            // When & Then
            StepVerifier.create(withAdminAuth(articleAdminService.createArticle(scheduledRequest)))
                    .assertNext(response -> {
                        assertThat(response.getStatus()).isEqualTo("SCHEDULED");
                    })
                    .verifyComplete();
        }
    }

    // ==================== updateArticle ====================

    @Nested
    @DisplayName("updateArticle")
    class UpdateArticle {

        @Test
        @DisplayName("Should update article successfully")
        void shouldUpdateArticle() {
            // Given
            ArticleRequest updateRequest = ArticleRequest.builder()
                    .slug("test-article")
                    .title("Updated Title")
                    .content("Updated content with enough words to be valid content here")
                    .status("DRAFT")
                    .build();

            when(articleRepository.findById(articleId)).thenReturn(Mono.just(testArticle));
            when(htmlSanitizerService.stripHtml(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(htmlSanitizerService.sanitize(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(articleRepository.save(any(Article.class))).thenReturn(Mono.just(testArticle));
            when(articleService.enrichArticleWithMetadata(any(Article.class)))
                    .thenReturn(Mono.just(testArticle));
            when(articleService.mapToResponse(any(Article.class)))
                    .thenReturn(testArticleResponse);

            // When & Then
            StepVerifier.create(articleAdminService.updateArticle(articleId, updateRequest))
                    .assertNext(response -> {
                        assertThat(response.getSlug()).isEqualTo("test-article");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when article not found")
        void shouldThrowWhenNotFound() {
            // Given
            ArticleRequest updateRequest = ArticleRequest.builder()
                    .slug("test")
                    .title("Title")
                    .content("Content")
                    .build();

            when(articleRepository.findById(999L)).thenReturn(Mono.empty());

            // When & Then
            StepVerifier.create(articleAdminService.updateArticle(999L, updateRequest))
                    .expectError(ResourceNotFoundException.class)
                    .verify();
        }
    }

    // ==================== deleteArticle ====================

    @Nested
    @DisplayName("deleteArticle")
    class DeleteArticle {

        @Test
        @DisplayName("Q3.2: Should delete article — DB ON DELETE CASCADE handles related rows")
        void shouldCascadeDeleteArticle() {
            // Q3.2: After the refactor, deleteArticle no longer issues explicit DELETEs
            // for tags/comments/bookmarks/etc. The DB ON DELETE CASCADE FKs handle
            // that atomically inside the @Transactional boundary. The test now only
            // verifies the surviving operations: findById, deleteById, cache invalidation.
            when(articleRepository.findById(articleId)).thenReturn(Mono.just(testArticle));
            when(articleRepository.deleteById(articleId)).thenReturn(Mono.empty());
            lenient().when(cacheService.delete(anyString())).thenReturn(Mono.just(true));

            StepVerifier.create(articleAdminService.deleteArticle(articleId))
                    .verifyComplete();

            verify(articleRepository).deleteById(articleId);
        }
    }

    // ==================== publishArticle ====================

    @Nested
    @DisplayName("publishArticle")
    class PublishArticle {

        @Test
        @DisplayName("Should publish article and change status")
        void shouldPublishArticle() {
            // Given
            Article draftArticle = Article.builder()
                    .id(articleId)
                    .slug("test-article")
                    .title("Test Article")
                    .status(ArticleStatus.DRAFT)
                    .build();

            Article publishedArticle = Article.builder()
                    .id(articleId)
                    .slug("test-article")
                    .title("Test Article")
                    .status(ArticleStatus.PUBLISHED)
                    .publishedAt(LocalDateTime.now())
                    .build();

            ArticleResponse publishedResponse = ArticleResponse.builder()
                    .id(articleId.toString())
                    .slug("test-article")
                    .status("PUBLISHED")
                    .build();

            when(articleRepository.findById(articleId)).thenReturn(Mono.just(draftArticle));
            when(articleRepository.save(any(Article.class))).thenReturn(Mono.just(publishedArticle));
            when(cacheService.delete(anyString())).thenReturn(Mono.just(true));
            when(subscriberRepository.findAllConfirmed(anyInt())).thenReturn(Flux.empty());
            when(articleService.enrichArticleWithMetadata(any(Article.class)))
                    .thenReturn(Mono.just(publishedArticle));
            when(articleService.mapToResponse(any(Article.class)))
                    .thenReturn(publishedResponse);

            // When & Then
            StepVerifier.create(articleAdminService.publishArticle(articleId))
                    .assertNext(response -> {
                        assertThat(response.getStatus()).isEqualTo("PUBLISHED");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when article not found")
        void shouldThrowWhenNotFound() {
            when(articleRepository.findById(999L)).thenReturn(Mono.empty());

            StepVerifier.create(articleAdminService.publishArticle(999L))
                    .expectError(ResourceNotFoundException.class)
                    .verify();
        }
    }

    // ==================== unpublishArticle ====================

    @Nested
    @DisplayName("unpublishArticle")
    class UnpublishArticle {

        @Test
        @DisplayName("Should unpublish article and set status to DRAFT")
        void shouldUnpublishArticle() {
            // Given
            Article publishedArticle = Article.builder()
                    .id(articleId)
                    .slug("test-article")
                    .status(ArticleStatus.PUBLISHED)
                    .build();

            ArticleResponse draftResponse = ArticleResponse.builder()
                    .id(articleId.toString())
                    .slug("test-article")
                    .status("DRAFT")
                    .build();

            when(articleRepository.findById(articleId)).thenReturn(Mono.just(publishedArticle));
            when(articleRepository.save(any(Article.class))).thenReturn(Mono.just(publishedArticle));
            when(cacheService.delete(anyString())).thenReturn(Mono.just(true));
            when(articleService.enrichArticleWithMetadata(any(Article.class)))
                    .thenReturn(Mono.just(publishedArticle));
            when(articleService.mapToResponse(any(Article.class)))
                    .thenReturn(draftResponse);

            // When & Then
            StepVerifier.create(articleAdminService.unpublishArticle(articleId))
                    .assertNext(response -> {
                        assertThat(response.getStatus()).isEqualTo("DRAFT");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when article not found")
        void shouldThrowWhenNotFound() {
            when(articleRepository.findById(999L)).thenReturn(Mono.empty());

            StepVerifier.create(articleAdminService.unpublishArticle(999L))
                    .expectError(ResourceNotFoundException.class)
                    .verify();
        }
    }

    // ==================== archiveArticle ====================

    @Nested
    @DisplayName("archiveArticle")
    class ArchiveArticle {

        @Test
        @DisplayName("Should archive article and set status to ARCHIVED")
        void shouldArchiveArticle() {
            // Given
            ArticleResponse archivedResponse = ArticleResponse.builder()
                    .id(articleId.toString())
                    .slug("test-article")
                    .status("ARCHIVED")
                    .build();

            when(articleRepository.findById(articleId)).thenReturn(Mono.just(testArticle));
            when(articleRepository.save(any(Article.class))).thenReturn(Mono.just(testArticle));
            when(cacheService.delete(anyString())).thenReturn(Mono.just(true));
            when(articleService.enrichArticleWithMetadata(any(Article.class)))
                    .thenReturn(Mono.just(testArticle));
            when(articleService.mapToResponse(any(Article.class)))
                    .thenReturn(archivedResponse);

            // When & Then
            StepVerifier.create(articleAdminService.archiveArticle(articleId))
                    .assertNext(response -> {
                        assertThat(response.getStatus()).isEqualTo("ARCHIVED");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when article not found")
        void shouldThrowWhenNotFound() {
            when(articleRepository.findById(999L)).thenReturn(Mono.empty());

            StepVerifier.create(articleAdminService.archiveArticle(999L))
                    .expectError(ResourceNotFoundException.class)
                    .verify();
        }
    }

    // ==================== AUD19C-1: scheduledAt on update ====================

    @Nested
    @DisplayName("AUD19C-1: scheduledAt handling on update")
    class ScheduledAtOnUpdate {

        private ArticleRequest.ArticleRequestBuilder baseRequest() {
            return ArticleRequest.builder()
                    .slug("test-article")
                    .title("Updated Title")
                    .content("Updated content with enough words to be valid content here");
        }

        private void stubSaveChain() {
            when(htmlSanitizerService.stripHtml(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(htmlSanitizerService.sanitize(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(articleService.enrichArticleWithMetadata(any(Article.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(articleService.mapToResponse(any(Article.class))).thenReturn(testArticleResponse);
        }

        @Test
        @DisplayName("DRAFT→SCHEDULED persists the requested date")
        void shouldPersistScheduledAtOnTransitionToScheduled() {
            LocalDateTime schedule = LocalDateTime.now().plusDays(2);
            ArticleRequest request = baseRequest().status("SCHEDULED").scheduledAt(schedule).build();

            when(articleRepository.findById(articleId)).thenReturn(Mono.just(testArticle));
            stubSaveChain();

            StepVerifier.create(articleAdminService.updateArticle(articleId, request))
                    .expectNextCount(1)
                    .verifyComplete();

            ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(ArticleStatus.SCHEDULED);
            assertThat(captor.getValue().getScheduledAt()).isEqualTo(schedule);
        }

        @Test
        @DisplayName("Reschedule replaces the existing date")
        void shouldReplaceExistingScheduleOnReschedule() {
            LocalDateTime oldSchedule = LocalDateTime.now().plusDays(1);
            LocalDateTime newSchedule = LocalDateTime.now().plusDays(7);
            testArticle.setStatus(ArticleStatus.SCHEDULED);
            testArticle.setScheduledAt(oldSchedule);
            ArticleRequest request = baseRequest().status("SCHEDULED").scheduledAt(newSchedule).build();

            when(articleRepository.findById(articleId)).thenReturn(Mono.just(testArticle));
            stubSaveChain();

            StepVerifier.create(articleAdminService.updateArticle(articleId, request))
                    .expectNextCount(1)
                    .verifyComplete();

            ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            assertThat(captor.getValue().getScheduledAt()).isEqualTo(newSchedule);
        }

        @Test
        @DisplayName("SCHEDULED with no date in request keeps the existing date")
        void shouldKeepExistingScheduleWhenRequestOmitsDate() {
            LocalDateTime existingSchedule = LocalDateTime.now().plusDays(3);
            testArticle.setStatus(ArticleStatus.SCHEDULED);
            testArticle.setScheduledAt(existingSchedule);
            ArticleRequest request = baseRequest().status("SCHEDULED").build();

            when(articleRepository.findById(articleId)).thenReturn(Mono.just(testArticle));
            stubSaveChain();

            StepVerifier.create(articleAdminService.updateArticle(articleId, request))
                    .expectNextCount(1)
                    .verifyComplete();

            ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            assertThat(captor.getValue().getScheduledAt()).isEqualTo(existingSchedule);
        }

        @Test
        @DisplayName("SCHEDULED→DRAFT clears the stale schedule")
        void shouldClearScheduleOnTransitionAwayFromScheduled() {
            testArticle.setStatus(ArticleStatus.SCHEDULED);
            testArticle.setScheduledAt(LocalDateTime.now().plusDays(1));
            ArticleRequest request = baseRequest().status("DRAFT").build();

            when(articleRepository.findById(articleId)).thenReturn(Mono.just(testArticle));
            stubSaveChain();

            StepVerifier.create(articleAdminService.updateArticle(articleId, request))
                    .expectNextCount(1)
                    .verifyComplete();

            ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            assertThat(captor.getValue().getScheduledAt()).isNull();
        }

        @Test
        @DisplayName("SCHEDULED with no date anywhere → error.scheduled_at_required")
        void shouldRejectScheduledWithoutAnyDate() {
            // testArticle is DRAFT with scheduledAt == null
            ArticleRequest request = baseRequest().status("SCHEDULED").build();

            when(articleRepository.findById(articleId)).thenReturn(Mono.just(testArticle));

            StepVerifier.create(articleAdminService.updateArticle(articleId, request))
                    .expectErrorMatches(e -> e instanceof IllegalArgumentException
                            && e.getMessage().equals("error.scheduled_at_required"))
                    .verify();

            verify(articleRepository, never()).save(any(Article.class));
        }

        @Test
        @DisplayName("SCHEDULED with a past date → error.scheduled_at_past")
        void shouldRejectPastScheduleDate() {
            ArticleRequest request = baseRequest()
                    .status("SCHEDULED")
                    .scheduledAt(LocalDateTime.now().minusHours(1))
                    .build();

            when(articleRepository.findById(articleId)).thenReturn(Mono.just(testArticle));

            StepVerifier.create(articleAdminService.updateArticle(articleId, request))
                    .expectErrorMatches(e -> e instanceof IllegalArgumentException
                            && e.getMessage().equals("error.scheduled_at_past"))
                    .verify();

            verify(articleRepository, never()).save(any(Article.class));
        }

        @Test
        @DisplayName("Create path parity: past scheduledAt on create → error.scheduled_at_past")
        void shouldRejectPastScheduleDateOnCreate() {
            ArticleRequest request = ArticleRequest.builder()
                    .slug("past-schedule")
                    .title("Past Schedule")
                    .content("Content here for the scheduled article enough words now")
                    .status("DRAFT")
                    .scheduledAt(LocalDateTime.now().minusHours(1))
                    .tagSlugs(List.of())
                    .build();

            when(articleRepository.existsBySlug("past-schedule")).thenReturn(Mono.just(false));

            StepVerifier.create(withAdminAuth(articleAdminService.createArticle(request)))
                    .expectErrorMatches(e -> e instanceof IllegalArgumentException
                            && e.getMessage().equals("error.scheduled_at_past"))
                    .verify();

            verify(articleRepository, never()).save(any(Article.class));
        }
    }

    // ==================== AUD19C-1: bulk status ====================

    @Nested
    @DisplayName("AUD19C-1: bulkUpdateStatus")
    class BulkUpdateStatus {

        // bulkUpdateStatus() captures getCurrentUser() eagerly at assembly time, so the
        // withAdminAuth(...) re-stub lands too late (same reason as GetAllArticles above).
        @BeforeEach
        void authAsAdmin() {
            // lenient: the SCHEDULED-rejection test short-circuits before resolving the user.
            lenient().when(currentUserService.currentUser()).thenReturn(Mono.just(
                    User.builder().id(1L).email("admin@test.com").name("Admin").role("ADMIN").build()));
        }

        @Test
        @DisplayName("SCHEDULED is rejected as a bulk target")
        void shouldRejectScheduledAsBulkTarget() {
            StepVerifier.create(articleAdminService.bulkUpdateStatus(List.of(1L, 2L), "SCHEDULED"))
                    .expectErrorMatches(e -> e instanceof IllegalArgumentException
                            && e.getMessage().equals("error.bulk_scheduled_not_allowed"))
                    .verify();

            verify(articleRepository, never()).findById(anyLong());
            verify(articleRepository, never()).save(any(Article.class));
        }

        @Test
        @DisplayName("Bulk publish clears stale schedules and applies shared side effects per article")
        void shouldApplySideEffectsOnBulkPublish() {
            testArticle.setStatus(ArticleStatus.SCHEDULED);
            testArticle.setScheduledAt(LocalDateTime.now().plusDays(1));

            when(articleRepository.findById(articleId)).thenReturn(Mono.just(testArticle));
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(articleAdminService.bulkUpdateStatus(List.of(articleId), "PUBLISHED"))
                    .expectNext(1L)
                    .verifyComplete();

            ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(ArticleStatus.PUBLISHED);
            assertThat(captor.getValue().getScheduledAt()).isNull();
            assertThat(captor.getValue().getPublishedAt()).isNotNull();
            // Shared side effects: SSE + notified_at CAS claim
            verify(notificationEventService).articlePublished(testArticle.getTitle(), testArticle.getSlug());
            verify(articleRepository).claimSubscriberNotification(eq(articleId), any(LocalDateTime.class));
        }
    }

    // ==================== AUD19C-2: publish side effects + review gate ====================

    @Nested
    @DisplayName("AUD19C-2: publish side effects and review gate")
    class PublishSideEffectsAndReviewGate {

        @Test
        @DisplayName("Publishing via PUT triggers SSE + notified_at claim + subscriber fan-out")
        void shouldApplySideEffectsWhenPublishingViaPut() {
            ArticleRequest request = ArticleRequest.builder()
                    .slug("test-article")
                    .title("Now Published")
                    .content("Updated content with enough words to be valid content here")
                    .status("PUBLISHED")
                    .build();

            Subscriber subscriber = Subscriber.builder()
                    .id(10L).email("sub@example.com").name("Sub").unsubscribeToken("tok").build();

            when(articleRepository.findById(articleId)).thenReturn(Mono.just(testArticle));
            when(htmlSanitizerService.stripHtml(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(htmlSanitizerService.sanitize(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(articleService.enrichArticleWithMetadata(any(Article.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(articleService.mapToResponse(any(Article.class))).thenReturn(testArticleResponse);
            when(subscriberRepository.findAllConfirmed(anyInt())).thenReturn(Flux.just(subscriber));
            when(emailService.sendNewArticleNotification(
                    anyString(), anyString(), anyString(), anyString(), any(), anyString()))
                    .thenReturn(Mono.empty());

            StepVerifier.create(articleAdminService.updateArticle(articleId, request))
                    .expectNextCount(1)
                    .verifyComplete();

            verify(notificationEventService).articlePublished(anyString(), eq("test-article"));
            verify(articleRepository).claimSubscriberNotification(eq(articleId), any(LocalDateTime.class));
            verify(emailService).sendNewArticleNotification(
                    eq("sub@example.com"), eq("Sub"), anyString(), eq("test-article"), any(), eq("tok"));

            ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(ArticleStatus.PUBLISHED);
            assertThat(captor.getValue().getPublishedAt()).isNotNull();
        }

        @Test
        @DisplayName("notified_at CAS prevents a second e-mail fan-out on republish")
        void shouldNotEmailTwiceWhenClaimAlreadyTaken() {
            Article article = Article.builder()
                    .id(articleId).slug("test-article").title("Test Article").excerpt("E")
                    .status(ArticleStatus.PUBLISHED).publishedAt(LocalDateTime.now()).build();

            Subscriber subscriber = Subscriber.builder()
                    .id(10L).email("sub@example.com").name("Sub").unsubscribeToken("tok").build();

            // First publish wins the claim, republish loses it.
            when(articleRepository.claimSubscriberNotification(eq(articleId), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(1), Mono.just(0));
            when(subscriberRepository.findAllConfirmed(anyInt())).thenReturn(Flux.just(subscriber));
            when(emailService.sendNewArticleNotification(
                    anyString(), anyString(), anyString(), anyString(), any(), anyString()))
                    .thenReturn(Mono.empty());

            StepVerifier.create(articleAdminService.applyPublishSideEffects(article))
                    .expectNext(article)
                    .verifyComplete();
            StepVerifier.create(articleAdminService.applyPublishSideEffects(article))
                    .expectNext(article)
                    .verifyComplete();

            // Exactly ONE fan-out despite two publishes.
            verify(emailService, times(1)).sendNewArticleNotification(
                    anyString(), anyString(), anyString(), anyString(), any(), anyString());
            verify(articleRepository, times(2))
                    .claimSubscriberNotification(eq(articleId), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("approveReview preserves an existing publishedAt (set-if-null)")
        void shouldPreservePublishedAtOnReApproval() {
            LocalDateTime originalPublishedAt = LocalDateTime.now().minusDays(30);
            testArticle.setStatus(ArticleStatus.REVIEW);
            testArticle.setPublishedAt(originalPublishedAt);

            when(articleRepository.findById(articleId)).thenReturn(Mono.just(testArticle));
            when(idService.nextId()).thenReturn(999L);
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(articleReviewRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(articleService.enrichArticleWithMetadata(any(Article.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(articleService.mapToResponse(any(Article.class))).thenReturn(testArticleResponse);

            StepVerifier.create(withAdminAuth(articleAdminService.approveReview(articleId)))
                    .expectNextCount(1)
                    .verifyComplete();

            ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(ArticleStatus.PUBLISHED);
            assertThat(captor.getValue().getPublishedAt()).isEqualTo(originalPublishedAt);
            // Shared side effects run for approval too.
            verify(notificationEventService).articlePublished(anyString(), eq("test-article"));
            verify(articleRepository).claimSubscriberNotification(eq(articleId), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("require-review-for-publish flag blocks a non-admin direct publish (403)")
        void shouldBlockNonAdminPublishWhenFlagEnabled() {
            ReflectionTestUtils.setField(articleAdminService, "requireReviewForPublish", true);
            testArticle.setAuthorId(2L);
            when(currentUserService.currentUser()).thenReturn(Mono.just(
                    User.builder().id(2L).email("dev@test.com").name("Dev").role("DEV").build()));
            when(articleRepository.findById(articleId)).thenReturn(Mono.just(testArticle));

            StepVerifier.create(articleAdminService.publishArticle(articleId))
                    .expectError(AccessDeniedException.class)
                    .verify();

            verify(articleRepository, never()).save(any(Article.class));
            verify(notificationEventService, never()).articlePublished(anyString(), anyString());
        }

        @Test
        @DisplayName("Hard rule: PUT blocks non-admin REVIEW→PUBLISHED even with the flag off")
        void shouldBlockNonAdminReviewToPublishedViaPut() {
            // requireReviewForPublish stays at its default (false)
            testArticle.setAuthorId(2L);
            testArticle.setStatus(ArticleStatus.REVIEW);
            when(currentUserService.currentUser()).thenReturn(Mono.just(
                    User.builder().id(2L).email("dev@test.com").name("Dev").role("DEV").build()));
            when(articleVersionService.createVersion(any(Article.class), anyString(), anyLong(), anyString()))
                    .thenReturn(Mono.empty());
            when(articleRepository.findById(articleId)).thenReturn(Mono.just(testArticle));

            ArticleRequest request = ArticleRequest.builder()
                    .slug("test-article")
                    .title("Sneaky Publish")
                    .content("Updated content with enough words to be valid content here")
                    .status("PUBLISHED")
                    .build();

            StepVerifier.create(articleAdminService.updateArticle(articleId, request))
                    .expectError(AccessDeniedException.class)
                    .verify();

            verify(articleRepository, never()).save(any(Article.class));
        }

        @Test
        @DisplayName("Unpublish clears schedule residue")
        void shouldClearScheduleOnUnpublish() {
            testArticle.setStatus(ArticleStatus.PUBLISHED);
            testArticle.setScheduledAt(LocalDateTime.now().plusDays(1));

            when(articleRepository.findById(articleId)).thenReturn(Mono.just(testArticle));
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(articleService.enrichArticleWithMetadata(any(Article.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(articleService.mapToResponse(any(Article.class))).thenReturn(testArticleResponse);

            StepVerifier.create(articleAdminService.unpublishArticle(articleId))
                    .expectNextCount(1)
                    .verifyComplete();

            ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(ArticleStatus.DRAFT);
            assertThat(captor.getValue().getScheduledAt()).isNull();
        }
    }

    // ==================== calculateReadingTime (via createArticle) ====================

    @Nested
    @DisplayName("calculateReadingTime")
    class CalculateReadingTime {

        /**
         * calculateReadingTime is private, so we test it indirectly through createArticle.
         * We verify the readingTimeMinutes field on the saved Article.
         */

        @Test
        @DisplayName("Should calculate reading time for short content (< 200 words = 1 min)")
        void shouldReturnOneMinuteForShortContent() {
            // Given — ~10 words
            ArticleRequest request = ArticleRequest.builder()
                    .slug("short")
                    .title("Short")
                    .content("word ".repeat(10).trim())
                    .status("DRAFT")
                    .tagSlugs(List.of())
                    .build();

            when(articleRepository.existsBySlug("short")).thenReturn(Mono.just(false));
            when(idService.nextId()).thenReturn(700L);
            when(htmlSanitizerService.stripHtml(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(htmlSanitizerService.sanitize(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(articleService.enrichArticleWithMetadata(any(Article.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(articleService.mapToResponse(any(Article.class)))
                    .thenAnswer(inv -> {
                        Article a = inv.getArgument(0);
                        return ArticleResponse.builder()
                                .readingTimeMinutes(a.getReadingTimeMinutes())
                                .build();
                    });

            // When & Then
            StepVerifier.create(withAdminAuth(articleAdminService.createArticle(request)))
                    .assertNext(response -> {
                        assertThat(response.getReadingTimeMinutes()).isEqualTo(1);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should calculate reading time for medium content (~600 words = 3 min)")
        void shouldReturnThreeMinutesForMediumContent() {
            // Given — ~600 words
            ArticleRequest request = ArticleRequest.builder()
                    .slug("medium")
                    .title("Medium")
                    .content("word ".repeat(600).trim())
                    .status("DRAFT")
                    .tagSlugs(List.of())
                    .build();

            when(articleRepository.existsBySlug("medium")).thenReturn(Mono.just(false));
            when(idService.nextId()).thenReturn(701L);
            when(htmlSanitizerService.stripHtml(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(htmlSanitizerService.sanitize(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(articleService.enrichArticleWithMetadata(any(Article.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(articleService.mapToResponse(any(Article.class)))
                    .thenAnswer(inv -> {
                        Article a = inv.getArgument(0);
                        return ArticleResponse.builder()
                                .readingTimeMinutes(a.getReadingTimeMinutes())
                                .build();
                    });

            // When & Then
            StepVerifier.create(withAdminAuth(articleAdminService.createArticle(request)))
                    .assertNext(response -> {
                        assertThat(response.getReadingTimeMinutes()).isEqualTo(3);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should calculate reading time for long content (~2000 words = 10 min)")
        void shouldReturnTenMinutesForLongContent() {
            // Given — ~2000 words
            ArticleRequest request = ArticleRequest.builder()
                    .slug("long-read")
                    .title("Long Read")
                    .content("word ".repeat(2000).trim())
                    .status("DRAFT")
                    .tagSlugs(List.of())
                    .build();

            when(articleRepository.existsBySlug("long-read")).thenReturn(Mono.just(false));
            when(idService.nextId()).thenReturn(702L);
            when(htmlSanitizerService.stripHtml(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(htmlSanitizerService.sanitize(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(articleService.enrichArticleWithMetadata(any(Article.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(articleService.mapToResponse(any(Article.class)))
                    .thenAnswer(inv -> {
                        Article a = inv.getArgument(0);
                        return ArticleResponse.builder()
                                .readingTimeMinutes(a.getReadingTimeMinutes())
                                .build();
                    });

            // When & Then
            StepVerifier.create(withAdminAuth(articleAdminService.createArticle(request)))
                    .assertNext(response -> {
                        assertThat(response.getReadingTimeMinutes()).isEqualTo(10);
                    })
                    .verifyComplete();
        }
    }
}
