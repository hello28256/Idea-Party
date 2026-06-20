package com.ideaparty.controller;

import com.ideaparty.dto.AvatarUploadResponse;
import com.ideaparty.dto.UpdatePreferencesRequest;
import com.ideaparty.dto.UserProfileResponse;
import com.ideaparty.entity.User;
import com.ideaparty.repository.UserRepository;
import com.ideaparty.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.UUID;

/**
 * 用户个人资料与偏好的 HTTP 入口。
 * 暴露当前登录用户自己的资料读取、头像上传、主题偏好更新；
 * 不负责注册/登录/Token 颁发，这些由 AuthController + AuthService 负责。
 * 与前端 settings 页面、头像裁剪组件直接对接。
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
@Slf4j
public class UserController {

    /** 用于读取/更新 users 表，由 Spring 注入。 */
    private final UserRepository userRepository;
    /** 用于从 JWT 中解析当前 userId，依赖全局统一的 token 校验逻辑。 */
    private final AuthService authService;

    /** 头像 MIME 白名单：仅允许 jpg/png/webp，统一前后端预期，防止上传可执行文件伪装成头像。 */
    private static final Set<String> ALLOWED_AVATAR_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    // 头像大小上限改为从配置注入，与 FileUploadController / application.yml 保持一致，
    // 避免在两个上传入口维护两份硬编码常量。
    /** 头像体积上限（字节），默认 5MB；通过 @Value 从 application.yml 注入，便于运维按磁盘配额调整。 */
    @Value("${upload.avatar.max-size:5242880}")
    private long maxAvatarSize;
    /** 头像落盘的相对目录（相对于 JVM 工作目录），与 WebMvcConfig 中静态映射 /uploads/** 配套。 */
    private static final String UPLOAD_DIR = "uploads/avatars/";

    /**
     * 获取当前登录用户的个人资料快照。
     * 入参：必须带 Bearer Token；副作用：无（纯读）；
     * 返回：UserProfileResponse，themeMode 缺失时回退为 "system"，isAdmin 做空安全转换。
     * 调用方：前端 settings 页、个人信息卡片、首次登录后拉取。
     */
    @GetMapping("/profile")
    public ResponseEntity<UserProfileResponse> getProfile(@RequestHeader("Authorization") String authHeader) {
        UUID userId = extractUserIdFromToken(authHeader);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        UserProfileResponse response = UserProfileResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .displayName(user.getDisplayName())
                .email(user.getEmail())
                .avatarUrl(user.getAvatarUrl())
                .usernameUpdatedAt(user.getLastUsernameChangeAt())
                .themeMode(user.getThemeMode() != null ? user.getThemeMode() : "system")
                .isAdmin(Boolean.TRUE.equals(user.getIsAdmin()))
                .build();

        return ResponseEntity.ok(response);
    }

    /**
     * 上传并替换当前用户的头像。
     * 入参：Bearer Token + 名为 "file" 的 MultipartFile；副作用：写入 uploads/avatars/ 目录、
     * 更新 users.avatar_url；返回：新头像的相对 URL（前端拼接 host 使用）。
     * 校验顺序：非空 → MIME 白名单 → 体积上限 → 落盘，保证先把大文件/危险类型挡在写盘之前。
     */
    @PostMapping("/avatar")
    public ResponseEntity<AvatarUploadResponse> uploadAvatar(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam("file") MultipartFile file) {
        UUID userId = extractUserIdFromToken(authHeader);

        // Validate file
        if (file.isEmpty()) {
            throw new IllegalArgumentException("头像文件不能为空");
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_AVATAR_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("不支持的头像格式，仅支持 jpg/jpeg/png/webp");
        }

        if (file.getSize() > maxAvatarSize) {
            throw new IllegalArgumentException("头像文件过大，最大 " + (maxAvatarSize / 1024 / 1024) + "MB");
        }

        try {
            // Create upload directory if not exists
            Path uploadPath = Paths.get(UPLOAD_DIR);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            // Generate unique filename
            String originalFilename = file.getOriginalFilename();
            String extension = originalFilename != null && originalFilename.contains(".")
                    ? originalFilename.substring(originalFilename.lastIndexOf("."))
                    : ".png";
            String filename = "avatar_" + userId.toString() + "_" + System.currentTimeMillis() + extension;

            // Save file
            Path filePath = uploadPath.resolve(filename);
            Files.copy(file.getInputStream(), filePath);

            // Update user avatar URL
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
            String avatarUrl = "/uploads/avatars/" + filename;
            user.setAvatarUrl(avatarUrl);
            userRepository.save(user);

            log.info("[DEBUG] [uploadAvatar] userId={}, avatarUrl={}", userId, avatarUrl);

            return ResponseEntity.ok(AvatarUploadResponse.builder()
                    .avatarUrl(avatarUrl)
                    .build());
        } catch (Exception e) {
            log.error("[DEBUG] [uploadAvatar] failed for userId={}, error={}", userId, e.getMessage());
            throw new RuntimeException("头像上传失败");
        }
    }

    /**
     * 更新当前用户的偏好设置（目前仅主题模式）。
     * 入参：Bearer Token + { themeMode: "system"|"light"|"dark" }；
     * 副作用：写入 users.theme_mode；返回：更新后的完整 UserProfileResponse，便于前端直接刷新 store。
     * 当前白名单只放行三档主题，避免前端传入未支持的 CSS 模式字符串污染数据库。
     */
    @PutMapping("/preferences")
    public ResponseEntity<UserProfileResponse> updatePreferences(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody UpdatePreferencesRequest request) {
        UUID userId = extractUserIdFromToken(authHeader);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        // Validate theme mode
        String themeMode = request.getThemeMode();
        if (themeMode == null || (!themeMode.equals("system") && !themeMode.equals("light") && !themeMode.equals("dark"))) {
            throw new IllegalArgumentException("无效的主题模式");
        }

        user.setThemeMode(themeMode);
        user = userRepository.save(user);

        return ResponseEntity.ok(UserProfileResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .displayName(user.getDisplayName())
                .email(user.getEmail())
                .avatarUrl(user.getAvatarUrl())
                .usernameUpdatedAt(user.getLastUsernameChangeAt())
                .themeMode(user.getThemeMode())
                .isAdmin(Boolean.TRUE.equals(user.getIsAdmin()))
                .build());
    }

    /**
     * 从 Authorization: Bearer xxx 头中解析出当前 userId。
     * 入参：完整的 Authorization 头；副作用：调用 AuthService.validateToken 校验签名/有效期；
     * 返回：userId 的 UUID。缺失前缀或 token 无效时直接抛 IllegalArgumentException，由全局异常处理器转为 401/400。
     * 本类所有接口都依赖此方法做鉴权前置，避免每个接口重复解析逻辑。
     */
    private UUID extractUserIdFromToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new IllegalArgumentException("Missing or invalid Authorization header");
        }
        String token = authHeader.substring(7);
        return authService.validateToken(token);
    }
}
