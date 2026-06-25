package com.ideaparty.entity;

// JPA 注解用于实体映射;Lombok 在编译期生成 getter/setter/builder 以避免模板代码膨胀。
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
 *
 * <p>使用 Lombok @Data + @Builder 减少样板代码,@Builder.Default 保证关联集合字段在 Builder
 * 路径下仍能被初始化为非 null 容器,避免后续业务代码 NPE。
 */
@Entity
@Table(name = "rooms")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Room {

    /**
     * 聊天室主键:使用 UUID 而非自增 Long,避免外部猜测房间 ID 并提供分布式友好的唯一标识。
     * 生成策略交给 Hibernate 在持久化时分配,无需业务侧手工赋值。
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * 房间展示名:由房主在创建时填写,用于侧边栏列表与加入提示。
     * 可空:前端在「单角色」场景下允许留空,Service 兜底回退到角色名,DB 不再做强约束。
     * 长度上限 100 与 DTO 校验对齐,避免 UI 列表/详情展示时超长截断。
     */
    @Column(length = 100)
    private String name;

    /**
     * 房间主题/简介:可空,长度上限 500,供 Moderator 在编排发言时参考上下文背景。
     */
    @Column(length = 500)
    private String topic;

    /**
     * 房间创建者:多对一关联 User,采用 LAZY 加载避免查询房间列表时把每个房主 User 全量拉出。
     * 房主在删除账号或转让时需要业务层显式处理关联房间的所有权迁移。
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    /**
     * 参与对话的 AI 角色集合:用 Set 而非 List 防止同一 Character 被重复加入房间。
     * 关联表为 room_characters,采用 PERSIST/MERGE 级联便于在新建房间时一次性带出角色。
     * 初始化为 HashSet 是为了 @Builder 在未显式赋值时仍能拿到非 null 容器,避免 NPE。
     */
    @ManyToMany(cascade = { CascadeType.PERSIST, CascadeType.MERGE })
    @JoinTable(
        name = "room_characters",
        joinColumns = @JoinColumn(name = "room_id"),
        inverseJoinColumns = @JoinColumn(name = "character_id")
    )
    @Builder.Default
    private Set<Character> characters = new HashSet<>();

    /**
     * 房间成员(被邀请的协作用户):一对多反向维护 RoomMember,ALL + orphanRemoval 保证成员关系随房间删除而清理。
     * 默认空列表,@Builder.Default 让 Lombok Builder 在调用方未赋值时不会覆盖成 null。
     */
    @OneToMany(mappedBy = "room", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<RoomMember> members = new ArrayList<>();

    /**
     * 房间产生的消息流:一对多反向维护 Message,ALL + orphanRemoval 用于房间删除时级联清空聊天记录。
     * 仅作为聚合根的导航属性,实际写入走 MessageRepository 而非 room.getMessages().add(...)。
     */
    @OneToMany(mappedBy = "room", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Message> messages = new ArrayList<>();

    /**
     * 房间创建时间:由 @PrePersist 在首次持久化时填入,updatable=false 防止后续误改。
     * 用于审计与按时间排序的房间列表展示。
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * 房间最后更新时间:由 @PrePersist 与 @PreUpdate 维护,业务层无需手工设置。
     * 用于乐观锁以外的最近变更判定与缓存失效触发。
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * 对话风格:取值 "dialogue" 或 "discussion"。
     * dialogue 为自由对白模式,discussion 触发 Moderator 介入主持主题推进。
     * 长度 20 足够覆盖枚举字符串,默认走 dialogue 降低新房间的复杂度。
     */
    @Column(name = "chat_mode", length = 20)
    @Builder.Default
    // 对话风格:dialogue 自由对白 / discussion 主题讨论(Moderator 介入主持)
    private String chatMode = "dialogue"; // "dialogue" or "discussion"

    /**
     * 房间拓扑模式:取值 "single"(1v1 简化路径)或 "group"(多人多角色编排路径)。
     * DB 层 NOT NULL,默认 group 与产品主流程一致;single 用于用户只想和单个 AI 角色聊天的场景。
     */
    @Column(name = "mode", length = 20, nullable = false)
    @Builder.Default
    // 房间拓扑:single 1v1 简化路径 / group 多人多角色编排路径
    private String mode = "group"; // "single" (1-on-1) or "group" (multi-character)

    /**
     * 讨论模式下 Moderator 单次编排最多推进的发言轮数,默认 5 防止无终止循环与 token 浪费。
     * 业务层在每轮 Moderator 调用前需要读取并裁剪发言队列,避免超过上限。
     */
    @Column(name = "max_discussion_rounds")
    @Builder.Default
    // 讨论模式下 Moderator 单次最多推进的发言轮数,防止无终止循环
    private Integer maxDiscussionRounds = 5;

    /**
     * 房主最近一次进入房间的时间:可空,房主首次进入时由 Room 进入入口写入。
     * 用于"最近活跃"房间排序与未活跃提醒(N 天未访问提示归档)。
     */
    // 房主最近一次进入房间的时间,用于"最近活跃"排序与未活跃提醒
    @Column(name = "last_enter_time")
    private Instant lastEnterTime;

    /**
     * JPA 持久化回调:首次 INSERT 前写入创建时间与初始更新时间,保证两条时间戳来自同一时刻便于审计对齐。
     * protected 访问性符合 JPA 规范要求,且 Lombok @Data 不会对外暴露该生命周期方法。
     */
    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    /**
     * JPA 持久化回调:每次 UPDATE 前刷新更新时间,业务侧不应直接修改 updatedAt。
     * 与 @PrePersist 配合保证 createdAt 永远早于 updatedAt。
     */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * 角色数量便捷查询:给前端房间列表与概览页直接展示,避免在视图层再做空值判断。
     * 容忍 characters 为 null 的极端情况(理论上 @Builder.Default 已保证非 null)。
     *
     * @return 当前房间内 AI 角色数量
     */
    public int getCharacterCount() {
        return characters != null ? characters.size() : 0;
    }

    /**
     * 房间成员数量便捷查询:用于列表卡片"X 人协作"展示与权限校验(超过上限时拒绝邀请)。
     * 同样容忍 null,保护 Builder 未走默认值的边缘场景。
     *
     * @return 当前房间内被邀请的协作成员数量
     */
    public int getMemberCount() {
        return members != null ? members.size() : 0;
    }
}
