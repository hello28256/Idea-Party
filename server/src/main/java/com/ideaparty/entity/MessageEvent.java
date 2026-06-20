package com.ideaparty.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * 与某条 AI 消息关联的隐式用户行为事件。
 * 示例：REWRITE（重新生成）、COPY、READ_COMPLETE、EDIT、FOCUS。
 * 用于派生隐式反馈信号，与显式的点赞/点踩互为补充。
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
     * 针对单条 AI 消息所捕获的隐式用户行为的封闭式枚举。
     * 以 STRING 形式存储，以便数据库保持可读性，并在新增事件种类时无需强制执行 schema migration 即可向前兼容。
     */
    public enum EventType {
        /** 用户点击了"重新生成"，或以其他方式针对同一槽位请求新的回复。 */
        REWRITE,
        /** 用户选中/复制了消息文本的部分内容。 */
        COPY,
        /** 消息被滚动进入视图，并停留了至少一个 dwell 阈值时长。 */
        READ_COMPLETE,
        /** 用户编辑了 AI 输出（仅在可编辑的消息界面中有意义）。 */
        EDIT,
        /** 消息气泡在 dwell 时间窗口内获得了焦点 / hover。 */
        FOCUS
    }

    /**
     * 主键，使用 UUID 生成，以便事件可以在客户端或分布式 worker 中产生，
     * 而无需与 MySQL AUTO_INCREMENT 协调 ID 分配。
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * 本事件所对应的 AI 消息。使用 LAZY 加载是因为多数分析查询并不需要完整的 Message 实体；
     * optional=false 在写入时强制引用完整性，避免产生孤儿事件累积。
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    /**
     * 触发该事件的用户——此处不支持匿名/系统事件；
     * 每一行都必须归属到真实账号，以便反馈信号可以按用户维度聚合。
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * 判别列。以 STRING 形式存储（而非序号），这样即便重排或新增枚举常量也不会静默损坏现有数据；
     * length=32 为将来可能出现的更长标识符预留了空间，无需 ALTER TABLE。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 32)
    private EventType eventType;

    /**
     * 在该消息上停留的时间，单位毫秒。
     * 仅在 dwell 追踪事件（READ_COMPLETE / FOCUS）中填充；
     * 可为空，因为 COPY / REWRITE / EDIT 并不具有有意义的持续时长。
     */
    @Column(name = "dwell_ms")
    private Integer dwellMs;

    /**
     * 可选的 JSON 字段，用于存放事件特有的扩展信息（例如 COPY 的字符区间、EDIT 的前后 diff）。
     * 使用 TEXT 存储，避免对持续演进的客户端埋点强加固定 schema。
     */
    @Column(columnDefinition = "TEXT")
    private String metadata;

    /**
     * 实际入库时间。由 {@link #onCreate()} 写入一次后不再更新，
     * 即使实体之后被回填，分析侧也能获得稳定的时间线。
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * JPA 生命周期回调：在 INSERT 执行前写入 {@link #createdAt}。
     * 保持为包内/protected 可见性，以便 JPA 可调用，
     * 同时让业务代码无法绕过服务器端的时间戳。
     */
    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}
