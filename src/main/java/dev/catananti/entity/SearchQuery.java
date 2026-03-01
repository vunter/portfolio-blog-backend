package dev.catananti.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("search_queries")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchQuery {
    @Id
    private Long id;

    @Column("query_text")
    private String queryText;

    @Column("results_count")
    private int resultsCount;

    @Column("user_id")
    private Long userId;

    @Column("user_ip")
    private String userIp;

    @Column("created_at")
    private LocalDateTime createdAt;
}
