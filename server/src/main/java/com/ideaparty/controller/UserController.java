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

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
@Slf4j
public class UserController {

    private final UserRepository userRepository;
    private final AuthService authService;

    private static final Set<String> ALLOWED_AVATAR_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    // 头像大小上限改为从配置注入，与 FileUploadController / application.yml 保持一致，
    // 避免在两个上传入口维护两份硬编码常量。
    @Value("${upload.avatar.max-size:5242880}")
    private long maxAvatarSize;
    private static final String UPLOAD_DIR = "uploads/avatars/";

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

    private UUID extractUserIdFromToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new IllegalArgumentException("Missing or invalid Authorization header");
        }
        String token = authHeader.substring(7);
        return authService.validateToken(token);
    }
}
