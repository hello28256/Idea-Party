package com.ideaparty.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Implicit user event tied to a specific AI message.
 * Examples: REWRITE (regenerate), COPY, READ_COMPLETE, EDIT, FOCUS.
 * Used to derive implicit feedback signals that complement explicit thumbs.
 */
@Entity
@Table(
    name = "message_events",
    indexes = {
        @Index(name = "idx_msg_event", columnList = "message_id, event_type"),
        @Index(name = "idx_user_event", columnList = "user_id, event_type, created_at")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageEvent {

    public enum EventType {
        /** User clicked "regenerate" or otherwise requested a new response for the same slot. */
        REWRITE,
        /** User selected/copied part of the message text. */
        COPY,
        /** Message was scrolled into view and stayed for at least the dwell threshold. */
        READ_COMPLETE,
        /** User edited the AI output (only relevant in editable message surfaces). */
        EDIT,
        /** Message bubble received focus / hover for the dwell window. */
        FOCUS
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 32)
    private EventType eventType;

    /** Time spent on this message, ms. For READ_COMPLETE / FOCUS. */
    @Column(name = "dwell_ms")
    private Integer dwellMs;

    /** Optional JSON blob for event-specific extras. */
    @Column(columnDefinition = "TEXT")
    private String metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}
