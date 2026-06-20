package com.ideaparty.controller;

import com.ideaparty.service.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 文件上传 HTTP 控制器。
 * 负责头像图片的上传与对外读取，与 FileStorageService 配合完成磁盘落地与访问 URL 拼接。
 * 由前端 My Rooms / 角色编辑等场景调用，是用户自定义头像图片的唯一入口。
 */
@RestController
@RequestMapping("/api/upload")
@Slf4j
public class FileUploadController {

    // 实际落盘与内容类型校验委托给 FileStorageService，避免在 Controller 层直接耦合文件系统实现。
    private final FileStorageService fileStorageService;
    // 头像最大字节数：从配置 upload.avatar.max-size 注入，默认 5MB；超过即拒绝上传，防止大文件占盘与拖慢响应。
    private final long maxAvatarSize;

    /**
     * 构造注入：把 Service 与配置项在启动期装配完毕，运行时不再依赖容器查找，便于单元测试直接 new。
     *
     * @param fileStorageService 文件存储服务，负责真正的写盘与白名单校验
     * @param maxAvatarSize      单张头像上限字节数，由 application.yml 的 upload.avatar.max-size 提供
     */
    public FileUploadController(
            FileStorageService fileStorageService,
            @Value("${upload.avatar.max-size:5242880}") long maxAvatarSize) {
        this.fileStorageService = fileStorageService;
        this.maxAvatarSize = maxAvatarSize;
    }

    /**
     * 上传头像图片。
     * 接收字段名为 "avatar" 的 multipart/form-data 请求。
     *
     * @param file 要上传的头像文件
     * @return 已上传文件的 URL
     */
    @PostMapping("/avatar")
    public ResponseEntity<Map<String, String>> uploadAvatar(@RequestParam("avatar") MultipartFile file) {
        log.debug("[DEBUG] uploadAvatar called with file: {}", file != null ? file.getOriginalFilename() : "null");

        // 全部校验失败交给 GlobalExceptionHandler：保证错误体格式与其它接口一致 (ErrorResponse)
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is required");
        }

        String contentType = file.getContentType();
        if (!fileStorageService.isAllowedContentType(contentType)) {
            throw new IllegalArgumentException("Invalid file type. Only JPEG, PNG, GIF, and WebP are allowed.");
        }

        if (file.getSize() > maxAvatarSize) {
            throw new IllegalArgumentException("File size exceeds " + (maxAvatarSize / 1024 / 1024) + "MB limit");
        }

        String filename = fileStorageService.store(file);
        String url = "/api/upload/avatars/" + filename;
        log.info("[DEBUG] Avatar uploaded successfully: {}", url);
        return ResponseEntity.ok(Map.of("url", url));
    }

    /**
     * 提供已上传头像文件的对外读取。
     *
     * @param filename 要返回的文件名
     * @return 文件资源
     */
    @GetMapping("/avatars/{filename}")
    public ResponseEntity<Resource> serveAvatar(@PathVariable String filename) {
        log.debug("[DEBUG] serveAvatar called for filename: {}", filename);

        Resource resource = fileStorageService.loadAsResource(filename);

        // 根据文件扩展名推断 content type
        String contentType = "application/octet-stream";
        if (filename.toLowerCase().endsWith(".png")) {
            contentType = "image/png";
        } else if (filename.toLowerCase().endsWith(".gif")) {
            contentType = "image/gif";
        } else if (filename.toLowerCase().endsWith(".webp")) {
            contentType = "image/webp";
        } else {
            contentType = "image/jpeg";
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .contentType(MediaType.parseMediaType(contentType))
                .body(resource);
    }
}
