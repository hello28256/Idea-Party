package com.ideaparty.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * 用户对单条 AI 消息的反馈。
 * 唯一约束 (message_id, user_id) 保证同一用户对同一消息只有一条反馈（upsert 语义）。
 * 消息或用户被删除时反馈级联删除（FK ON DELETE CASCADE）。
 */
@Entity
@Table(
    name = "message_feedbacks",
    uniqueConstraints = @UniqueConstraint(name = "uk_msg_user", columnNames = {"message_id", "user_id"}),
    indexes = {
        @Index(name = "idx_user_created", columnList = "user_id, created_at"),
        @Index(name = "idx_category_created", columnList = "category, created_at")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageFeedback {

    /**
     * 主键 ID，由数据库自动生成（UUID v4）。
     * 使用 UUID 而非自增 ID 是为了分布式写入/合并场景下避免主键冲突，也方便在客户端预生成。
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * 被反馈的 AI 消息。
     * 延迟加载（LAZY）避免仅取反馈列表时连带拉出整条消息内容/角色信息；
     * optional=false 在持久化校验时拒绝 message_id 为空的反馈记录；
     * 数据库侧通过 FK ON DELETE CASCADE 在消息被删除时自动清理其反馈。
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    /**
     * 提交反馈的用户。
     * 同样采用 LAZY 加载以避免反馈列表查询时触发 User 的级联加载；
     * 与 message 共同构成 uk_msg_user 唯一约束，保证同一用户对同一消息只有一条反馈（upsert）。
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * 反馈类型（点赞/点踩/举报等），枚举以字符串形式持久化以便数据库可读。
     * 必填：length=16 为当前枚举预留，未来新增枚举值需同步调整。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FeedbackType type;

    /**
     * 反馈分类（事实错误/答非所问/不当言论 等），可空——简单点赞/点踩无需分类。
     * 建立 idx_category_created 索引用于后台按分类聚合统计反馈分布。
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private FeedbackCategory category;

    /**
     * 用户附加说明，可空。
     * 使用 TEXT 而非 VARCHAR 以支持较长的反馈文本，规避长度限制带来的截断问题。
     */
    @Column(columnDefinition = "TEXT")
    private String comment;

    /**
     * 创建时间，由 {@link #onCreate()} 在首次持久化时写入，之后不可更新（updatable=false），
     * 用于审计与按时间窗口分析反馈趋势；索引 idx_user_created 依赖此列做范围查询。
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * 最近更新时间，由 {@link #onCreate()} 初始化并在 {@link #onUpdate()} 中刷新，
     * 用于追踪反馈分类/评论被修改的时间，配合 createdAt 可计算反馈的修订历史。
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * JPA 生命周期回调：实体首次持久化前触发。
     * 同时初始化 createdAt 与 updatedAt 为同一时刻，保证两者在新建时一致，避免出现 updatedAt < createdAt 的脏数据。
     * 由 JPA 反射调用，业务侧不应直接调用。
     */
    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    /**
     * JPA 生命周期回调：实体每次更新前触发。
     * 仅刷新 updatedAt 字段，createdAt 保持不变以保留首次反馈的时间戳。
     * 由 JPA 反射调用，业务侧不应直接调用。
     */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
