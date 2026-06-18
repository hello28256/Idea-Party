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

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(unique = true)
    private String email;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(nullable = false)
    private String displayName;

    @Column(nullable = false)
    private String password;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "api_key")
    private String apiKey;

    // 用户名变更节流锚点；前端冷却 UI 与后端校验都依赖此字段，避免短期内频繁改名破坏 @ 引用稳定性
    @Column(name = "last_username_change_at")
    private Instant lastUsernameChangeAt;

    @Column(name = "avatar_url")
    private String avatarUrl;

    // 默认 "system" 而非 "light"：跟随操作系统主题，避免首次登录用户被强加一种外观
    @Builder.Default
    @Column(name = "theme_mode")
    private String themeMode = "system";

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

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    // orphanRemoval=true：用户删除时连同其聊天室一并清理，避免遗留无主房间污染查询与计费
    @OneToMany(mappedBy = "owner", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Room> rooms = new ArrayList<>();
}
