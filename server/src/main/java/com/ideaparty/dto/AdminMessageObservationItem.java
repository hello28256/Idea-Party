package com.ideaparty.dto;

import com.ideaparty.entity.FeedbackCategory;
import com.ideaparty.entity.FeedbackType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Single row in the admin message overview. Represents one AI message
 * regardless of whether anyone has rated it. feedbackStatus is null when
 * the user viewing the admin panel has not rated this message.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminMessageObservationItem {

    private String messageId;
    private String roomId;
    private String roomName;
    private String characterId;
    private String characterName;
    private String userId;          // author of feedback (null for unrated)
    private String username;
    private String displayName;

    private String messagePreview;
    private LocalDateTime messageCreatedAt;
    /** COMPLETE / EMPTY / FAILED — null for legacy rows. */
    private String streamStatus;

    /** Most recent USER message that prompted the AI reply. May be null. */
    private String userPrompt;
    private LocalDateTime userPromptAt;

    // Rollup across ALL users
    private int feedbackCount;
    private int likeCount;
    private int dislikeCount;
    private Instant lastFeedbackAt;

    /** Feedback status: "RATED" (this user rated), "UNRATED" (nobody), "AGGREGATED" (others rated but not this user). */
    private String status;

    /** If this user rated, details; otherwise null. */
    private FeedbackType feedbackType;
    private FeedbackCategory feedbackCategory;
    private String feedbackComment;
    private Instant userFeedbackAt;
}
