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

    private static final List<String> ALLOWED_CONTENT_TYPES = Arrays.asList(
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp"
    );

    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5MB

    private final Path uploadDir;

    public FileStorageService() {
        // Store in server/uploads/avatars/
        this.uploadDir = Paths.get("uploads", "avatars").toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.uploadDir);
            log.info("[DEBUG] FileStorageService initialized. Upload directory: {}", this.uploadDir);
        } catch (IOException e) {
            throw new RuntimeException("Could not create upload directory", e);
        }
    }

    /**
     * Store a file and return the generated filename.
     * Uses UUID-based filename to prevent collisions and hide original names.
     *
     * @param file The multipart file to store
     * @return The stored filename (UUID-based)
     * @throws IllegalArgumentException if file is invalid
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

        // Generate UUID-based filename preserving extension
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
     * Load a file as a Resource for serving.
     *
     * @param filename The filename to load
     * @return Resource pointing to the file
     * @throws RuntimeException if file cannot be loaded
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
     * Delete a file from storage.
     *
     * @param filename The filename to delete
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
     * Check if a content type is allowed.
     *
     * @param contentType The content type to check
     * @return true if allowed
     */
    public boolean isAllowedContentType(String contentType) {
        return contentType != null && ALLOWED_CONTENT_TYPES.contains(contentType);
    }

    /**
     * Get the upload directory path.
     *
     * @return The upload directory path
     */
    public Path getUploadDir() {
        return this.uploadDir;
    }
}
