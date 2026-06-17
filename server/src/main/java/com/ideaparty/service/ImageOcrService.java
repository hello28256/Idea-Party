package com.ideaparty.service;

import com.ideaparty.dto.ExtractTextFromImageResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 图片 OCR 服务：调用系统 tesseract 命令识别图片中的文字。
 * 依赖：系统已安装 tesseract + chi_sim 语言包（macOS: brew install tesseract tesseract-lang）
 */
@Service
@Slf4j
public class ImageOcrService {

    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024L;
    private static final int MAX_TEXT_LENGTH = 6000;
    private static final long OCR_TIMEOUT_SECONDS = 30L;

    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "image/png", "image/jpeg", "image/jpg", "image/webp", "image/gif"
    );

    private final Tika tika = new Tika();

    /**
     * 解析上传的 JD 截图，提取其中的文字
     */
    public ExtractTextFromImageResponse extractText(UUID userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("文件大小超过 5MB 上限");
        }

        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "image";
        String detectedMime = detectMime(file);
        if (!ALLOWED_MIME_TYPES.contains(detectedMime)) {
            throw new IllegalArgumentException(
                "不支持的图片格式: " + detectedMime + "。仅支持 PNG / JPG / WEBP / GIF"
            );
        }

        // 把上传文件写到临时目录，让 tesseract 直接读
        Path tempInput = null;
        Path tempOutput = null;
        try {
            String suffix = suffixForMime(detectedMime);
            tempInput = Files.createTempFile("ideaparty-jd-", suffix);
            file.transferTo(tempInput.toFile());
            tempOutput = Files.createTempFile("ideaparty-jd-out-", "");

            log.info("[DEBUG] OCR start: userId={}, filename={}, size={}, mime={}, tesseract input={}",
                    userId, filename, file.getSize(), detectedMime, tempInput);

            String text = runTesseract(tempInput, tempOutput);

            boolean truncated = false;
            if (text.length() > MAX_TEXT_LENGTH) {
                text = text.substring(0, MAX_TEXT_LENGTH);
                truncated = true;
            }

            log.info("[DEBUG] OCR done: filename={}, length={}, truncated={}", filename, text.length(), truncated);
            return new ExtractTextFromImageResponse(text.trim(), text.length(), filename, truncated);
        } catch (IOException e) {
            log.error("[DEBUG] OCR temp file failed: {}", e.getMessage());
            throw new RuntimeException("处理上传文件失败: " + e.getMessage(), e);
        } finally {
            deleteQuietly(tempInput);
            deleteQuietly(tempOutput);
        }
    }

    /**
     * 调用 tesseract CLI：tesseract <input> <output> -l chi_sim+eng
     * tesseract 会把识别结果写到 <output>.txt
     */
    private String runTesseract(Path input, Path outputBase) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                "tesseract",
                input.toAbsolutePath().toString(),
                outputBase.toAbsolutePath().toString(),
                "-l", "chi_sim+eng",   // 简体中文 + 英文（混合 JD 通常中英都有）
                "--psm", "6"            // 6 = 假设是统一的文本块（适合 JD 截图）
        );
        pb.redirectErrorStream(true);

        Process process = pb.start();
        StringBuilder stderr = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                stderr.append(line).append('\n');
            }
        } catch (IOException ignored) {
            // ignore
        }

        boolean finished;
        try {
            finished = process.waitFor(OCR_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new RuntimeException("OCR 识别超时（被中断）");
        }
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("OCR 识别超时（" + OCR_TIMEOUT_SECONDS + "s）");
        }

        int exitCode = process.exitValue();
        Path outputTxt = Path.of(outputBase.toString() + ".txt");
        if (exitCode != 0) {
            log.error("[DEBUG] tesseract exit code={}, stderr={}", exitCode, stderr);
            throw new RuntimeException("OCR 识别失败：tesseract 退出码 " + exitCode);
        }
        if (!Files.exists(outputTxt)) {
            throw new RuntimeException("OCR 识别失败：未生成输出文件");
        }

        String text = Files.readString(outputTxt, StandardCharsets.UTF_8);
        // 清理 tesseract 产生的临时 .txt
        deleteQuietly(outputTxt);
        return text;
    }

    private String detectMime(MultipartFile file) {
        try (InputStream is = file.getInputStream()) {
            return tika.detect(is, file.getOriginalFilename());
        } catch (IOException e) {
            log.warn("[DEBUG] MIME detection failed: {}", e.getMessage());
            return file.getContentType() != null ? file.getContentType() : "application/octet-stream";
        }
    }

    private String suffixForMime(String mime) {
        return switch (mime) {
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            default -> ".jpg"; // jpeg / jpg 都用 .jpg
        };
    }

    private void deleteQuietly(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // ignore
        }
    }
}
