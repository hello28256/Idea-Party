package com.ideaparty.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 平台用户实体，承载登录身份、AI 角色归属与个人偏好设置。
 * 作为聚合根管理用户拥有的聊天室列表（级联删除），与 Room、AuthService、JwtAuthFilter 协作完成认证与会话隔离。
 */
@Entity
@Table(name = "users")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    // 主键采用 UUID 而非自增 Long：分布式部署下生成全局唯一 id 无需 DB round-trip，且不会泄露用户规模/创建顺序
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // 邮箱允许为空（支持纯用户名注册），但非空时必须唯一，作为找回密码/通知通道；登录接口按 username OR email 双重匹配
    @Column(unique = true)
    private String email;

    // 用户名是登录主键与 @ 引用锚点，不可为空且全局唯一；改名有冷却期（见 lastUsernameChangeAt）以保护历史消息中 @username 的稳定性
    @Column(unique = true, nullable = false)
    private String username;

    // 聊天室内展示名，可与 username 不同（支持中文/emoji）；用户可在设置页改，前端消息气泡按消息发送时刻的 displayName 渲染以保证历史一致性
    @Column(nullable = false)
    private String displayName;

    // 已用 BCrypt 哈希后的密码（cost ≥ 10）；AuthService.login 负责比对，永远不要把明文持久化到该字段或日志中
    @Column(nullable = false)
    private String password;

    // 账户创建时间；updatable=false 防止后续 save 调用被脏检查误覆盖，是审计/排行榜/老用户标记的事实来源
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // 最近一次写库时间戳；onUpdate() 回调刷新，用于乐观锁之外的“最近活跃”判断与缓存失效
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // 用户级 LLM 凭证（OpenAI 兼容）；为空时 AIService 回落到服务端默认 Key，实现“付费用户自带额度”模式
    @Column(name = "api_key")
    private String apiKey;

    // 用户名变更节流锚点；前端冷却 UI 与后端校验都依赖此字段，避免短期内频繁改名破坏 @ 引用稳定性
    @Column(name = "last_username_change_at")
    private Instant lastUsernameChangeAt;

    // 用户头像外链；前端 <Avatar> 组件直接渲染，未设置时回落到 displayName 首字母占位
    @Column(name = "avatar_url")
    private String avatarUrl;

    // 默认 "system" 而非 "light"：跟随操作系统主题，避免首次登录用户被强加一种外观
    @Builder.Default
    @Column(name = "theme_mode")
    private String themeMode = "system";

    // 普通用户默认 false；后台管理接口（/api/admin/**）的 @PreAuthorize 通过此字段放行，避免单独建角色表
    @Builder.Default
    @Column(name = "is_admin", nullable = false)
    private Boolean isAdmin = false;

    // JPA 生命周期回调：在首次持久化前写入时间戳，保证 created_at/updated_at 同源且无需业务层重复设值
    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    // JPA 生命周期回调：每次 UPDATE 前刷新 updatedAt，让审计字段无需业务代码手动维护
    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    // orphanRemoval=true：用户删除时连同其聊天室一并清理，避免遗留无主房间污染查询与计费
    @OneToMany(mappedBy = "owner", cascade = CascadeType.ALL, orphanRemoval = true)
    // Builder.Default 防止 Lombok @Builder 把 rooms 置为 null；新建用户即拥有可变集合，RoomService 可直接 add 而非判空
    @Builder.Default
    private List<Room> rooms = new ArrayList<>();
}
