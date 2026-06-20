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

    /** 5MB 上限：避免恶意大文件拖垮 Tika 解析或撑爆 JVM 堆 */
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024L;

    /** 文本截断上限：送进 LLM prompt 的简历内容不能太长，8000 字是经验值（兼顾信息量与 token 成本） */
    private static final int MAX_TEXT_LENGTH = 8000;

    /** 允许的 MIME 类型：白名单来自 Tika 真实检测结果，而非浏览器声明的 Content-Type，防止伪造 */
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/msword",
            "text/plain"
    );

    /** Tika 实例：单例复用其内部解析器缓存，避免每次请求都重建解析器开销 */
    private final Tika tika = new Tika();

    /**
     * 解析上传的简历文件
     * @param file 浏览器上传的 multipart 文件
     * @return 解析结果（纯文本 + 元信息）
     * @throws IllegalArgumentException 文件类型不支持 / 大小超限 / 文件为空
     * @throws RuntimeException 解析失败
     */
    public ParseResumeResponse parse(MultipartFile file) {
        // 先做空值/大小校验：比 Tika 解析便宜，可以在更早阶段拒绝无效请求，减少无谓开销
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件为空");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("文件大小超过 5MB 上限");
        }

        // 文件名兜底：部分浏览器（尤其移动端）上传时可能不携带原始文件名，避免下游日志/响应 NPE
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "resume";
        // 用 Tika 真实探测的 MIME 做白名单校验，杜绝把 .exe 改名成 .pdf 绕过
        String detectedMime = detectMime(file);

        if (!ALLOWED_MIME_TYPES.contains(detectedMime)) {
            throw new IllegalArgumentException(
                "不支持的文件类型: " + detectedMime + "。仅支持 PDF、Word(docx/doc)、纯文本"
            );
        }

        log.info("[DEBUG] Parsing resume: filename={}, size={}, mime={}", filename, file.getSize(), detectedMime);

        String text;
        // try-with-resources 保证流被关闭：Spring 的 MultipartFile 底层走磁盘临时文件，泄漏会占满 /tmp
        try (InputStream is = file.getInputStream()) {
            text = tika.parseToString(is);
        } catch (IOException | TikaException e) {
            // Tika 解析失败通常意味着文件损坏/加密/格式异常，对外暴露原始 message 便于前端提示用户
            log.error("[DEBUG] Tika parse failed for {}: {}", filename, e.getMessage());
            throw new RuntimeException("简历解析失败: " + e.getMessage(), e);
        }

        // 清洗：去首尾空白、统一换行、压缩连续空行
        text = cleanText(text);

        boolean truncated = false;
        // 截断要在清洗之后做，否则清洗引入/移除的换行会影响截断点位置
        if (text.length() > MAX_TEXT_LENGTH) {
            text = text.substring(0, MAX_TEXT_LENGTH);
            truncated = true;
            // 记录被截断的简历：业务方后续可观察是否需要调整 MAX_TEXT_LENGTH
            log.info("[DEBUG] Resume text truncated from longer than {} chars", MAX_TEXT_LENGTH);
        }

        log.info("[DEBUG] Resume parsed: filename={}, length={}, truncated={}",
                filename, text.length(), truncated);
        return new ParseResumeResponse(text, text.length(), filename, truncated);
    }

    /**
     * 优先用 Tika 探测真实 MIME，绕开浏览器伪造的 Content-Type。
     * 调用方：parse() 在白名单校验前调用，必须返回非 null（兜底走 application/octet-stream）。
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
     * 调用方：parse() 在截断前调用一次，保证后续 prompt 看到的文本规整可控。
     */
    private String cleanText(String text) {
        if (text == null) return "";
        return text.trim()
                .replaceAll("\\r\\n?", "\n")
                .replaceAll("\\n{3,}", "\n\n");
    }
}
