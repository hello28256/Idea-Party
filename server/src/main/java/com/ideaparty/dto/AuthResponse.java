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

    private String accessToken;

    // 固定为 OAuth2 风格的 Bearer 方案，前端 Authorization 头拼装逻辑依赖此默认值。
    @Builder.Default
    private String tokenType = "Bearer";

    // 单位为秒，与 OAuth2 RFC 6749 / RFC 6750 保持一致；前端据此实现 access token 静默刷新。
    private long expiresIn;

    private UserResponse user;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserResponse {
        private UUID id;
        private String username;
        private String displayName;
        private String email;
        private String avatarUrl;
        private Instant lastUsernameChangeAt;
        // 未在偏好表中显式保存时降级到 system，跟随操作系统主题；与前端 store 初始值保持一致。
        @Builder.Default
        private String themeMode = "system";
        // 默认非管理员；提升权限只能由服务端在用户表上写入，不允许由客户端请求体控制。
        @Builder.Default
        private Boolean isAdmin = false;
    }
}
