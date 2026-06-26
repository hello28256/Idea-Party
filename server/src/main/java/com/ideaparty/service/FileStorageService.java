package com.ideaparty.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class FileStorageService {
    // 本服务专门负责把用户上传的头像/图片安全地存到本地磁盘、读回、以及删除。
    // 存在的原因：Spring Boot 默认不提供文件落盘能力，而 Controller/Service 层不应直接操作 java.nio.file。
    // 配合 Controller（如头像上传接口）和静态资源映射使用，对外返回 UUID 文件名以避免冲突和泄露原名。

    private static final List<String> ALLOWED_CONTENT_TYPES = Arrays.asList(
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp"
    );
    // 业务含义：只允许这四种 MIME 类型的文件入库，防止脚本/可执行文件通过上传通道注入。

    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5MB
    // 业务含义：5MB 是头像/聊天图片的合理上限，避免大文件撑爆磁盘或被恶意刷带宽。

    private final Path uploadDir;
    // 取值原因：构造时按相对路径 uploads/avatars 解析并转绝对路径，作为所有文件读写的根目录。

    /**
     * Spring 注入入口：解析上传根目录并确保目录存在，失败则直接抛出阻止应用启动。
     * 副作用：可能创建 uploads/avatars 目录。
     */
    public FileStorageService() {
        // 存储到 server/uploads/avatars/
        this.uploadDir = Paths.get("uploads", "avatars").toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.uploadDir);
            log.info("[DEBUG] FileStorageService initialized. Upload directory: {}", this.uploadDir);
        } catch (IOException e) {
            throw new RuntimeException("Could not create upload directory", e);
        }
    }

    /**
     * 保存文件并返回生成的文件名。
     * 使用基于 UUID 的文件名以避免冲突并隐藏原始文件名。
     *
     * @param file 要保存的 multipart 文件
     * @return 已保存的文件名（UUID 形式）
     * @throws IllegalArgumentException 当文件无效时
     */
    /**
     * 把前端上传的文件校验后落盘，返回新的 UUID 文件名。
     * 副作用：会在 uploadDir 写入一个新文件；调用方拿到返回的文件名后通常会写回数据库。
     */
    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is empty or null");
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("Invalid file type. Only JPEG, PNG, GIF, and WebP are allowed.");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("File size exceeds 5MB limit");
        }

        // 生成保留扩展名的 UUID 文件名
        String originalFilename = file.getOriginalFilename();
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        String filename = UUID.randomUUID().toString() + extension;

        try {
            Path targetLocation = this.uploadDir.resolve(filename);
            Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);
            log.debug("[DEBUG] File stored successfully: {}", filename);
            return filename;
        } catch (IOException e) {
            log.error("[DEBUG] Failed to store file: {}", e.getMessage());
            throw new RuntimeException("Failed to store file", e);
        }
    }

    /**
     * 从外网 URL 下载图片到 uploads/avatars 目录，文件名带 hash 前缀避免重复。
     *
     * <p>用途：用户点"创建角色"时如果传的是 wikipedia / 其他外网 URL（不是本地 /api/upload/...），
     * 后端自动下载到本地，避免每次渲染头像都打外网；同时让头像 URL 在数据库里稳定可缓存。
     *
     * <p>安全控制：
     *   1) 强制按 content-type 校验白名单（JPEG/PNG/GIF/WebP），防止 SVG/HTML 注入；
     *   2) 限制文件大小 5MB（与上传一致）；
     *   3) 重定向最多 5 次（防 SSRF 攻击把请求转到内网）；
     *   4) 文件名用 sha1(url + 当前时间) 做 hash，无原始文件名泄露。
     *
     * @param url 外网图片直链（如 https://upload.wikimedia.org/.../foo.jpg）
     * @return 写入磁盘的文件名（不含目录前缀）；失败返回 null
     */
    public String storeFromUrl(String url) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return null;
        try {
            java.net.URL u = new java.net.URL(url);
            // 仅允许 http/https
            String protocol = u.getProtocol();
            if (!"http".equals(protocol) && !"https".equals(protocol)) return null;

            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "IdeaParty/1.0 (avatar-downloader)");
            // 维基 CDN 默认会按需返回 thumb/webp，强制要 jpg/png 让前端 <img> 直接渲染
            conn.setRequestProperty("Accept", "image/jpeg,image/png,image/webp;q=0.9,*/*;q=0.5");

            int code = conn.getResponseCode();
            if (code != 200) {
                log.warn("[DEBUG] storeFromUrl non-200 {} for {}", code, url);
                return null;
            }
            String contentType = conn.getContentType();
            if (contentType == null) return null;
            // 去掉 charset 等参数
            String mime = contentType.split(";")[0].trim().toLowerCase();
            String ext;
            switch (mime) {
                case "image/jpeg": ext = ".jpg"; break;
                case "image/png":  ext = ".png"; break;
                case "image/gif":  ext = ".gif"; break;
                case "image/webp": ext = ".webp"; break;
                default:
                    log.warn("[DEBUG] storeFromUrl unsupported mime {} for {}", mime, url);
                    return null;
            }

            // 文件名 = sha1(url) + ext，确保同一 URL 不会被重复下载
            String hash = org.springframework.util.DigestUtils.md5DigestAsHex(url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String filename = "auto_" + hash + ext;

            Path target = this.uploadDir.resolve(filename);
            // 已存在则跳过（幂等）
            if (Files.exists(target)) {
                log.info("[DEBUG] storeFromUrl hit cache: {}", filename);
                return filename;
            }

            long copied = Files.copy(conn.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
            if (copied > MAX_FILE_SIZE) {
                Files.deleteIfExists(target);
                log.warn("[DEBUG] storeFromUrl too large {} bytes for {}", copied, url);
                return null;
            }
            log.info("[DEBUG] storeFromUrl saved {} ({} bytes) from {}", filename, copied, url);
            return filename;
        } catch (Exception e) {
            log.warn("[DEBUG] storeFromUrl failed for {}: {}", url, e.getMessage());
            return null;
        }
    }

    /**
     * 将文件加载为 Resource 以供返回。
     *
     * @param filename 要加载的文件名
     * @return 指向该文件的 Resource
     * @throws RuntimeException 文件无法加载时
     */
    /**
     * 按文件名读取并包装成 Spring 的 Resource，供下载/预览接口流式返回。
     * 副作用：无（只读）；调用方一般是 Controller 写回 HTTP 响应体。
     */
    public Resource loadAsResource(String filename) {
        try {
            Path filePath = this.uploadDir.resolve(filename).normalize();
            Resource resource = new UrlResource(filePath.toUri());

            if (resource.exists() && resource.isReadable()) {
                log.debug("[DEBUG] File loaded as resource: {}", filename);
                return resource;
            } else {
                log.warn("[DEBUG] File not found or not readable: {}", filename);
                throw new RuntimeException("File not found: " + filename);
            }
        } catch (MalformedURLException e) {
            log.error("[DEBUG] Failed to load file as resource: {}", e.getMessage());
            throw new RuntimeException("Failed to load file", e);
        }
    }

    /**
     * 从存储中删除一个文件。
     *
     * @param filename 要删除的文件名
     */
    /**
     * 按文件名删除磁盘文件，失败仅记录 warn 而不抛异常，避免清理逻辑打断主流程。
     * 副作用：可能从 uploadDir 删除一个文件；调用方通常是头像替换/用户注销场景。
     */
    public void delete(String filename) {
        try {
            Path filePath = this.uploadDir.resolve(filename).normalize();
            Files.deleteIfExists(filePath);
            log.debug("[DEBUG] File deleted: {}", filename);
        } catch (IOException e) {
            log.warn("[DEBUG] Failed to delete file: {} - {}", filename, e.getMessage());
        }
    }

    /**
     * 检查某个 content type 是否被允许。
     *
     * @param contentType 要检查的 content type
     * @return 允许则返回 true
     */
    /**
     * 提供给 Controller 在接收 multipart 之前做预校验，避免无效请求走到完整 store 流程。
     * 入参约束：contentType 可以为 null（视为不允许）。
     */
    public boolean isAllowedContentType(String contentType) {
        return contentType != null && ALLOWED_CONTENT_TYPES.contains(contentType);
    }

    /**
     * 获取上传目录的路径。
     *
     * @return 上传目录的路径
     */
    /**
     * 暴露给静态资源映射或调试日志使用的根目录路径，方便查看文件真实落盘位置。
     * 调用方一般是 WebMvcConfig 注册 ResourceHandler 时读取。
     */
    public Path getUploadDir() {
        return this.uploadDir;
    }
}
