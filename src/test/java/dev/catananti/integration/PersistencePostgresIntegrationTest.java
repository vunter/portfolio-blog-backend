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
