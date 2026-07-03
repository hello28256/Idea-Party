package com.ideaparty.controller;

import com.ideaparty.dto.AvatarUploadResponse;
import com.ideaparty.dto.UpdatePreferencesRequest;
import com.ideaparty.dto.UserProfileResponse;
import com.ideaparty.entity.User;
import com.ideaparty.repository.UserRepository;
import com.ideaparty.service.AuthService;
import com.ideaparty.util.ImageUrlResolver;
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
    /** avatarUrl 转完整 OSS URL,响应序列化前统一过这道闸 */
    private final ImageUrlResolver imageUrlResolver;

    /** 头像 MIME 白名单：仅允许 jpg/png/webp，统一前后端预期，防止上传可执行文件伪装成头像。 */
    private static final Set<String> ALLOWED_AVATAR_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    // 头像大小上限改为从配置注入，与 application.yml 保持一致,
    // 避免在多个入口维护两份硬编码常量。
    /** 头像体积上限（字节），默认 5MB；通过 @Value 从 application.yml 注入，便于运维按磁盘配额调整。 */
    @Value("${upload.avatar.max-size:5242880}")
    private long maxAvatarSize;
    /** 头像落盘的相对目录已废弃(OSS 直传),保留仅用于向后兼容老逻辑 */
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
                .build()
                .resolveImageUrls(imageUrlResolver);

        return ResponseEntity.ok(response);
    }

    /**
     * 上传并替换当前用户的头像。
     * 入参：Bearer Token + 名为 "file" 的 MultipartFile；副作用：写入 uploads/avatars/ 目录、
     * 更新 users.avatar_url；返回：新头像的相对 URL（前端拼接 host 使用）。
     * 校验顺序：非空 → MIME 白名单 → 体积上限 → 落盘，保证先把大文件/危险类型挡在写盘之前。
     */
    @PostMapping("/avatar")
    public ResponseEntity<AvatarUploadResponse> uploadAvatar(@RequestHeader("Authorization") String authHeader, @RequestParam("file") MultipartFile file) {
        // OSS 迁移后,头像上传走 STS 凭证浏览器直传(GET /api/uploads/sts-token 拿凭证 → 直接 PutObject 到 OSS)
        // 前端拿到完整 URL 后会调 PUT /api/user/avatar 把 URL 存到 DB(见下方 saveAvatarUrl)
        // 这个老接口保留 410 Gone 避免误调
        log.warn("[DEBUG] uploadAvatar multipart called but endpoint is deprecated; use STS 直传 instead");
        return ResponseEntity.status(410).body(AvatarUploadResponse.builder()
                .avatarUrl("")
                .build());
    }

    /**
     * 保存 STS 直传后的头像 URL 到 DB(只换 url,不传文件)。
     * 流程: 前端 1) 调 /api/uploads/sts-token 拿凭证 2) cos.putObject 上传 3) 调本接口把返回的完整 URL 存到 users.avatar_url
     * 校验:URL 必须是 https:// 开头且指向 idea-party-uploads-1361890600.cos.ap-seoul.myqcloud.com(防 SSRF)
     *
     * PR3: 阿里云 OSS → 腾讯云 COS,允许的桶域名更新。
     */
    @PutMapping("/avatar")
    public ResponseEntity<AvatarUploadResponse> saveAvatarUrl(@RequestHeader("Authorization") String authHeader, @RequestBody AvatarUrlRequest request) {
        UUID userId = extractUserIdFromToken(authHeader);
        String url = request == null ? null : request.avatarUrl();
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("avatarUrl 不能为空");
        }
        // 简单 SSRF 防护:只允许 COS 桶域名
        if (!url.startsWith("https://idea-party-uploads-1361890600.cos.ap-seoul.myqcloud.com/")) {
            throw new IllegalArgumentException("avatarUrl 必须指向 COS 桶 idea-party-uploads-1361890600.cos.ap-seoul.myqcloud.com");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        user.setAvatarUrl(url);
        userRepository.save(user);
        log.info("[DEBUG] [saveAvatarUrl] userId={}, avatarUrl={}", userId, url);
        return ResponseEntity.ok(AvatarUploadResponse.builder().avatarUrl(url).build().resolveImageUrls(imageUrlResolver));
    }

    /** STS 直传后保存 URL 的请求体 */
    public record AvatarUrlRequest(String avatarUrl) {}

    /**
     * 更新当前用户的偏好设置（目前仅主题模式）。
     * 入参：Bearer Token + { themeMode: "system"|"light"|"dark" }；
     * 副作用：写入 users.theme_mode；返回：更新后的完整 UserProfileResponse，便于前端直接刷新 store。
     * 当前白名单只放行三档主题，避免前端传入未支持的 CSS 模式字符串污染数据库。
     */
    @PutMapping("/preferences")
    public ResponseEntity<UserProfileResponse> updatePreferences(@RequestHeader("Authorization") String authHeader, @RequestBody UpdatePreferencesRequest request) {
        UUID userId = extractUserIdFromToken(authHeader);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        // 校验主题模式
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
                .build()
                .resolveImageUrls(imageUrlResolver));
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
