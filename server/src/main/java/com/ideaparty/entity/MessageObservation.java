package com.ideaparty.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Per-AI-message feedback observation. One row per AI message, regardless of
 * whether the user has rated it. Lets the admin overview list ALL AI messages
 * (rated + unrated) and drill into the ones that matter.
 */
@Entity
@Table(
    name = "message_observations",
    indexes = {
        @Index(name = "idx_room_created", columnList = "room_id, created_at"),
        @Index(name = "idx_char_created", columnList = "character_id, created_at"),
        @Index(name = "idx_feedback_count", columnList = "feedback_count, created_at")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageObservation {

    /** Maps to messages.id (String). One observation per AI message. */
    @Id
    @Column(name = "message_id", length = 36)
    private String messageId;

    @Column(name = "room_id", nullable = false, length = 36)
    private String roomId;

    @Column(name = "character_id", length = 36)
    private String characterId;

    @Column(name = "feedback_count", nullable = false)
    @Builder.Default
    private Integer feedbackCount = 0;

    @Column(name = "like_count", nullable = false)
    @Builder.Default
    private Integer likeCount = 0;

    @Column(name = "dislike_count", nullable = false)
    @Builder.Default
    private Integer dislikeCount = 0;

    @Column(name = "last_feedback_at")
    private Instant lastFeedbackAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
