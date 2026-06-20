package com.ideaparty.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * 用户个人资料响应 DTO。
 * 作为 GET /auth/me 等接口的返回载体，向前端暴露当前登录用户的基础档案与偏好设置；
 * 字段来源于 User 实体，但只挑选对外可见的子集，避免泄露密码哈希等敏感列。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileResponse {
    /**
     * 用户的全局唯一标识 UUID。
     * 前端在 WebSocket 鉴权与跨服务引用（如消息归属）时使用，不可对外暴露自增 ID 以避免枚举。
     */
    private UUID id;

    /**
     * 用户名（唯一登录账号）。
     * 用于 @ 提及、URL 路由（如 /users/{username}）以及前端顶栏展示。
     */
    private String username;

    /**
     * 展示名（昵称）。
     * 优先于 username 用于聊天室内气泡、评论等场景，未设置时回退到 username。
     */
    private String displayName;

    /**
     * 用户邮箱。
     * 仅用于前端账户设置页展示与是否已验证徽标，业务逻辑不依赖邮箱做唯一判定（避免隐私问题）。
     */
    private String email;

    /**
     * 头像图片 URL（绝对或 CDN 路径）。
     * 为空时前端应回退到默认头像，因此后端不强制写入占位值。
     */
    private String avatarUrl;

    /**
     * 用户名最近一次修改时间。
     * 用于前端判断「用户名冷却期」（项目内禁止过频改名）与审计追溯；为空表示从未改过。
     */
    private Instant usernameUpdatedAt;

    /**
     * 主题模式偏好（"light" / "dark" / "system" 等）。
     * 由前端保存并回传给后端以便跨设备同步；与浏览器 localStorage 形成双写策略。
     */
    private String themeMode;

    /**
     * 是否管理员标志。
     * 使用包装类型 Boolean 而非基本类型，便于在未赋值时与 false 区分；前端根据该位控制后台入口显隐。
     */
    private Boolean isAdmin;
}
