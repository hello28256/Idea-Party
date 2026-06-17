package com.ideaparty.service;

import com.ideaparty.dto.ParseResumeResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/**
 * 简历解析服务：把上传的 .docx / .pdf 文件转成纯文本。
 * 限制：5MB、白名单 MIME、按段落保留换行、最长 8000 字符（超过截断）。
 */
@Service
@Slf4j
public class ResumeParseService {

    /** 5MB 上限 */
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024L;

    /** 文本截断上限（送进 prompt 不能太长） */
    private static final int MAX_TEXT_LENGTH = 8000;

    /** 允许的 MIME 类型（Tika 实际检测出来的） */
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/msword",
            "text/plain"
    );

    private final Tika tika = new Tika();

    /**
     * 解析上传的简历文件
     * @param file 浏览器上传的 multipart 文件
     * @return 解析结果（纯文本 + 元信息）
     * @throws IllegalArgumentException 文件类型不支持 / 大小超限 / 文件为空
     * @throws RuntimeException 解析失败
     */
    public ParseResumeResponse parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件为空");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("文件大小超过 5MB 上限");
        }

        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "resume";
        String detectedMime = detectMime(file);

        if (!ALLOWED_MIME_TYPES.contains(detectedMime)) {
            throw new IllegalArgumentException(
                "不支持的文件类型: " + detectedMime + "。仅支持 PDF、Word(docx/doc)、纯文本"
            );
        }

        log.info("[DEBUG] Parsing resume: filename={}, size={}, mime={}", filename, file.getSize(), detectedMime);

        String text;
        try (InputStream is = file.getInputStream()) {
            text = tika.parseToString(is);
        } catch (IOException | TikaException e) {
            log.error("[DEBUG] Tika parse failed for {}: {}", filename, e.getMessage());
            throw new RuntimeException("简历解析失败: " + e.getMessage(), e);
        }

        // 清洗：去首尾空白、统一换行、压缩连续空行
        text = cleanText(text);

        boolean truncated = false;
        if (text.length() > MAX_TEXT_LENGTH) {
            text = text.substring(0, MAX_TEXT_LENGTH);
            truncated = true;
            log.info("[DEBUG] Resume text truncated from longer than {} chars", MAX_TEXT_LENGTH);
        }

        log.info("[DEBUG] Resume parsed: filename={}, length={}, truncated={}",
                filename, text.length(), truncated);
        return new ParseResumeResponse(text, text.length(), filename, truncated);
    }

    /**
     * 优先用 Tika 探测真实 MIME，绕开浏览器伪造的 Content-Type。
     */
    private String detectMime(MultipartFile file) {
        try (InputStream is = file.getInputStream()) {
            // Tika 探测只读前若干字节，文件流会自动关闭
            return tika.detect(is, file.getOriginalFilename());
        } catch (IOException e) {
            log.warn("[DEBUG] MIME detection failed, falling back to declared type: {}", e.getMessage());
            String declared = file.getContentType();
            return declared != null ? declared : "application/octet-stream";
        }
    }

    /**
     * 清洗解析出的文本：
     * 1. 去掉首尾空白
     * 2. 把 \r\n / \r 统一成 \n
     * 3. 压缩连续 3+ 换行为 2 个（保留段落间隔但去掉噪声）
     */
    private String cleanText(String text) {
        if (text == null) return "";
        return text.trim()
                .replaceAll("\\r\\n?", "\n")
                .replaceAll("\\n{3,}", "\n\n");
    }
}
