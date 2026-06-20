package com.ideaparty.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * 登录/注册成功后下发的认证响应载荷，供前端建立会话与渲染用户资料。
 * 与 JwtService 配对：accessToken 由 JwtService 签发，expiresIn 用于前端倒计时刷新，
 * UserResponse 一次性回带基础资料，避免登录后再发一次 /me 请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {

    /**
     * 由 JwtService 签发的 JWT 字符串，前端写入 Authorization 头；调用方负责在响应序列化前注入。
     */
    private String accessToken;

    // 固定为 OAuth2 风格的 Bearer 方案，前端 Authorization 头拼装逻辑依赖此默认值。
    @Builder.Default
    private String tokenType = "Bearer";

    // 单位为秒，与 OAuth2 RFC 6749 / RFC 6750 保持一致；前端据此实现 access token 静默刷新。
    private long expiresIn;

    /**
     * 登录态建立时一次性回带的用户基础资料，避免前端登录后再额外请求 /me 接口。
     */
    private UserResponse user;

    /**
     * 内嵌的用户资料 DTO，用于在登录/注册响应里一次性带回当前账户的基础信息，
     * 与 User 实体解耦——只暴露前端需要的字段，避免序列化敏感列（如密码哈希）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserResponse {
        /**
         * 用户主键 UUID，前端用作稳定的用户标识符，避免泄露自增 ID 与用户量级。
         */
        private UUID id;
        /**
         * 登录名，全局唯一；前端在 @ 提及或房间成员列表中作为默认显示后备。
         */
        private String username;
        /**
         * 用户可自定义的展示名，可为空；为空时前端应回退到 username。
         */
        private String displayName;
        /**
         * 邮箱地址，仅用于资料展示；任何邮件相关功能需额外走验证码校验。
         */
        private String email;
        /**
         * 头像 URL，可为空；为空时前端用首字母占位图渲染。
         */
        private String avatarUrl;
        /**
         * 上次修改用户名的服务端时间戳，前端据此判断是否仍在改名校验冷却期内。
         */
        private Instant lastUsernameChangeAt;
        // 未在偏好表中显式保存时降级到 system，跟随操作系统主题；与前端 store 初始值保持一致。
        @Builder.Default
        private String themeMode = "system";
        // 默认非管理员；提升权限只能由服务端在用户表上写入，不允许由客户端请求体控制。
        @Builder.Default
        private Boolean isAdmin = false;
    }
}
