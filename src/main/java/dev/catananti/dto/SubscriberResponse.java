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
    
    public static SubscriberResponse fromEntity(Subscriber subscriber) {
        return SubscriberResponse.builder()
                .id(String.valueOf(subscriber.getId()))
                .email(subscriber.getEmail())
                .name(subscriber.getName())
                .status(subscriber.getStatus())
                .subscribedAt(subscriber.getCreatedAt())
                .confirmedAt(subscriber.getConfirmedAt())
                .unsubscribedAt(subscriber.getUnsubscribedAt())
                .build();
    }
}
