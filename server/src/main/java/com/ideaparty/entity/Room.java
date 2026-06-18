package com.ideaparty.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 聊天室聚合根:作为多角色 AI 对话场景的载体,由 User 创建并聚合一组 Character 参与对话。
 * 与 RoomMember(成员邀请)、Message(消息流)、Character(参与角色)协作,
 * 维护群聊/单聊模式、Moderator 编排所需的轮次上限等运行期配置。
 */
@Entity
@Table(name = "rooms")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Room {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(length = 500)
    private String topic;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @ManyToMany(cascade = { CascadeType.PERSIST, CascadeType.MERGE })
    @JoinTable(
        name = "room_characters",
        joinColumns = @JoinColumn(name = "room_id"),
        inverseJoinColumns = @JoinColumn(name = "character_id")
    )
    @Builder.Default
    private Set<Character> characters = new HashSet<>();

    @OneToMany(mappedBy = "room", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<RoomMember> members = new ArrayList<>();

    @OneToMany(mappedBy = "room", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Message> messages = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "chat_mode", length = 20)
    @Builder.Default
    // 对话风格:dialogue 自由对白 / discussion 主题讨论(Moderator 介入主持)
    private String chatMode = "dialogue"; // "dialogue" or "discussion"

    @Column(name = "mode", length = 20, nullable = false)
    @Builder.Default
    // 房间拓扑:single 1v1 简化路径 / group 多人多角色编排路径
    private String mode = "group"; // "single" (1-on-1) or "group" (multi-character)

    @Column(name = "max_discussion_rounds")
    @Builder.Default
    // 讨论模式下 Moderator 单次最多推进的发言轮数,防止无终止循环
    private Integer maxDiscussionRounds = 5;

    // 房主最近一次进入房间的时间,用于"最近活跃"排序与未活跃提醒
    @Column(name = "last_enter_time")
    private Instant lastEnterTime;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public int getCharacterCount() {
        return characters != null ? characters.size() : 0;
    }

    public int getMemberCount() {
        return members != null ? members.size() : 0;
    }
}
