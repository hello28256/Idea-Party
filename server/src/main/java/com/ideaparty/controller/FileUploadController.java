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

@RestController
@RequestMapping("/api/upload")
@Slf4j
public class FileUploadController {

    private final FileStorageService fileStorageService;
    private final long maxAvatarSize;

    public FileUploadController(
            FileStorageService fileStorageService,
            @Value("${upload.avatar.max-size:5242880}") long maxAvatarSize) {
        this.fileStorageService = fileStorageService;
        this.maxAvatarSize = maxAvatarSize;
    }

    /**
     * Upload an avatar image.
     * Accepts multipart/form-data with field name "avatar".
     *
     * @param file The avatar file to upload
     * @return URL of the uploaded file
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
     * Serve an uploaded avatar file.
     *
     * @param filename The filename to serve
     * @return The file as a resource
     */
    @GetMapping("/avatars/{filename}")
    public ResponseEntity<Resource> serveAvatar(@PathVariable String filename) {
        log.debug("[DEBUG] serveAvatar called for filename: {}", filename);

        Resource resource = fileStorageService.loadAsResource(filename);

        // Determine content type from filename extension
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
