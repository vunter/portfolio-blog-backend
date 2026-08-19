package dev.catananti.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Method;
import java.util.stream.Stream;

import org.springframework.data.r2dbc.repository.Query;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AUD19C-2: admin comment search queries. Asserted reflectively (like
 * {@link CommentRepositoryQueryTest}) so the SQL contract is pinned without a DB:
 * every variant must match content OR author_name case-insensitively, bind the
 * term as a parameter (no string concatenation of user input) and declare
 * ESCAPE '\' so the caller-side wildcard escaping (F-291) is honored on both
 * PostgreSQL and H2.
 */
class CommentRepositorySearchQueryTest {

    private static final String CONTENT_MATCH = "LOWER(content) LIKE LOWER('%' || :search || '%') ESCAPE '\\'";
    private static final String AUTHOR_MATCH = "LOWER(author_name) LIKE LOWER('%' || :search || '%') ESCAPE '\\'";
    private static final String ALIASED_CONTENT_MATCH = "LOWER(c.content) LIKE LOWER('%' || :search || '%') ESCAPE '\\'";
    private static final String ALIASED_AUTHOR_MATCH = "LOWER(c.author_name) LIKE LOWER('%' || :search || '%') ESCAPE '\\'";

    static Stream<Arguments> searchQueries() {
        return Stream.of(
                // methodName, paramTypes, aliased?, expects status filter?, expects author join?, count?
                Arguments.of("findAllBySearch", new Class<?>[]{String.class, int.class, int.class}, false, false, false, false),
                Arguments.of("countAllBySearch", new Class<?>[]{String.class}, false, false, false, true),
                Arguments.of("findByStatusAndSearch", new Class<?>[]{String.class, String.class, int.class, int.class}, false, true, false, false),
                Arguments.of("countByStatusAndSearch", new Class<?>[]{String.class, String.class}, false, true, false, true),
                Arguments.of("findByArticleAuthorIdAndSearch", new Class<?>[]{Long.class, String.class, int.class, int.class}, true, false, true, false),
                Arguments.of("countByArticleAuthorIdAndSearch", new Class<?>[]{Long.class, String.class}, true, false, true, true),
                Arguments.of("findByArticleAuthorIdAndStatusAndSearch", new Class<?>[]{Long.class, String.class, String.class, int.class, int.class}, true, true, true, false),
                Arguments.of("countByArticleAuthorIdAndStatusAndSearch", new Class<?>[]{Long.class, String.class, String.class}, true, true, true, true)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("searchQueries")
    @DisplayName("search variants match content OR author_name, parameterized, with explicit ESCAPE")
    void searchQueryContract(String name, Class<?>[] params, boolean aliased,
                             boolean statusFiltered, boolean authorJoined, boolean count)
            throws NoSuchMethodException {
        Method m = CommentRepository.class.getMethod(name, params);
        String sql = m.getAnnotation(Query.class).value();

        String contentMatch = aliased ? ALIASED_CONTENT_MATCH : CONTENT_MATCH;
        String authorMatch = aliased ? ALIASED_AUTHOR_MATCH : AUTHOR_MATCH;

        assertThat(sql)
                .as("term is bound as :search on content and author_name with ESCAPE — never concatenated")
                .contains(contentMatch)
                .contains(authorMatch)
                .contains(contentMatch + " OR " + authorMatch);

        if (statusFiltered) {
            assertThat(sql).contains(aliased ? "c.status = :status" : "status = :status");
        } else {
            assertThat(sql).doesNotContain(":status");
        }

        if (authorJoined) {
            assertThat(sql)
                    .as("DEV variants stay ownership-scoped via the articles join")
                    .contains("JOIN articles a ON c.article_id = a.id")
                    .contains("a.author_id = :authorId");
        } else {
            assertThat(sql).doesNotContain(":authorId");
        }

        if (count) {
            assertThat(sql).contains("COUNT(");
            assertThat(m.getGenericReturnType().getTypeName())
                    .isEqualTo("reactor.core.publisher.Mono<java.lang.Long>");
        } else {
            assertThat(sql)
                    .contains("ORDER BY")
                    .contains("LIMIT :limit OFFSET :offset");
            assertThat(m.getGenericReturnType().getTypeName())
                    .isEqualTo("reactor.core.publisher.Flux<dev.catananti.entity.Comment>");
        }
    }
}
