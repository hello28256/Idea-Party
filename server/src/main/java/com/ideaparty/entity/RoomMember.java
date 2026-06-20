package com.ideaparty.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * 聊天室成员关系实体。
 *
 * 记录某个 User 在某个 Room 中的角色与状态，作为多对多关联的中间表
 * （room_members）落地到 MySQL。配合 {@link Room} 与 {@link User} 一同
 * 维护聊天室的人员组成与权限边界，避免在同一 Room 中重复加入同一 User。
 */
@Entity
@Table(name = "room_members", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"room_id", "user_id"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomMember {

    /**
     * 主键，自增 Long。
     * 由数据库生成，业务层无需赋值；RoomMemberService 写入后回填给实体。
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 所属聊天室。
     * LAZY 加载避免仅查询成员关系时连带拉取 Room 全量数据；
     * 通过 room_id 外键映射，配合唯一约束保证 (room_id, user_id) 不重复。
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    /**
     * 关联用户。
     * LAZY 加载，仅在业务真正需要 User 信息（如 @ManyToOne 触发）时由 JPA 拉取，
     * 减少成员列表查询时的 N+1 与冗余 IO。
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * 成员角色：owner / admin / member。
     * 默认 "member"——绝大多数受邀用户为普通成员，避免 builder 调用方忘填角色
     * 导致权限校验歧义；权限校验逻辑读取此字段判断是否可踢人/转让房间。
     */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String role = "member"; // owner, admin, member

    /**
     * 成员状态：active / removed。
     * 默认 "active"——新建即生效；软删除时改为 "removed" 而非 DELETE 行，
     * 以保留历史消息归属与审计追溯能力。
     */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "active"; // active, removed

    /**
     * 邀请人（可空）。
     * 由 Room 创建者直接加入时为空；通过邀请链接或成员邀请进入时记录
     * 邀请者 User，用于审计与"谁邀请了谁"的来源追溯。
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invited_by")
    private User invitedBy;

    /**
     * 实际加入时间。
     * 可由业务侧显式传入（如批量导入历史数据），未传时由 onCreate() 回填为当前时间，
     * 与 createdAt 区分以便支持"先创建占位记录、后激活"的场景。
     */
    @Column(name = "joined_at")
    private Instant joinedAt;

    /**
     * 记录创建时间，插入时由 onCreate() 写入，updatable = false 防止后续被覆盖，
     * 作为审计基线时间。
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * 记录最后更新时间，每次 INSERT/UPDATE 由 JPA 回调刷新，供前端排序与缓存失效判断使用。
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * JPA 持久化前回调：统一写入 createdAt / updatedAt，并在 joinedAt 未显式
     * 设置时回填，保证三个时间字段在新建场景下非空、语义一致。
     */
    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (joinedAt == null) {
            joinedAt = now;
        }
    }

    /**
     * JPA 更新前回调：仅刷新 updatedAt 为当前时间，避免业务层手动维护时间戳遗漏。
     */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
