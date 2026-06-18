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

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;

    // 注册走 201 Created：新账号是服务端创建的新资源，符合 REST 语义而非 200。
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        log.info("[DEBUG] Register request received for username: {}", request.getUsername());
        AuthResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        log.info("[DEBUG] Login request received for identifier: {}", request.getIdentifier());
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    // 个人资料修改：解析 Authorization 头得到 userId，避免信任请求体里的 userId 字段
    // （防止越权改他人资料），所有需鉴权的写操作都走这个模式。
    @PutMapping("/profile")
    public ResponseEntity<AuthResponse> updateProfile(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody UpdateProfileRequest request) {
        log.info("[DEBUG] [update profile] headers auth = {}", authHeader);
        log.info("[DEBUG] [update profile] body = {}", request);

        UUID userId = extractUserIdFromToken(authHeader);
        log.info("[DEBUG] [update profile] extracted userId = {}", userId);

        AuthResponse response = authService.updateProfile(userId, request);
        return ResponseEntity.ok(response);
    }

    // 改密返回 200 + 空体：操作是幂等的（重复请求结果相同），无需返回新 token。
    @PatchMapping("/change-password")
    public ResponseEntity<Void> changePassword(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody ChangePasswordRequest request) {
        UUID userId = extractUserIdFromToken(authHeader);
        log.info("[DEBUG] [change password] extracted userId = {}", userId);
        authService.changePassword(userId, request);
        return ResponseEntity.ok().build();
    }

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
