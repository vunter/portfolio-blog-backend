package dev.catananti.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Entity coverage tests — covers business methods, builders, equals/hashCode,
 * Persistable, and enum logic across all entities with non-trivial methods.
 */
@DisplayName("Entity Coverage Tests")
class EntityCoverageTest {

    // ==================== Article ====================

    @Nested
    @DisplayName("Article")
    class ArticleTests {

        @Test
        @DisplayName("Builder should create article with default values")
        void shouldCreateArticleWithDefaults() {
            Article article = Article.builder()
                    .id(1L)
                    .slug("test-slug")
                    .title("Test Title")
                    .build();

            assertThat(article.getId()).isEqualTo(1L);
            assertThat(article.getSlug()).isEqualTo("test-slug");
            assertThat(article.getStatus()).isEqualTo("DRAFT");
            assertThat(article.getViewsCount()).isZero();
            assertThat(article.getLikesCount()).isZero();
            assertThat(article.getOriginalLocale()).isEqualTo("en");
            assertThat(article.isNewRecord()).isTrue();
        }

        @Test
        @DisplayName("incrementViews should increase view count")
        void shouldIncrementViews() {
            Article article = Article.builder().id(1L).viewsCount(5).build();
            article.incrementViews();
            assertThat(article.getViewsCount()).isEqualTo(6);
        }

        @Test
        @DisplayName("incrementViews should handle null view count")
        void shouldHandleNullViewCount() {
            Article article = Article.builder().id(1L).build();
            article.setViewsCount(null);
            article.incrementViews();
            assertThat(article.getViewsCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("incrementLikes should increase like count")
        void shouldIncrementLikes() {
            Article article = Article.builder().id(1L).likesCount(10).build();
            article.incrementLikes();
            assertThat(article.getLikesCount()).isEqualTo(11);
        }

        @Test
        @DisplayName("incrementLikes should handle null like count")
        void shouldHandleNullLikeCount() {
            Article article = Article.builder().id(1L).build();
            article.setLikesCount(null);
            article.incrementLikes();
            assertThat(article.getLikesCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("isScheduled should return true for scheduled articles")
        void shouldReturnTrueForScheduledArticle() {
            Article article = Article.builder()
                    .id(1L)
                    .status("SCHEDULED")
                    .scheduledAt(LocalDateTime.now().plusDays(1))
                    .build();

            assertThat(article.isScheduled()).isTrue();
        }

        @Test
        @DisplayName("isScheduled should return false for non-scheduled status")
        void shouldReturnFalseForNonScheduledStatus() {
            Article article = Article.builder()
                    .id(1L)
                    .status("DRAFT")
                    .scheduledAt(LocalDateTime.now().plusDays(1))
                    .build();

            assertThat(article.isScheduled()).isFalse();
        }

        @Test
        @DisplayName("isScheduled should return false when scheduledAt is null")
        void shouldReturnFalseWhenScheduledAtNull() {
            Article article = Article.builder()
                    .id(1L)
                    .status("SCHEDULED")
                    .build();

            assertThat(article.isScheduled()).isFalse();
        }

        @Test
        @DisplayName("shouldPublishNow should return true for past scheduled time")
        void shouldPublishNowForPastScheduledTime() {
            Article article = Article.builder()
                    .id(1L)
                    .status("SCHEDULED")
                    .scheduledAt(LocalDateTime.now().minusMinutes(5))
                    .build();

            assertThat(article.shouldPublishNow()).isTrue();
        }

        @Test
        @DisplayName("shouldPublishNow should return false for future scheduled time")
        void shouldNotPublishNowForFutureScheduledTime() {
            Article article = Article.builder()
                    .id(1L)
                    .status("SCHEDULED")
                    .scheduledAt(LocalDateTime.now().plusDays(1))
                    .build();

            assertThat(article.shouldPublishNow()).isFalse();
        }

        @Test
        @DisplayName("shouldPublishNow should return false for DRAFT status")
        void shouldNotPublishNowForDraft() {
            Article article = Article.builder()
                    .id(1L)
                    .status("DRAFT")
                    .build();

            assertThat(article.shouldPublishNow()).isFalse();
        }

        @Test
        @DisplayName("isNew should delegate to newRecord flag")
        void shouldDelegateIsNewToNewRecord() {
            Article article = Article.builder().id(1L).build();
            assertThat(article.isNew()).isTrue();

            article.setNewRecord(false);
            assertThat(article.isNew()).isFalse();
        }

        @Test
        @DisplayName("equals should use id only")
        void shouldUseIdForEquals() {
            Article a1 = Article.builder().id(1L).title("Title A").build();
            Article a2 = Article.builder().id(1L).title("Title B").build();
            Article a3 = Article.builder().id(2L).title("Title A").build();

            assertThat(a1).isEqualTo(a2);
            assertThat(a1).isNotEqualTo(a3);
        }

        @Test
        @DisplayName("hashCode should use id only")
        void shouldUseIdForHashCode() {
            Article a1 = Article.builder().id(1L).title("Title A").build();
            Article a2 = Article.builder().id(1L).title("Title B").build();

            assertThat(a1.hashCode()).isEqualTo(a2.hashCode());
        }
    }

    // ==================== PasswordResetToken ====================

    @Nested
    @DisplayName("PasswordResetToken")
    class PasswordResetTokenTests {

        @Test
        @DisplayName("isValid should return true for unexpired, unused token")
        void shouldReturnTrueForValidToken() {
            PasswordResetToken token = PasswordResetToken.builder()
                    .id(1L)
                    .expiresAt(LocalDateTime.now().plusHours(1))
                    .used(false)
                    .build();

            assertThat(token.isValid()).isTrue();
        }

        @Test
        @DisplayName("isValid should return false for expired token")
        void shouldReturnFalseForExpiredToken() {
            PasswordResetToken token = PasswordResetToken.builder()
                    .id(1L)
                    .expiresAt(LocalDateTime.now().minusHours(1))
                    .used(false)
                    .build();

            assertThat(token.isValid()).isFalse();
        }

        @Test
        @DisplayName("isValid should return false for used token")
        void shouldReturnFalseForUsedToken() {
            PasswordResetToken token = PasswordResetToken.builder()
                    .id(1L)
                    .expiresAt(LocalDateTime.now().plusHours(1))
                    .used(true)
                    .build();

            assertThat(token.isValid()).isFalse();
        }

        @Test
        @DisplayName("isValid should return true when used is null (not yet used)")
        void shouldReturnTrueWhenUsedIsNull() {
            PasswordResetToken token = PasswordResetToken.builder()
                    .id(1L)
                    .expiresAt(LocalDateTime.now().plusHours(1))
                    .used(null)
                    .build();

            assertThat(token.isValid()).isTrue();
        }

        @Test
        @DisplayName("isExpired should return true when past expiry")
        void shouldReturnTrueWhenExpired() {
            PasswordResetToken token = PasswordResetToken.builder()
                    .expiresAt(LocalDateTime.now().minusMinutes(1))
                    .build();

            assertThat(token.isExpired()).isTrue();
        }

        @Test
        @DisplayName("isExpired should return false when before expiry")
        void shouldReturnFalseWhenNotExpired() {
            PasswordResetToken token = PasswordResetToken.builder()
                    .expiresAt(LocalDateTime.now().plusHours(1))
                    .build();

            assertThat(token.isExpired()).isFalse();
        }

        @Test
        @DisplayName("isNew should delegate to newRecord flag")
        void shouldDelegateIsNew() {
            PasswordResetToken token = PasswordResetToken.builder().id(1L).build();
            assertThat(token.isNew()).isTrue();

            token.setNewRecord(false);
            assertThat(token.isNew()).isFalse();
        }
    }

    // ==================== RefreshToken ====================

    @Nested
    @DisplayName("RefreshToken")
    class RefreshTokenTests {

        @Test
        @DisplayName("isValid should return true for non-revoked, unexpired token")
        void shouldReturnTrueForValidToken() {
            RefreshToken token = RefreshToken.builder()
                    .id(1L)
                    .expiresAt(LocalDateTime.now().plusDays(7))
                    .revoked(false)
                    .build();

            assertThat(token.isValid()).isTrue();
        }

        @Test
        @DisplayName("isValid should return false for revoked token")
        void shouldReturnFalseForRevokedToken() {
            RefreshToken token = RefreshToken.builder()
                    .id(1L)
                    .expiresAt(LocalDateTime.now().plusDays(7))
                    .revoked(true)
                    .build();

            assertThat(token.isValid()).isFalse();
        }

        @Test
        @DisplayName("isValid should return false for expired token")
        void shouldReturnFalseForExpiredToken() {
            RefreshToken token = RefreshToken.builder()
                    .id(1L)
                    .expiresAt(LocalDateTime.now().minusDays(1))
                    .revoked(false)
                    .build();

            assertThat(token.isValid()).isFalse();
        }

        @Test
        @DisplayName("isExpired should detect expired tokens")
        void shouldDetectExpiredToken() {
            RefreshToken expired = RefreshToken.builder()
                    .expiresAt(LocalDateTime.now().minusMinutes(1))
                    .build();
            RefreshToken valid = RefreshToken.builder()
                    .expiresAt(LocalDateTime.now().plusHours(1))
                    .build();

            assertThat(expired.isExpired()).isTrue();
            assertThat(valid.isExpired()).isFalse();
        }

        @Test
        @DisplayName("isNew should delegate to newRecord flag")
        void shouldDelegateIsNew() {
            RefreshToken token = RefreshToken.builder().id(1L).build();
            assertThat(token.isNew()).isTrue();

            token.setNewRecord(false);
            assertThat(token.isNew()).isFalse();
        }
    }

    // ==================== Subscriber ====================

    @Nested
    @DisplayName("Subscriber")
    class SubscriberTests {

        @Test
        @DisplayName("isConfirmed should return true for CONFIRMED status")
        void shouldReturnTrueForConfirmed() {
            Subscriber sub = Subscriber.builder().status("CONFIRMED").build();
            assertThat(sub.isConfirmed()).isTrue();
        }

        @Test
        @DisplayName("isConfirmed should return false for PENDING status")
        void shouldReturnFalseForPending() {
            Subscriber sub = Subscriber.builder().status("PENDING").build();
            assertThat(sub.isConfirmed()).isFalse();
        }

        @Test
        @DisplayName("isActive should return true for CONFIRMED status")
        void shouldReturnTrueForActive() {
            Subscriber sub = Subscriber.builder().status("CONFIRMED").build();
            assertThat(sub.isActive()).isTrue();
        }

        @Test
        @DisplayName("isActive should return false for UNSUBSCRIBED status")
        void shouldReturnFalseForUnsubscribed() {
            Subscriber sub = Subscriber.builder().status("UNSUBSCRIBED").build();
            assertThat(sub.isActive()).isFalse();
        }

        @Test
        @DisplayName("default status should be PENDING")
        void shouldHaveDefaultPendingStatus() {
            Subscriber sub = Subscriber.builder().id(1L).email("test@test.com").build();
            assertThat(sub.getStatus()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("isNew should delegate to newRecord flag")
        void shouldDelegateIsNew() {
            Subscriber sub = Subscriber.builder().id(1L).build();
            assertThat(sub.isNew()).isTrue();

            sub.setNewRecord(false);
            assertThat(sub.isNew()).isFalse();
        }
    }

    // ==================== User ====================

    @Nested
    @DisplayName("User")
    class UserTests {

        @Test
        @DisplayName("Builder should create user with default values")
        void shouldCreateUserWithDefaults() {
            User user = User.builder()
                    .id(1L)
                    .email("test@example.com")
                    .name("Test User")
                    .build();

            assertThat(user.getRole()).isEqualTo("VIEWER");
            assertThat(user.getActive()).isTrue();
            assertThat(user.isNewRecord()).isTrue();
        }

        @Test
        @DisplayName("equals should use id only")
        void shouldUseIdForEquals() {
            User u1 = User.builder().id(1L).email("a@test.com").build();
            User u2 = User.builder().id(1L).email("b@test.com").build();
            User u3 = User.builder().id(2L).email("a@test.com").build();

            assertThat(u1).isEqualTo(u2);
            assertThat(u1).isNotEqualTo(u3);
        }

        @Test
        @DisplayName("hashCode should use id only")
        void shouldUseIdForHashCode() {
            User u1 = User.builder().id(1L).email("a@test.com").build();
            User u2 = User.builder().id(1L).email("b@test.com").build();

            assertThat(u1.hashCode()).isEqualTo(u2.hashCode());
        }

        @Test
        @DisplayName("isNew should delegate to newRecord flag")
        void shouldDelegateIsNew() {
            User user = User.builder().id(1L).build();
            assertThat(user.isNew()).isTrue();

            user.setNewRecord(false);
            assertThat(user.isNew()).isFalse();
        }
    }

    // ==================== Comment ====================

    @Nested
    @DisplayName("Comment")
    class CommentTests {

        @Test
        @DisplayName("Builder should create comment with default values")
        void shouldCreateCommentWithDefaults() {
            Comment comment = Comment.builder()
                    .id(1L)
                    .content("Great article!")
                    .build();

            assertThat(comment.getStatus()).isEqualTo("PENDING");
            assertThat(comment.getReplies()).isNotNull().isEmpty();
            assertThat(comment.isNewRecord()).isTrue();
        }

        @Test
        @DisplayName("equals should use id only")
        void shouldUseIdForEquals() {
            Comment c1 = Comment.builder().id(1L).content("A").build();
            Comment c2 = Comment.builder().id(1L).content("B").build();

            assertThat(c1).isEqualTo(c2);
        }
    }

    // ==================== Status Enums ====================

    @Nested
    @DisplayName("SubscriberStatus enum")
    class SubscriberStatusTests {

        @Test
        @DisplayName("matches should return true for matching status string")
        void shouldMatchForMatchingStatus() {
            assertThat(SubscriberStatus.PENDING.matches("PENDING")).isTrue();
            assertThat(SubscriberStatus.CONFIRMED.matches("CONFIRMED")).isTrue();
            assertThat(SubscriberStatus.UNSUBSCRIBED.matches("UNSUBSCRIBED")).isTrue();
        }

        @Test
        @DisplayName("matches should return false for non-matching status string")
        void shouldNotMatchForDifferentStatus() {
            assertThat(SubscriberStatus.PENDING.matches("CONFIRMED")).isFalse();
            assertThat(SubscriberStatus.CONFIRMED.matches("PENDING")).isFalse();
        }

        @Test
        @DisplayName("matches should return false for null")
        void shouldNotMatchNull() {
            assertThat(SubscriberStatus.PENDING.matches(null)).isFalse();
        }

        @Test
        @DisplayName("matches should be case-sensitive")
        void shouldBeCaseSensitive() {
            assertThat(SubscriberStatus.PENDING.matches("pending")).isFalse();
        }

        @Test
        @DisplayName("Should have exactly 3 values")
        void shouldHaveThreeValues() {
            assertThat(SubscriberStatus.values()).hasSize(3);
        }
    }

    @Nested
    @DisplayName("ArticleStatus enum")
    class ArticleStatusTests {

        @Test
        @DisplayName("matches should return true for matching status")
        void shouldMatchForMatchingStatus() {
            assertThat(ArticleStatus.DRAFT.matches("DRAFT")).isTrue();
            assertThat(ArticleStatus.PUBLISHED.matches("PUBLISHED")).isTrue();
            assertThat(ArticleStatus.SCHEDULED.matches("SCHEDULED")).isTrue();
            assertThat(ArticleStatus.ARCHIVED.matches("ARCHIVED")).isTrue();
        }

        @Test
        @DisplayName("matches should return false for non-matching")
        void shouldNotMatchForDifferent() {
            assertThat(ArticleStatus.DRAFT.matches("PUBLISHED")).isFalse();
        }

        @Test
        @DisplayName("Should have exactly 4 values")
        void shouldHaveFourValues() {
            assertThat(ArticleStatus.values()).hasSize(4);
        }
    }

    @Nested
    @DisplayName("CommentStatus enum")
    class CommentStatusTests {

        @Test
        @DisplayName("matches should work for all values")
        void shouldMatchAllValues() {
            assertThat(CommentStatus.PENDING.matches("PENDING")).isTrue();
            assertThat(CommentStatus.APPROVED.matches("APPROVED")).isTrue();
            assertThat(CommentStatus.REJECTED.matches("REJECTED")).isTrue();
            assertThat(CommentStatus.SPAM.matches("SPAM")).isTrue();
        }

        @Test
        @DisplayName("matches should return false for wrong value")
        void shouldNotMatchWrongValue() {
            assertThat(CommentStatus.PENDING.matches("APPROVED")).isFalse();
        }

        @Test
        @DisplayName("Should have exactly 4 values")
        void shouldHaveFourValues() {
            assertThat(CommentStatus.values()).hasSize(4);
        }
    }

    @Nested
    @DisplayName("UserRole enum")
    class UserRoleTests {

        @Test
        @DisplayName("matches should work for all values")
        void shouldMatchAllValues() {
            assertThat(UserRole.ADMIN.matches("ADMIN")).isTrue();
            assertThat(UserRole.DEV.matches("DEV")).isTrue();
            assertThat(UserRole.EDITOR.matches("EDITOR")).isTrue();
            assertThat(UserRole.VIEWER.matches("VIEWER")).isTrue();
        }

        @Test
        @DisplayName("matches should return false for wrong value")
        void shouldNotMatchWrongValue() {
            assertThat(UserRole.ADMIN.matches("VIEWER")).isFalse();
        }

        @Test
        @DisplayName("Should have exactly 4 values")
        void shouldHaveFourValues() {
            assertThat(UserRole.values()).hasSize(4);
        }
    }

    @Nested
    @DisplayName("ResumeTemplateStatus enum")
    class ResumeTemplateStatusTests {

        @Test
        @DisplayName("matches should work for all values")
        void shouldMatchAllValues() {
            assertThat(ResumeTemplateStatus.DRAFT.matches("DRAFT")).isTrue();
            assertThat(ResumeTemplateStatus.ACTIVE.matches("ACTIVE")).isTrue();
            assertThat(ResumeTemplateStatus.ARCHIVED.matches("ARCHIVED")).isTrue();
        }

        @Test
        @DisplayName("Should have exactly 3 values")
        void shouldHaveThreeValues() {
            assertThat(ResumeTemplateStatus.values()).hasSize(3);
        }
    }

    // ==================== LocalizedText ====================

    @Nested
    @DisplayName("LocalizedText")
    class LocalizedTextTests {

        @Test
        @DisplayName("ofEnglish should create English-only text")
        void shouldCreateEnglishOnly() {
            LocalizedText text = LocalizedText.ofEnglish("Hello");
            assertThat(text.getDefault()).isEqualTo("Hello");
            assertThat(text.get("en")).isEqualTo("Hello");
        }

        @Test
        @DisplayName("of should create single locale text")
        void shouldCreateSingleLocale() {
            LocalizedText text = LocalizedText.of("pt-br", "Olá");
            assertThat(text.get("pt-br")).isEqualTo("Olá");
        }

        @Test
        @DisplayName("get should fall back to English when locale not found")
        void shouldFallbackToEnglish() {
            LocalizedText text = LocalizedText.ofEnglish("Hello");
            assertThat(text.get("fr")).isEqualTo("Hello");
        }

        @Test
        @DisplayName("get should fall back to any available when no English")
        void shouldFallbackToAny() {
            LocalizedText text = LocalizedText.of("pt-br", "Olá");
            assertThat(text.get("fr")).isEqualTo("Olá");
        }

        @Test
        @DisplayName("get should return null when empty")
        void shouldReturnNullWhenEmpty() {
            LocalizedText text = new LocalizedText();
            assertThat(text.get("en")).isNull();
        }

        @Test
        @DisplayName("get should handle null locale by falling back")
        void shouldHandleNullLocale() {
            LocalizedText text = LocalizedText.ofEnglish("Hello");
            assertThat(text.get(null)).isEqualTo("Hello");
        }

        @Test
        @DisplayName("put should add translation")
        void shouldAddTranslation() {
            LocalizedText text = LocalizedText.ofEnglish("Hello");
            text.put("es", "Hola");

            assertThat(text.get("es")).isEqualTo("Hola");
            assertThat(text.get("en")).isEqualTo("Hello");
        }

        @Test
        @DisplayName("getTranslations should return unmodifiable map")
        void shouldReturnUnmodifiableMap() {
            LocalizedText text = LocalizedText.ofEnglish("Hello");
            Map<String, String> translations = text.getTranslations();

            assertThat(translations).containsEntry("en", "Hello");
            assertThatThrownBy(() -> translations.put("es", "Hola"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("isEmpty should detect empty translations")
        void shouldDetectEmpty() {
            assertThat(new LocalizedText().isEmpty()).isTrue();
            assertThat(LocalizedText.ofEnglish("Hello").isEmpty()).isFalse();
        }

        @Test
        @DisplayName("toJson should serialize to JSON")
        void shouldSerializeToJson() {
            LocalizedText text = LocalizedText.ofEnglish("Hello");
            String json = text.toJson();

            assertThat(json).contains("\"en\"");
            assertThat(json).contains("\"Hello\"");
        }

        @Test
        @DisplayName("fromJson should deserialize from JSON")
        void shouldDeserializeFromJson() {
            LocalizedText text = LocalizedText.fromJson("{\"en\":\"Hello\",\"es\":\"Hola\"}");

            assertThat(text.get("en")).isEqualTo("Hello");
            assertThat(text.get("es")).isEqualTo("Hola");
        }

        @Test
        @DisplayName("fromJson should handle null input")
        void shouldHandleNullJson() {
            LocalizedText text = LocalizedText.fromJson(null);
            assertThat(text.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("fromJson should handle blank input")
        void shouldHandleBlankJson() {
            LocalizedText text = LocalizedText.fromJson("  ");
            assertThat(text.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("fromJson should treat invalid JSON as English text")
        void shouldTreatInvalidJsonAsEnglishText() {
            LocalizedText text = LocalizedText.fromJson("just plain text");
            assertThat(text.getDefault()).isEqualTo("just plain text");
        }

        @Test
        @DisplayName("of should handle null value")
        void shouldHandleNullValue() {
            LocalizedText text = LocalizedText.of("en", null);
            assertThat(text.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("constructor should handle null map")
        void shouldHandleNullMap() {
            LocalizedText text = new LocalizedText(null);
            assertThat(text.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("toString should return default locale value")
        void shouldReturnDefaultInToString() {
            LocalizedText text = LocalizedText.ofEnglish("Hello");
            assertThat(text.toString()).isEqualTo("Hello");
        }

        @Test
        @DisplayName("equals should compare translations")
        void shouldCompareTranslations() {
            LocalizedText a = LocalizedText.ofEnglish("Hello");
            LocalizedText b = LocalizedText.ofEnglish("Hello");
            LocalizedText c = LocalizedText.ofEnglish("World");

            assertThat(a).isEqualTo(b);
            assertThat(a).isNotEqualTo(c);
        }

        @Test
        @DisplayName("hashCode should be consistent with equals")
        void shouldHaveConsistentHashCode() {
            LocalizedText a = LocalizedText.ofEnglish("Hello");
            LocalizedText b = LocalizedText.ofEnglish("Hello");

            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("equals should handle null and different types")
        void shouldHandleNullAndDifferentTypes() {
            LocalizedText text = LocalizedText.ofEnglish("Hello");

            assertThat(text).isNotEqualTo(null);
            assertThat(text).isNotEqualTo("Hello");
        }

        @Test
        @DisplayName("equals should be reflexive")
        void shouldBeReflexive() {
            LocalizedText text = LocalizedText.ofEnglish("Hello");
            assertThat(text).isEqualTo(text);
        }
    }

    // ==================== AuditLog ====================

    @Nested
    @DisplayName("AuditLog")
    class AuditLogTests {

        @Test
        @DisplayName("Builder should create audit log")
        void shouldCreateAuditLog() {
            AuditLog log = AuditLog.builder()
                    .id(1L)
                    .action("CREATE")
                    .entityType("ARTICLE")
                    .entityId("42")
                    .performedBy(1L)
                    .performedByEmail("admin@example.com")
                    .details("Created article")
                    .ipAddress("127.0.0.1")
                    .createdAt(LocalDateTime.now())
                    .build();

            assertThat(log.getAction()).isEqualTo("CREATE");
            assertThat(log.getEntityType()).isEqualTo("ARTICLE");
            assertThat(log.getIpAddress()).isEqualTo("127.0.0.1");
            assertThat(log.isNewRecord()).isTrue();
        }

        @Test
        @DisplayName("isNew should delegate to newRecord flag")
        void shouldDelegateIsNew() {
            AuditLog log = AuditLog.builder().id(1L).build();
            assertThat(log.isNew()).isTrue();

            log.setNewRecord(false);
            assertThat(log.isNew()).isFalse();
        }
    }
}
