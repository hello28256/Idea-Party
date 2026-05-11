package com.ideaparty.controller;

import com.ideaparty.service.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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

    public FileUploadController(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    /**
     * Upload an avatar image.
     * Accepts multipart/form-data with field name "avatar".
     *
     * @param file The avatar file to upload
     * @return URL of the uploaded file
     */
    @PostMapping("/avatar")
    public ResponseEntity<?> uploadAvatar(@RequestParam("avatar") MultipartFile file) {
        log.debug("[DEBUG] uploadAvatar called with file: {}", file != null ? file.getOriginalFilename() : "null");

        // Validate file is not null
        if (file == null || file.isEmpty()) {
            log.warn("[DEBUG] Upload failed: file is empty or null");
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "File is required"));
        }

        // Validate content type
        String contentType = file.getContentType();
        if (!fileStorageService.isAllowedContentType(contentType)) {
            log.warn("[DEBUG] Upload failed: invalid content type {}", contentType);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid file type. Only JPEG, PNG, GIF, and WebP are allowed."));
        }

        // Validate file size (5MB)
        if (file.getSize() > 5 * 1024 * 1024) {
            log.warn("[DEBUG] Upload failed: file size {} exceeds 5MB limit", file.getSize());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "File size exceeds 5MB limit"));
        }

        try {
            String filename = fileStorageService.store(file);
            String url = "/api/upload/avatars/" + filename;
            log.info("[DEBUG] Avatar uploaded successfully: {}", url);
            return ResponseEntity.ok(Map.of("url", url));
        } catch (IllegalArgumentException e) {
            log.error("[DEBUG] Upload failed: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[DEBUG] Upload failed unexpectedly: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to upload file"));
        }
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

        try {
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
        } catch (Exception e) {
            log.error("[DEBUG] Failed to serve avatar {}: {}", filename, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }
}
