package com.ideaparty.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Aggregated implicit signals for a single AI message.
 * Returned by the admin endpoint to power the "signals" panel
 * in the feedback detail view.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageSignalsResponse {

    private String messageId;
    private long rewriteCount;
    private long copyCount;
    private long readCompleteCount;
    private long editCount;
    /** Average dwell (ms) across READ_COMPLETE and FOCUS events. Null if no events. */
    private Double averageDwellMs;
    /** Distinct users who triggered any event. */
    private long uniqueUsers;
}
