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

    public Message() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public SenderType getSenderType() { return senderType; }
    public void setSenderType(SenderType senderType) { this.senderType = senderType; }

    public Character getCharacter() { return character; }
    public void setCharacter(Character character) { this.character = character; }

    public Room getRoom() { return room; }
    public void setRoom(Room room) { this.room = room; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public StreamStatus getStreamStatus() { return streamStatus; }
    public void setStreamStatus(StreamStatus streamStatus) { this.streamStatus = streamStatus; }
}
