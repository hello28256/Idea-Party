package com.ideaparty.controller;

import com.ideaparty.dto.AuthResponse;
import com.ideaparty.dto.ChangePasswordRequest;
import com.ideaparty.dto.LoginRequest;
import com.ideaparty.dto.RegisterRequest;
import com.ideaparty.dto.UpdateProfileRequest;
import com.ideaparty.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 认证相关 HTTP 入口：注册、登录、修改资料、改密。
 * 把 JWT 解析收敛在 controller 层，让 service 只关心业务，避免每个 endpoint 重复 header 处理样板。
 * 与 {@link com.ideaparty.security.JwtAuthFilter}、{@link AuthService} 配合：filter 负责后续接口的鉴权，
 * 本 controller 仅处理未认证（登录/注册）与"已认证用户的资料维护"两类场景。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    /**
     * 业务委托对象：所有鉴权/账号/资料逻辑都在 service 层完成，controller 只做参数装配与状态码选择。
     * 通过 Lombok {@code @RequiredArgsConstructor} 注入 final 字段，便于单测时直接替换为 mock。
     */
    private final AuthService authService;

    /**
     * 用户注册：成功返回 201 Created + AuthResponse（含 token，便于前端注册后直接进入登录态）。
     * 入参走 {@code @Valid} 由 Bean Validation 校验，违规由全局异常处理器转 400。
     *
     * @param request 注册请求体（用户名/邮箱/密码等），由 service 校验唯一性并 hash 密码
     * @return 201 + AuthResponse（含 JWT 与用户资料）
     */
    // 注册走 201 Created：新账号是服务端创建的新资源，符合 REST 语义而非 200。
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        log.info("[DEBUG] Register request received for username: {}", request.getUsername());
        AuthResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 用户登录：支持用户名或邮箱登录（由 service 通过 identifier 字段自适应），返回 200 + AuthResponse。
     * 失败由 service 抛业务异常（如账号不存在/密码错误），由全局异常处理器映射为 401/4xx。
     *
     * @param request 登录请求体（identifier + password）
     * @return 200 + AuthResponse（含 JWT 与用户资料）
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        log.info("[DEBUG] Login request received for identifier: {}", request.getIdentifier());
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    /**
     * 更新当前登录用户的个人资料（如昵称/邮箱/头像等）。
     * userId 从 Authorization 头解析得到，不信任请求体里的 userId，防止越权改他人资料。
     *
     * @param authHeader {@code Bearer <jwt>} 形式的请求头，用于解析当前登录用户 ID
     * @param request    资料更新请求体，字段可空表示不修改
     * @return 200 + AuthResponse（含最新的 token 与资料，方便前端一次拿到刷新后的状态）
     */
    // 个人资料修改：解析 Authorization 头得到 userId，避免信任请求体里的 userId 字段
    // （防止越权改他人资料），所有需鉴权的写操作都走这个模式。
    @PutMapping("/profile")
    public ResponseEntity<AuthResponse> updateProfile(@RequestHeader("Authorization") String authHeader, @RequestBody UpdateProfileRequest request) {
        log.info("[DEBUG] [update profile] headers auth = {}", authHeader);
        log.info("[DEBUG] [update profile] body = {}", request);

        UUID userId = extractUserIdFromToken(authHeader);
        log.info("[DEBUG] [update profile] extracted userId = {}", userId);

        AuthResponse response = authService.updateProfile(userId, request);
        return ResponseEntity.ok(response);
    }

    /**
     * 修改当前用户密码。
     * 改密是幂等操作（同样的旧密+新密多次调用结果一致），且不需要返回新 token，因此响应体为空。
     *
     * @param authHeader {@code Bearer <jwt>} 形式的请求头
     * @param request    含旧密码 + 新密码，用于 service 校验旧密后再覆盖
     * @return 200 + 空体
     */
    // 改密返回 200 + 空体：操作是幂等的（重复请求结果相同），无需返回新 token。
    @PatchMapping("/change-password")
    public ResponseEntity<Void> changePassword(@RequestHeader("Authorization") String authHeader, @RequestBody ChangePasswordRequest request) {
        UUID userId = extractUserIdFromToken(authHeader);
        log.info("[DEBUG] [change password] extracted userId = {}", userId);
        authService.changePassword(userId, request);
        return ResponseEntity.ok().build();
    }

    /**
     * 从 {@code Authorization: Bearer <token>} 头中解析并校验 JWT，返回 token 对应的 userId。
     * 解析与校验合二为一：减少每个接口重复样板；同时把"非法 header"这类调用方错误
     * 抛为 {@link IllegalArgumentException}，由全局异常处理器转 400；token 非法/过期由 service 抛业务异常。
     *
     * @param authHeader 原始 Authorization 头；为 null 或不以 "Bearer " 开头都视为非法
     * @return token 中携带的用户 UUID
     * @throws IllegalArgumentException 当 header 缺失或格式错误时抛出
     */
    // 解析 + 校验合二为一：减少每个接口重复样板；同时把"非法 header"这类调用方错误
    // 抛为 IllegalArgumentException，由全局异常处理器转 400。
    private UUID extractUserIdFromToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new IllegalArgumentException("Missing or invalid Authorization header");
        }
        String token = authHeader.substring(7);
        return authService.validateToken(token);
    }
}
