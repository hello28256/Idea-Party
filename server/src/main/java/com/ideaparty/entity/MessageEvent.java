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

    /**
     * Closed enumeration of implicit user actions captured against a single AI message.
     * Stored as STRING so the DB remains human-readable and forward-compatible when new
     * event kinds are added without forcing a schema migration.
     */
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

    /**
     * Primary key, generated as a UUID so events can be produced client-side or in
     * distributed workers without coordinating ID allocation with MySQL AUTO_INCREMENT.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * The AI message this event is about. LAZY because most analytics queries don't need
     * the full Message body, and optional=false enforces referential integrity at write
     * time so orphan events cannot accumulate.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    /**
     * The acting user — anonymous/system events are not supported here; every row must
     * be attributable to a real account so feedback signals can be per-user aggregated.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * Discriminator column. Stored as STRING (not ordinal) so reordering or inserting new
     * enum constants never silently corrupts existing rows; length=32 leaves headroom for
     * future longer identifiers without an ALTER TABLE.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 32)
    private EventType eventType;

    /**
     * Time spent on this message, in milliseconds. Only populated for dwell-tracking
     * events (READ_COMPLETE / FOCUS); nullable because COPY / REWRITE / EDIT have no
     * meaningful duration.
     */
    @Column(name = "dwell_ms")
    private Integer dwellMs;

    /**
     * Optional JSON blob for event-specific extras (e.g. character range for COPY,
     * before/after diff for EDIT). Stored as TEXT to avoid imposing a hard schema
     * on evolving client telemetry.
     */
    @Column(columnDefinition = "TEXT")
    private String metadata;

    /**
     * Wall-clock insert time. Set once by {@link #onCreate()} and never updated,
     * giving analytics a stable timeline even if the entity is later backfilled.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * JPA lifecycle hook that stamps {@link #createdAt} before INSERT runs.
     * Kept package-private/protected so JPA can invoke it but application code
     * cannot bypass the server-side timestamp.
     */
    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}
