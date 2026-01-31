package dev.catananti.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ArticleTest {

    @Test
    @DisplayName("Should increment views count")
    void incrementViews_ShouldIncrementViewsCount() {
        // Given
        Article article = Article.builder()
                .id(1234567890123456789L)
                .slug("test")
                .title("Test")
                .content("Content")
                .viewsCount(10)
                .build();

        // When
        article.incrementViews();

        // Then
        assertThat(article.getViewsCount()).isEqualTo(11);
    }

    @Test
    @DisplayName("Should increment views from null")
    void incrementViews_ShouldHandleNullViewsCount() {
        // Given
        Article article = Article.builder()
                .id(222222222222222L)
                .slug("test")
                .title("Test")
                .content("Content")
                .viewsCount(null)
                .build();

        // When
        article.incrementViews();

        // Then
        assertThat(article.getViewsCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should increment likes count")
    void incrementLikes_ShouldIncrementLikesCount() {
        // Given
        Article article = Article.builder()
                .id(333333333333333L)
                .slug("test")
                .title("Test")
                .content("Content")
                .likesCount(5)
                .build();

        // When
        article.incrementLikes();

        // Then
        assertThat(article.getLikesCount()).isEqualTo(6);
    }

    @Test
    @DisplayName("Should increment likes from null")
    void incrementLikes_ShouldHandleNullLikesCount() {
        // Given
        Article article = Article.builder()
                .id(444444444444444L)
                .slug("test")
                .title("Test")
                .content("Content")
                .likesCount(null)
                .build();

        // When
        article.incrementLikes();

        // Then
        assertThat(article.getLikesCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should use builder defaults")
    void builder_ShouldUseDefaults() {
        // When
        Article article = Article.builder()
                .id(555555555555555L)
                .slug("test")
                .title("Test")
                .content("Content")
                .build();

        // Then
        assertThat(article.getStatus()).isEqualTo("DRAFT");
        assertThat(article.getViewsCount()).isEqualTo(0);
        assertThat(article.getLikesCount()).isEqualTo(0);
        assertThat(article.getTags()).isEmpty();
    }
}
