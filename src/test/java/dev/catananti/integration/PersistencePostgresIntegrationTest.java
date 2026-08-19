package dev.catananti.integration;

import dev.catananti.entity.Comment;
import dev.catananti.repository.CommentRepository;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests against a real PostgreSQL provisioned exclusively by the
 * Flyway migrations — the same path a disaster-recovery restore or a brand-new
 * environment takes. Guards three production defects:
 *
 * <ul>
 *   <li>Flyway must migrate a virgin database end-to-end (V1 previously aborted
 *       on an index over a column that only existed in a dead duplicate block).</li>
 *   <li>The schema produced by the migrations must contain every column the
 *       entities map (comments.likes_count previously existed only in schema.sql).</li>
 *   <li>{@code CommentRepository.findApprovedRepliesByParentIds} must expand its
 *       IN parameter — a {@code Long[]} binds as a single bigint[] and fails on
 *       PostgreSQL, so the signature must take a {@code Collection}.</li>
 * </ul>
 */
class PersistencePostgresIntegrationTest {

    /**
     * Prefers an externally provided PostgreSQL (IT_POSTGRES_HOST/PORT/DB/USER/PASSWORD env
     * vars — must point at an EMPTY database) so the test can run where Testcontainers
     * cannot reach the Docker daemon; otherwise spins up its own container, or skips when
     * neither is available.
     */
    static PostgreSQLContainer<?> postgres;

    static R2dbcEntityTemplate template;
    static CommentRepository commentRepository;

    @BeforeAll
    static void migrateAndConnect() {
        String host = System.getenv("IT_POSTGRES_HOST");
        String port;
        String db;
        String user;
        String password;
        if (host != null) {
            port = System.getenv().getOrDefault("IT_POSTGRES_PORT", "5432");
            db = System.getenv().getOrDefault("IT_POSTGRES_DB", "blog");
            user = System.getenv().getOrDefault("IT_POSTGRES_USER", "postgres");
            password = System.getenv().getOrDefault("IT_POSTGRES_PASSWORD", "test");
        } else {
            Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                    "Neither IT_POSTGRES_HOST nor a Docker daemon is available");
            postgres = new PostgreSQLContainer<>("postgres:16-alpine");
            postgres.start();
            host = postgres.getHost();
            port = String.valueOf(postgres.getMappedPort(5432));
            db = postgres.getDatabaseName();
            user = postgres.getUsername();
            password = postgres.getPassword();
        }

        Flyway.configure()
                .dataSource("jdbc:postgresql://" + host + ":" + port + "/" + db, user, password)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        ConnectionFactory connectionFactory = ConnectionFactories.get(
                "r2dbc:postgresql://" + user + ":" + password + "@" + host + ":" + port + "/" + db);
        template = new R2dbcEntityTemplate(connectionFactory);
        commentRepository = new R2dbcRepositoryFactory(template).getRepository(CommentRepository.class);
    }

    @AfterAll
    static void tearDown() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private static void execute(String sql) {
        template.getDatabaseClient().sql(sql).then().block(Duration.ofSeconds(10));
    }

    @Test
    void flywayMigrationsProvisionVirginDatabase() {
        Long tables = template.getDatabaseClient()
                .sql("SELECT COUNT(*) AS c FROM information_schema.tables WHERE table_schema = 'public'")
                .map(row -> row.get("c", Long.class))
                .one()
                .block(Duration.ofSeconds(10));
        assertThat(tables).isGreaterThan(30);
    }

    @Test
    void commentsTableHasLikesCountColumnUsedByRepository() {
        execute("INSERT INTO users (id, email, name, password_hash, role) VALUES (100, 'likes@test.dev', 'Likes', 'x', 'ADMIN') ON CONFLICT DO NOTHING");
        execute("INSERT INTO articles (id, title, slug, content, status, author_id) VALUES (100, 'a', 'likes-a', 'c', 'PUBLISHED', 100) ON CONFLICT DO NOTHING");
        execute("INSERT INTO comments (id, article_id, author_name, author_email, content, status) VALUES (100, 100, 'n', 'e@test.dev', 'root', 'APPROVED') ON CONFLICT DO NOTHING");
        // reset so the assertion is deterministic when the test reuses an external database
        execute("UPDATE comments SET likes_count = 0 WHERE id = 100");

        StepVerifier.create(commentRepository.incrementLikes(100L))
                .verifyComplete();
        StepVerifier.create(commentRepository.getLikesCount(100L))
                .expectNext(1)
                .verifyComplete();
    }

    @Test
    void emailVerificationTokensTableExistsWithExpectedColumns() {
        Long cols = template.getDatabaseClient()
                .sql("SELECT COUNT(*) AS c FROM information_schema.columns "
                   + "WHERE table_name = 'email_verification_tokens' "
                   + "AND column_name IN ('id','user_id','email','token','expires_at','used','used_at','created_at')")
                .map(row -> row.get("c", Long.class))
                .one()
                .block(Duration.ofSeconds(10));
        assertThat(cols).isEqualTo(8L);
    }

    /**
     * Round-trip via the actual repository. Guards a silent bug class: entities
     * with pre-assigned ids that do not implement Persistable make save() run an
     * UPDATE against a nonexistent row — zero rows affected, no error raised, and
     * the "saved" token never reaches the database.
     */
    @Test
    void emailVerificationTokenSaveActuallyInserts() {
        execute("INSERT INTO users (id, email, name, password_hash, role) VALUES (400, 'evt@test.dev', 'Evt', 'x', 'VIEWER') ON CONFLICT DO NOTHING");
        execute("DELETE FROM email_verification_tokens WHERE user_id = 400");

        var repo = new R2dbcRepositoryFactory(template)
                .getRepository(dev.catananti.repository.EmailVerificationTokenRepository.class);
        var token = dev.catananti.entity.EmailVerificationToken.builder()
                .id(400400L)
                .userId(400L)
                .email("evt@test.dev")
                .token("hash-e2e-roundtrip")
                .expiresAt(java.time.LocalDateTime.now().plusHours(1))
                .used(false)
                .createdAt(java.time.LocalDateTime.now())
                .build();

        StepVerifier.create(repo.save(token).then(repo.findByTokenAndUsedFalse("hash-e2e-roundtrip")))
                .assertNext(found -> assertThat(found.getUserId()).isEqualTo(400L))
                .verifyComplete();
    }

    @Test
    void subscribersAndUsersGainedTheLinkColumns() {
        Long subscriberCols = template.getDatabaseClient()
                .sql("SELECT COUNT(*) AS c FROM information_schema.columns "
                   + "WHERE table_name = 'subscribers' "
                   + "AND column_name IN ('user_id','linked_at','link_origin','unlinked_at','unlinked_by')")
                .map(row -> row.get("c", Long.class))
                .one()
                .block(Duration.ofSeconds(10));
        assertThat(subscriberCols).isEqualTo(5L);

        Long userCols = template.getDatabaseClient()
                .sql("SELECT COUNT(*) AS c FROM information_schema.columns "
                   + "WHERE table_name = 'users' "
                   + "AND column_name IN ('analytics_consent','analytics_consent_at')")
                .map(row -> row.get("c", Long.class))
                .one()
                .block(Duration.ofSeconds(10));
        assertThat(userCols).isEqualTo(2L);
    }

    /**
     * The V20 backfill must be idempotent (a restore may replay it): running the
     * same UPDATE twice over an eligible pair produces exactly one link and does
     * not touch the row again on the second pass.
     */
    @Test
    void v20BackfillLinksEligiblePairsAndIsIdempotent() {
        execute("INSERT INTO users (id, email, name, password_hash, role, email_verified) "
              + "VALUES (300, 'backfill@test.dev', 'Backfill', 'x', 'VIEWER', true) ON CONFLICT DO NOTHING");
        execute("INSERT INTO subscribers (id, email, status, confirmed_at) "
              + "VALUES (300, 'backfill@test.dev', 'CONFIRMED', NOW()) ON CONFLICT DO NOTHING");
        // reset so the assertion is deterministic when the test reuses an external database
        execute("UPDATE subscribers SET user_id = NULL, linked_at = NULL, link_origin = NULL, "
              + "unlinked_at = NULL, unlinked_by = NULL WHERE id = 300");

        String backfill = "UPDATE subscribers s "
                + "SET user_id = u.id, linked_at = NOW(), link_origin = 'AUTO_BACKFILL', "
                + "    unlinked_at = NULL, unlinked_by = NULL "
                + "FROM users u "
                + "WHERE s.user_id IS NULL AND s.status = 'CONFIRMED' "
                + "  AND u.email_verified = true AND LOWER(u.email) = s.email "
                + "  AND s.unlinked_by IS DISTINCT FROM 'USER'";

        execute(backfill);
        java.time.LocalDateTime linkedAtAfterFirstRun = template.getDatabaseClient()
                .sql("SELECT linked_at FROM subscribers WHERE id = 300")
                .map(row -> row.get("linked_at", java.time.LocalDateTime.class))
                .one()
                .block(Duration.ofSeconds(10));
        assertThat(linkedAtAfterFirstRun).isNotNull();

        execute(backfill);

        Long linked = template.getDatabaseClient()
                .sql("SELECT COUNT(*) AS c FROM subscribers WHERE user_id = 300 AND link_origin = 'AUTO_BACKFILL'")
                .map(row -> row.get("c", Long.class))
                .one()
                .block(Duration.ofSeconds(10));
        assertThat(linked).isEqualTo(1L);

        java.time.LocalDateTime linkedAtAfterSecondRun = template.getDatabaseClient()
                .sql("SELECT linked_at FROM subscribers WHERE id = 300")
                .map(row -> row.get("linked_at", java.time.LocalDateTime.class))
                .one()
                .block(Duration.ofSeconds(10));
        assertThat(linkedAtAfterSecondRun)
                .as("second pass must not rewrite an already-linked row")
                .isEqualTo(linkedAtAfterFirstRun);
    }

    @Test
    void v21AddsAccountDeletionColumns() {
        Long commentCols = template.getDatabaseClient()
                .sql("SELECT COUNT(*) AS c FROM information_schema.columns "
                   + "WHERE table_name = 'comments' AND column_name = 'user_id'")
                .map(row -> row.get("c", Long.class))
                .one()
                .block(Duration.ofSeconds(10));
        assertThat(commentCols).isEqualTo(1L);

        Long userCols = template.getDatabaseClient()
                .sql("SELECT COUNT(*) AS c FROM information_schema.columns "
                   + "WHERE table_name = 'users' AND column_name IN ('status','deleted_at')")
                .map(row -> row.get("c", Long.class))
                .one()
                .block(Duration.ofSeconds(10));
        assertThat(userCols).isEqualTo(2L);
    }

    /**
     * Erasure nulls the author's email on public comments; author_email was
     * NOT NULL since V1, so V21 must have dropped the constraint.
     */
    @Test
    void v21AuthorEmailAcceptsNull() {
        String nullable = template.getDatabaseClient()
                .sql("SELECT is_nullable FROM information_schema.columns "
                   + "WHERE table_name = 'comments' AND column_name = 'author_email'")
                .map(row -> row.get("is_nullable", String.class))
                .one()
                .block(Duration.ofSeconds(10));
        assertThat(nullable).isEqualTo("YES");

        execute("INSERT INTO users (id, email, name, password_hash, role) "
              + "VALUES (410, 'nullmail@test.dev', 'NullMail', 'x', 'ADMIN') ON CONFLICT DO NOTHING");
        execute("INSERT INTO articles (id, title, slug, content, status, author_id) "
              + "VALUES (410, 'a', 'nullmail-a', 'c', 'PUBLISHED', 410) ON CONFLICT DO NOTHING");
        execute("INSERT INTO comments (id, article_id, author_name, author_email, content, status) "
              + "VALUES (410, 410, 'anon', NULL, 'no email', 'APPROVED') ON CONFLICT DO NOTHING");

        Long saved = template.getDatabaseClient()
                .sql("SELECT COUNT(*) AS c FROM comments WHERE id = 410 AND author_email IS NULL")
                .map(row -> row.get("c", Long.class))
                .one()
                .block(Duration.ofSeconds(10));
        assertThat(saved).isEqualTo(1L);
    }

    /**
     * The V21 comment backfill must be idempotent (a restore may replay it):
     * matching is case-insensitive on the email, guests without an account stay
     * NULL, and a second pass changes nothing.
     */
    @Test
    void v21CommentBackfillMatchesByEmailAndIsIdempotent() {
        execute("INSERT INTO users (id, email, name, password_hash, role) "
              + "VALUES (400, 'commenter@test.dev', 'Commenter', 'x', 'VIEWER') ON CONFLICT DO NOTHING");
        execute("INSERT INTO articles (id, title, slug, content, status, author_id) "
              + "VALUES (400, 'a', 'backfill-c', 'c', 'PUBLISHED', 400) ON CONFLICT DO NOTHING");
        // author_email deliberately upper-cased: the backfill matches on LOWER()
        execute("INSERT INTO comments (id, article_id, author_name, author_email, content, status) "
              + "VALUES (400, 400, 'n', 'COMMENTER@test.dev', 'mine', 'APPROVED') ON CONFLICT DO NOTHING");
        execute("INSERT INTO comments (id, article_id, author_name, author_email, content, status) "
              + "VALUES (401, 400, 'n', 'guest@test.dev', 'guest', 'APPROVED') ON CONFLICT DO NOTHING");
        // reset so the assertion is deterministic when the test reuses an external database
        execute("UPDATE comments SET user_id = NULL WHERE id IN (400, 401)");

        String backfill = "UPDATE comments c SET user_id = u.id FROM users u "
                + "WHERE c.user_id IS NULL AND LOWER(c.author_email) = LOWER(u.email)";

        execute(backfill);
        execute(backfill);

        Long linked = template.getDatabaseClient()
                .sql("SELECT user_id AS c FROM comments WHERE id = 400")
                .map(row -> row.get("c", Long.class))
                .one()
                .block(Duration.ofSeconds(10));
        assertThat(linked).isEqualTo(400L);

        Long guests = template.getDatabaseClient()
                .sql("SELECT COUNT(*) AS c FROM comments WHERE id = 401 AND user_id IS NULL")
                .map(row -> row.get("c", Long.class))
                .one()
                .block(Duration.ofSeconds(10));
        assertThat(guests)
                .as("visitor comments without an account must stay NULL")
                .isEqualTo(1L);
    }

    /**
     * AUD19-F140: the two lastLogin sources for the admin activity endpoint must
     * return the newest timestamp for the user (and only for the requested
     * action, on the audit side) and complete empty — not NULL — when no rows exist.
     */
    @Test
    void lastLoginQueriesReturnNewestTimestampAndEmptyWhenAbsent() {
        execute("INSERT INTO users (id, email, name, password_hash, role) VALUES (500, 'activity@test.dev', 'Act', 'x', 'ADMIN') ON CONFLICT DO NOTHING");
        // reset so the assertion is deterministic when the test reuses an external database
        execute("DELETE FROM audit_logs WHERE performed_by = 500");
        execute("DELETE FROM refresh_tokens WHERE user_id = 500");

        var auditRepo = new R2dbcRepositoryFactory(template)
                .getRepository(dev.catananti.repository.AuditLogRepository.class);
        var tokenRepo = new R2dbcRepositoryFactory(template)
                .getRepository(dev.catananti.repository.RefreshTokenRepository.class);

        StepVerifier.create(auditRepo.findLastActionAt(500L, "LOGIN")).verifyComplete();
        StepVerifier.create(tokenRepo.findLatestCreatedAtByUserId(500L)).verifyComplete();

        execute("INSERT INTO audit_logs (id, action, entity_type, entity_id, performed_by, created_at) "
              + "VALUES (500, 'LOGIN', 'USER', '500', 500, TIMESTAMP '2026-01-01 10:00:00')");
        execute("INSERT INTO audit_logs (id, action, entity_type, entity_id, performed_by, created_at) "
              + "VALUES (501, 'LOGIN', 'USER', '500', 500, TIMESTAMP '2026-02-01 10:00:00')");
        // newer but a different action — must NOT be picked up as a login
        execute("INSERT INTO audit_logs (id, action, entity_type, entity_id, performed_by, created_at) "
              + "VALUES (502, 'LOGIN_FAILED', 'USER', '500', 500, TIMESTAMP '2026-03-01 10:00:00')");
        execute("INSERT INTO refresh_tokens (id, user_id, token, expires_at, created_at) "
              + "VALUES (500, 500, 'tok-activity-old', TIMESTAMP '2099-01-01 00:00:00', TIMESTAMP '2026-01-15 10:00:00')");
        execute("INSERT INTO refresh_tokens (id, user_id, token, expires_at, created_at) "
              + "VALUES (501, 500, 'tok-activity-new', TIMESTAMP '2099-01-01 00:00:00', TIMESTAMP '2026-02-15 10:00:00')");

        StepVerifier.create(auditRepo.findLastActionAt(500L, "LOGIN"))
                .expectNext(java.time.LocalDateTime.of(2026, 2, 1, 10, 0))
                .verifyComplete();
        StepVerifier.create(tokenRepo.findLatestCreatedAtByUserId(500L))
                .expectNext(java.time.LocalDateTime.of(2026, 2, 15, 10, 0))
                .verifyComplete();
    }

    @Test
    void batchRepliesQueryExpandsInClauseOnPostgres() {
        execute("INSERT INTO users (id, email, name, password_hash, role) VALUES (200, 'in@test.dev', 'In', 'x', 'ADMIN') ON CONFLICT DO NOTHING");
        execute("INSERT INTO articles (id, title, slug, content, status, author_id) VALUES (200, 'a', 'in-a', 'c', 'PUBLISHED', 200) ON CONFLICT DO NOTHING");
        execute("INSERT INTO comments (id, article_id, author_name, author_email, content, status) VALUES (201, 200, 'n', 'e@test.dev', 'root-1', 'APPROVED') ON CONFLICT DO NOTHING");
        execute("INSERT INTO comments (id, article_id, author_name, author_email, content, status) VALUES (202, 200, 'n', 'e@test.dev', 'root-2', 'APPROVED') ON CONFLICT DO NOTHING");
        execute("INSERT INTO comments (id, article_id, author_name, author_email, content, status, parent_id) VALUES (203, 200, 'n', 'e@test.dev', 'reply-1', 'APPROVED', 201) ON CONFLICT DO NOTHING");
        execute("INSERT INTO comments (id, article_id, author_name, author_email, content, status, parent_id) VALUES (204, 200, 'n', 'e@test.dev', 'reply-2', 'APPROVED', 202) ON CONFLICT DO NOTHING");

        StepVerifier.create(commentRepository.findApprovedRepliesByParentIds(List.of(201L, 202L))
                        .map(Comment::getContent)
                        .collectList())
                .assertNext(contents -> assertThat(contents).containsExactlyInAnyOrder("reply-1", "reply-2"))
                .verifyComplete();
    }
}
