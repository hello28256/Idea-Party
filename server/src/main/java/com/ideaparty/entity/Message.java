package com.ideaparty.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 聊天室中的一条发言（用户或角色均可）。
 * 作为聚合根同时挂载流式生成过程事件（events）与用户反馈（feedbacks），
 * 因此删除 Room 时必须通过反向级联先清理这些子表，否则会撞外键约束。
 */
@Entity
@Table(name = "messages")
public class Message {

    /**
     * 发言主体区分：USER 走 user_id 关联，CHARACTER 走 character_id 关联，
     * 两条 FK 在表中都是可空的，但每条消息实际只会填充其中一个。
     */
    public enum SenderType {
        USER,
        CHARACTER
    }

    public enum StreamStatus {
        /** Default — message was fully generated and saved via the normal onResponse path. */
        COMPLETE,
        /** LLM stream finished but produced empty / placeholder content. */
        EMPTY,
        /** Generation failed mid-stream (LLM error, timeout, etc.) — message kept for visibility. */
        FAILED
    }

    // 主键用 UUID：分布式生成、无自增序列依赖，方便后续按 id 直接对外暴露而不泄露递增信息
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    // TEXT 长度：角色回复（含上下文拼接）经常超过 255 / VARCHAR 上限
    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_type", nullable = false)
    private SenderType senderType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "character_id")
    private Character character;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    // 创建时间由 @PrePersist 自动填充，调用方无需 set；列表查询按它降序排得到"最新消息在顶"的聊天室体验
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    // length=16 够放 "COMPLETE" / "EMPTY" / "FAILED"；前向兼容未来加更长的状态枚举值
    @Enumerated(EnumType.STRING)
    @Column(name = "stream_status", length = 16)
    private StreamStatus streamStatus;

    /**
     * 反向级联：message_events.message_id 是外键指向 messages.id。
     * Room 删除时级联到 messages，再级联到 events，否则会撞外键约束。
     * MessageEvent 实体本身没有声明反向，本字段让 Hibernate 知道要先删 events。
     */
    @OneToMany(mappedBy = "message", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<MessageEvent> events = new ArrayList<>();

    /**
     * 反向级联：message_feedbacks.message_id 也是外键指向 messages.id。
     * 同 message_events 一样需要反向声明，否则 Room 删除时会撞外键约束。
     */
    @OneToMany(mappedBy = "message", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<MessageFeedback> feedbacks = new ArrayList<>();

    // 默认 COMPLETE：用户消息走正常保存路径，没有「流式生成」概念；
    // AI 消息在 onResponse 成功回调时也会显式设为 COMPLETE，FAILED/EMPTY 由异常分支写入。
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (streamStatus == null) streamStatus = StreamStatus.COMPLETE;
    }

    // JPA 要求 entity 必须有无参构造；实例化完全由 Hibernate 负责，业务代码统一走 builder/of 静态工厂
    public Message() {}

    // 读：DTO 序列化、消息跳转链接；写：仅测试/反射构造，业务路径不直接 setId
    public String getId() { return id; }
    // 写：测试/反射构造；业务路径通常由 JPA 在 persist 时自动生成
    public void setId(String id) { this.id = id; }

    // 读：聊天列表渲染、上下文回灌 LLM；写：onResponse 回调时一次性写完整段
    public String getContent() { return content; }
    // 写：流式生成期间不要走 setter，每次 delta 直接走 Repository update，避免覆盖丢失 token
    public void setContent(String content) { this.content = content; }

    // 读：前端根据 senderType 决定头像/气泡样式（USER vs CHARACTER）
    public SenderType getSenderType() { return senderType; }
    // 写：构建消息时根据发送方选定，业务路径必填
    public void setSenderType(SenderType senderType) { this.senderType = senderType; }

    // 读：前端要拿 character.name / avatar 用于消息气泡头；LAZY 需在事务内访问
    public Character getCharacter() { return character; }
    // 写：CHARACTER 类型消息必填；USER 类型消息保持 null
    public void setCharacter(Character character) { this.character = character; }

    // 读：聚合查询或消息列表分页时拿 roomId；LAZY 触发 SQL
    public Room getRoom() { return room; }
    // 写：每条消息必填，反向级联删除路径的根节点
    public void setRoom(Room room) { this.room = room; }

    // 读：USER 类型消息需要拿 user.username / avatar；LAZY 触发 SQL
    public User getUser() { return user; }
    // 写：USER 类型消息必填；CHARACTER 类型消息保持 null
    public void setUser(User user) { this.user = user; }

    // 读：消息列表排序、UI 时间戳展示；写入统一交给 @PrePersist，业务不应手 set
    public LocalDateTime getCreatedAt() { return createdAt; }
    // 写：仅测试/数据迁移使用；正常路径由 JPA 自动填充
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    // 读：前端/消息列表可据此决定是否显示"生成失败"角标；nullable（user 消息时为 null）
    public StreamStatus getStreamStatus() { return streamStatus; }
    // 写：onResponse/异常分支写入；默认 COMPLETE 由 @PrePersist 兜底
    public void setStreamStatus(StreamStatus streamStatus) { this.streamStatus = streamStatus; }
}
