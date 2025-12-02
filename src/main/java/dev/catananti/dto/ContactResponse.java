package dev.catananti.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContactResponse {

    private String id;
    private String name;
    private String email;
    private String subject;
    private String message;
    private boolean read;
    private LocalDateTime createdAt;
}
