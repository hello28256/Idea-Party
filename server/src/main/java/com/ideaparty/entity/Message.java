package com.ideaparty.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "messages")
public class Message {

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
