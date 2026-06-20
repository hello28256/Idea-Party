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

    /** Stable identifier of the AI message row being observed in the admin list. */
    private String messageId;
    /** Id of the room this AI message belongs to; used by admin UI to deep-link back to the room. */
    private String roomId;
    /** Human-readable room name shown alongside the message so admins don't have to resolve id by hand. */
    private String roomName;
    /** Id of the AI character/role that produced this message; needed for drill-down filters. */
    private String characterId;
    /** Display name of the AI character; shown directly in the admin table for fast scanning. */
    private String characterName;
    /** Id of the user who authored the feedback; null when nobody has rated this message yet. */
    private String userId;          // author of feedback (null for unrated)
    /** Login name of the feedback author; mirrors userId for display, null when unrated. */
    private String username;
    /** Preferred display name of the feedback author; null when unrated or not set. */
    private String displayName;

    /** Truncated body of the AI message so admins can preview content without opening detail view. */
    private String messagePreview;
    /** Timestamp the AI message was persisted; used to sort the admin table newest-first. */
    private LocalDateTime messageCreatedAt;
    /** COMPLETE / EMPTY / FAILED — null for legacy rows. */
    private String streamStatus;

    /** Most recent USER message that prompted the AI reply. May be null. */
    private String userPrompt;
    /** Time the prompting user message was created; lets admin correlate latency with request time. */
    private LocalDateTime userPromptAt;
    /** Id of the user whose question triggered this AI reply; null when the prompt context is missing. */
    private String promptUserId;
    /** Login name of the prompting user; shown to admin for context (who asked what). */
    private String promptUsername;
    /** Display name of the prompting user; preferred over username in admin UI when present. */
    private String promptDisplayName;

    // Rollup across ALL users
    /** Total feedback rows across every user for this message; primary denominator for like/dislike ratios. */
    private int feedbackCount;
    /** Number of LIKE ratings aggregated from all users; used for at-a-glance quality signals. */
    private int likeCount;
    /** Number of DISLIKE ratings aggregated from all users; high values flag problem messages for triage. */
    private int dislikeCount;
    /** Time of the most recent feedback on this message; helps prioritize freshly-flagged content. */
    private Instant lastFeedbackAt;

    /** Feedback status: "RATED" (this user rated), "UNRATED" (nobody), "AGGREGATED" (others rated but not this user). */
    private String status;

    /** If this user rated, details; otherwise null. */
    /** LIKE/DISLIKE choice of the viewing admin user; null when status is UNRATED or AGGREGATED. */
    private FeedbackType feedbackType;
    /** Optional category bucket the viewing user picked (e.g. tone, accuracy); null when not categorized. */
    private FeedbackCategory feedbackCategory;
    /** Free-text comment left by the viewing user; null when no comment was provided. */
    private String feedbackComment;
    /** When the viewing user submitted their own feedback; null when this user has not rated yet. */
    private Instant userFeedbackAt;
}
