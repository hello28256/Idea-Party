package com.ideaparty.service;

import com.ideaparty.dto.AuthResponse;
import com.ideaparty.dto.ChangePasswordRequest;
import com.ideaparty.dto.LoginRequest;
import com.ideaparty.dto.RegisterRequest;
import com.ideaparty.dto.UpdateProfileRequest;
import com.ideaparty.entity.User;
import com.ideaparty.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/**
 * 认证核心服务：负责用户注册、登录、JWT 签发/校验以及资料/密码更新。
 * 由 AuthController 调用；token 中 subject 存放 userId，供后续接口通过 validateToken 还原身份。
 * 不直接处理 HTTP 异常转换——统一抛出 IllegalArgumentException，由 Controller/GlobalExceptionHandler 翻译。
 */
@Service
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecretKey secretKey;
    private final long jwtExpiration;
    private final Random random = new Random();

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${jwt.secret}") String jwtSecret,
            @Value("${jwt.expiration}") long jwtExpiration,
            @Value("${jwt.secret.min-length:32}") int jwtSecretMinLength) {
        // 在构造期就把字符串 secret 解析为 SecretKey，避免每次签发/校验重复计算
        // 启动期显式校验密钥强度，避免弱密钥 / 默认占位符被部署到生产环境
        validateJwtSecret(jwtSecret, jwtSecretMinLength);
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.secretKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        // jwt.expiration 从配置注入（毫秒），便于按环境调整 token 生命周期
        this.jwtExpiration = jwtExpiration;
    }

    /**
     * 校验 JWT 签名密钥的强度：拒绝默认占位符与短于最小长度的密钥。
     * HS256 推荐至少 256 位 (32 字节)，不足时启动直接失败而不是悄悄签发弱 token。
     */
    private void validateJwtSecret(String secret, int minLength) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("jwt.secret 未配置，请通过环境变量 JWT_SECRET 注入");
        }
        if (secret.startsWith("CHANGE_ME") || secret.toUpperCase().contains("INSECURE")) {
            throw new IllegalStateException("jwt.secret 仍为默认占位符，请设置真实密钥（>= " + minLength + " 字节）");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < minLength) {
            throw new IllegalStateException("jwt.secret 过短（" + keyBytes.length + " 字节），HS256 至少需要 " + minLength + " 字节");
        }
    }

    /**
     * 注册新用户：邮箱可选但若提供需唯一；用户名必填且唯一。
     * 用户名统一存小写，避免登录时大小写歧义；displayName 保留原始大小写用于界面展示。
     * 注册成功直接签发 token，省去让用户再次登录的步骤。
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        // 邮箱可选：只在传了邮箱的情况下做唯一性校验，避免把空字符串误判为冲突
        if (request.getEmail() != null && !request.getEmail().isBlank() && userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email already registered");
        }

        if (userRepository.existsByUsername(request.getUsername())) {
            throw new IllegalArgumentException("Username already taken");
        }

        User user = User.builder()
                .email(request.getEmail())
                .username(request.getUsername().toLowerCase())
                .displayName(request.getUsername())
                .password(passwordEncoder.encode(request.getPassword()))
                .build();

        user = userRepository.save(user);
        String token = generateToken(user);

        return buildAuthResponse(token, user);
    }

    /**
     * 登录：用 identifier 同时支持用户名/邮箱（通过是否含 @ 区分），统一小写查询。
     * 用户不存在与密码错误返回相同文案，避免暴露账号是否存在（防枚举攻击）。
     */
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user;
        String identifier = request.getIdentifier().trim();

        // 用"是否含 @"区分登录方式：避免让用户在前端分别选"用户名/邮箱登录"
        if (identifier.contains("@")) {
            user = userRepository.findByEmail(identifier.toLowerCase())
                    .orElseThrow(() -> new IllegalArgumentException("Invalid username or password"));
        } else {
            user = userRepository.findByUsername(identifier.toLowerCase())
                    .orElseThrow(() -> new IllegalArgumentException("Invalid username or password"));
        }

        // 账号不存在 vs 密码错误用相同提示，防止通过响应差异枚举有效账号
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new IllegalArgumentException("Invalid username or password");
        }

        String token = generateToken(user);
        return buildAuthResponse(token, user);
    }

    /**
     * 生成 JWT：subject 直接放 userId 字符串，validateToken 解析时再还原为 UUID。
     * 不在 token 中放 username/email 等可变信息，避免资料更新后旧 token 与库数据不一致。
     */
    public String generateToken(User user) {
        return Jwts.builder()
                .subject(user.getId().toString())
                .issuedAt(new java.util.Date())
                .expiration(new java.util.Date(System.currentTimeMillis() + jwtExpiration))
                .signWith(secretKey)
                .compact();
    }

    /**
     * 更新用户资料：只处理 request 中非空字段；改完 username/email 后重新签发 token，
     * 让前端能立刻拿到包含最新 displayName/avatar 的 AuthResponse，省一次额外查询。
     * 含用户名 30 天冷却校验：防止刷名、保留身份稳定性（聊天室里 @ 别人、他人记忆靠 username）。
     */
    @Transactional
    public AuthResponse updateProfile(UUID userId, UpdateProfileRequest request) {
        log.info("[DEBUG] [update profile] userId = {}, request = {}", userId, request);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        log.info("[DEBUG] [update profile] current user from db: id={}, username={}, email={}",
                user.getId(), user.getUsername(), user.getEmail());

        String newUsername = null;
        String newEmail = null;

        // Validate and update username if changed
        if (request.getUsername() != null && !request.getUsername().isBlank()) {
            newUsername = request.getUsername().trim().toLowerCase();
            if (!newUsername.equals(user.getUsername())) {
                // Check username format: 3-20 chars, letters, numbers, underscores only
                if (!newUsername.matches("^[a-z0-9_]{3,20}$")) {
                    throw new IllegalArgumentException("用户名格式不正确");
                }
                // Check username uniqueness (exclude current user)
                if (userRepository.existsByUsername(newUsername)) {
                    throw new IllegalArgumentException("用户名已被占用");
                }
                // 30 天冷却期：username 在聊天室、他人记忆、历史消息中被广泛引用，
                // 过于频繁改名会破坏他人对身份的关联，因此业务上限制为 30 天一次
                if (user.getLastUsernameChangeAt() != null) {
                    Instant thirtyDaysAgo = Instant.now().minus(Duration.ofDays(30));
                    if (user.getLastUsernameChangeAt().isAfter(thirtyDaysAgo)) {
                        long daysRemaining = Duration.between(Instant.now(), user.getLastUsernameChangeAt().plus(Duration.ofDays(30))).toDays();
                        throw new IllegalArgumentException("用户名 30 天内只能修改一次，还需 " + daysRemaining + " 天才能再次修改");
                    }
                }
                user.setUsername(newUsername);
                user.setLastUsernameChangeAt(Instant.now());
                log.info("[DEBUG] [update profile] username updated to: {}, lastUsernameChangeAt set to now", newUsername);
            }
        }

        // Validate and update email if changed
        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            newEmail = request.getEmail().trim().toLowerCase();
            if (!newEmail.equals(user.getEmail())) {
                // Email format validation
                if (!newEmail.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
                    throw new IllegalArgumentException("邮箱格式不正确");
                }
                // Check email uniqueness (exclude current user)
                if (userRepository.existsByEmail(newEmail)) {
                    throw new IllegalArgumentException("邮箱已被使用");
                }
                user.setEmail(newEmail);
                log.info("[DEBUG] [update profile] email updated to: {}", newEmail);
            }
        }

        // Update displayName
        if (request.getDisplayName() != null && !request.getDisplayName().isBlank()) {
            user.setDisplayName(request.getDisplayName().trim());
        }

        user = userRepository.save(user);
        log.info("[DEBUG] [update profile] user saved, final state: id={}, username={}, email={}, displayName={}",
                user.getId(), user.getUsername(), user.getEmail(), user.getDisplayName());

        String token = generateToken(user);
        return buildAuthResponse(token, user);
    }

    /**
     * 修改密码：必须先验证当前密码，防止 token 泄露后攻击者直接改密踢掉原用户。
     * 改密后不吊销旧 token（无服务端 session 状态），靠 token 自身的过期时间兜底。
     */
    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest request) {
        log.info("[DEBUG] [change password] userId = {}", userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        log.info("[DEBUG] [change password] verify current password");
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            log.warn("[DEBUG] [change password] current password mismatch");
            throw new IllegalArgumentException("当前密码不正确");
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        log.info("[DEBUG] [change password] password updated success for userId = {}", userId);
    }

    /**
     * 校验 token 并返回 userId：把各类 JJWT 异常归一化为 IllegalArgumentException，
     * 由 Controller/Filter 统一映射为 401，避免把 jjwt 的内部错误类型泄漏给上层。
     */
    public UUID validateToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return UUID.fromString(claims.getSubject());
        } catch (ExpiredJwtException e) {
            log.warn("[DEBUG] JWT token expired: {}", e.getMessage());
            throw new IllegalArgumentException("Token has expired");
        } catch (MalformedJwtException e) {
            log.warn("[DEBUG] Malformed JWT token: {}", e.getMessage());
            throw new IllegalArgumentException("Invalid token format");
        } catch (UnsupportedJwtException e) {
            log.warn("[DEBUG] Unsupported JWT token: {}", e.getMessage());
            throw new IllegalArgumentException("Unsupported token");
        } catch (SignatureException e) {
            log.warn("[DEBUG] Invalid JWT signature: {}", e.getMessage());
            throw new IllegalArgumentException("Invalid token signature");
        } catch (IllegalArgumentException e) {
            log.warn("[DEBUG] JWT validation failed: {}", e.getMessage());
            throw new IllegalArgumentException("Token validation failed");
        }
    }

    public Optional<User> findUserById(UUID userId) {
        return userRepository.findById(userId);
    }

    private AuthResponse buildAuthResponse(String token, User user) {
        return AuthResponse.builder()
                .accessToken(token)
                .tokenType("Bearer")
                .expiresIn(jwtExpiration)
                .user(AuthResponse.UserResponse.builder()
                        .id(user.getId())
                        .username(user.getUsername())
                        .displayName(user.getDisplayName())
                        .email(user.getEmail())
                        .avatarUrl(user.getAvatarUrl())
                        .lastUsernameChangeAt(user.getLastUsernameChangeAt())
                        .themeMode(user.getThemeMode() != null ? user.getThemeMode() : "system")
                        .isAdmin(Boolean.TRUE.equals(user.getIsAdmin()))
                        .build())
                .build();
    }
}
