package dev.catananti.dto;

import dev.catananti.entity.Subscriber;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO for Subscriber that excludes sensitive fields like confirmationToken
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriberResponse {
    private String id;
    private String email;
    private String name;
    private String status;
    private LocalDateTime subscribedAt;
    private LocalDateTime confirmedAt;
    private LocalDateTime unsubscribedAt;
    // Account link visibility for the admin panel (null when not linked).
    // String like id: Snowflake longs overflow the JS number range.
    private String userId;
    private LocalDateTime linkedAt;
    private String linkOrigin;

    public static SubscriberResponse fromEntity(Subscriber subscriber) {
        return SubscriberResponse.builder()
                .id(String.valueOf(subscriber.getId()))
                .email(subscriber.getEmail())
                .name(subscriber.getName())
                .status(subscriber.getStatus().name())
                .subscribedAt(subscriber.getCreatedAt())
                .confirmedAt(subscriber.getConfirmedAt())
                .unsubscribedAt(subscriber.getUnsubscribedAt())
                .userId(subscriber.getUserId() != null ? String.valueOf(subscriber.getUserId()) : null)
                .linkedAt(subscriber.getLinkedAt())
                .linkOrigin(subscriber.getLinkOrigin())
                .build();
    }
}
