package com.ideaparty.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Aggregated implicit signals for a single AI message.
 * Returned by the admin endpoint to power the "signals" panel
 * in the feedback detail view.
 *
 * <p>Carries the rolled-up counts and timing metrics that the backend
 * computes from raw message-feedback events, so the admin UI can render
 * engagement health without re-aggregating client-side.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageSignalsResponse {

    /** Identifier of the AI message these signals belong to; links back to the chat history entry. */
    private String messageId;
    /** Number of REWRITE events — how often users regenerated this message; high values suggest low quality. */
    private long rewriteCount;
    /** Number of COPY events — indicates the message produced reusable output the user wanted to keep. */
    private long copyCount;
    /** Number of READ_COMPLETE events — users who scrolled/exposed the message fully, proxy for reach. */
    private long readCompleteCount;
    /** Number of EDIT events — users who manually tweaked the AI output before sending it forward. */
    private long editCount;
    /** Average dwell (ms) across READ_COMPLETE and FOCUS events. Null if no events. */
    private Double averageDwellMs;
    /** Distinct users who triggered any event. */
    private long uniqueUsers;
}
