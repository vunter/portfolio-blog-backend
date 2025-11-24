package dev.catananti.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CommentResponse {
    private String id;
    private String articleId;
    private String articleSlug;
    private String articleTitle;
    private String authorName;
    @JsonIgnore
    private String authorEmail;
    private String content;
    private String status;
    private String parentId;
    private List<CommentResponse> replies;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
