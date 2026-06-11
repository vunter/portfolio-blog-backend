package dev.catananti.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Subset of a GitHub repository, exposed by the public
 * {@code GET /api/v1/github/repos} proxy.
 *
 * <p>Field JSON names are kept in snake_case (via {@link JsonProperty}) so the
 * payload sent to the frontend is byte-for-byte the shape it previously consumed
 * directly from api.github.com — no frontend mapping change is needed. The same
 * annotations drive deserialization of GitHub's response; {@code ignoreUnknown}
 * drops the dozens of fields the widget does not use.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubRepoResponse(
        long id,
        String name,
        @JsonProperty("full_name") String fullName,
        String description,
        @JsonProperty("html_url") String htmlUrl,
        String homepage,
        @JsonProperty("stargazers_count") int stargazersCount,
        @JsonProperty("forks_count") int forksCount,
        String language,
        List<String> topics,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt,
        @JsonProperty("pushed_at") String pushedAt
) {
}
