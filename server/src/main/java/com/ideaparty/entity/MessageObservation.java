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

    /** 所属聊天室 ID（冗余字段，避免后台聚合时反复 JOIN messages 表）。 */
    @Column(name = "room_id", nullable = false, length = 36)
    private String roomId;

    /** 发言 AI 角色 ID（可为 null，兼容系统/MODERATOR 类非角色消息）；冗余存储便于按角色筛选。 */
    @Column(name = "character_id", length = 36)
    private String characterId;

    /** 总反馈次数（含 like + dislike）；@Builder.Default 保证 Lombok Builder 不会重置初始值 0。 */
    @Column(name = "feedback_count", nullable = false)
    @Builder.Default
    private Integer feedbackCount = 0;

    /** 点赞数；初始为 0，配合 @Builder.Default 让 Builder 调用方省略该字段时仍能得到合法默认值。 */
    @Column(name = "like_count", nullable = false)
    @Builder.Default
    private Integer likeCount = 0;

    /** 点踩数；初始为 0，配合 @Builder.Default 让 Builder 调用方省略该字段时仍能得到合法默认值。 */
    @Column(name = "dislike_count", nullable = false)
    @Builder.Default
    private Integer dislikeCount = 0;

    /** 最近一次反馈的时间戳；用于后台按"最近反馈时间"排序，识别长期未互动的内容。 */
    @Column(name = "last_feedback_at")
    private Instant lastFeedbackAt;

    /** 记录创建时间；由 @PrePersist 在首次插入时写入，updatable=false 防止后续 update 被覆盖。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 最近一次更新时间；每次 save/update 都会通过 @PreUpdate 刷新，供审计与缓存失效判断使用。 */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * JPA 生命周期回调：实体首次持久化前触发，统一设置 createdAt / updatedAt 为当前瞬时时间，
     * 调用方无需在 Service 层手动维护时间字段。
     */
    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    /**
     * JPA 生命周期回调：每次更新前刷新 updatedAt 为当前瞬时时间，保证审计字段始终反映最近写入时间。
     */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
