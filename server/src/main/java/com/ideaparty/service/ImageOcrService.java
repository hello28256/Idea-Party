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
 *
 * <p>职责：接收 Controller 转发的截图文件 → 校验 MIME 与体积 → 临时落盘 → 调 tesseract CLI → 截断超长文本 → 返回。
 * 不直接依赖 Spring Web，由 Controller 注入；调用方为 JobDescriptionController（JD 截图上传入口）。
 */
@Service
@Slf4j
public class ImageOcrService {

    /** 上传图片体积上限：防止恶意大文件耗尽磁盘与 CPU。5MB 覆盖常见 JD 截图（手机截图通常 1-3MB）。 */
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024L;
    /** 识别结果截断阈值：超过此长度按业务需求截断，避免一次性塞入大段文本给下游 LLM 触发 token 超限。 */
    private static final int MAX_TEXT_LENGTH = 6000;
    /** tesseract 子进程最长等待时间：JD 截图通常数秒内完成，30s 留足余量；超时强制 kill 防止线程堆积。 */
    private static final long OCR_TIMEOUT_SECONDS = 30L;

    /** 允许识别的图片 MIME 白名单：与 suffixForMime 必须保持一致，避免上传可执行伪装的图片绕过校验。 */
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "image/png", "image/jpeg", "image/jpg", "image/webp", "image/gif"
    );

    /** Tika 实例：用于嗅探真实 MIME 类型（仅信任客户端 Content-Type 会被绕过），无状态所以单例复用即可。 */
    private final Tika tika = new Tika();

    /**
     * 解析上传的 JD 截图，提取其中的文字
     * <p>
     * 校验文件非空与体积上限 → 用 Tika 嗅探真实 MIME（不信任客户端声明）→ 写临时文件交给 tesseract →
     * 读取输出、按 MAX_TEXT_LENGTH 截断、清理临时文件。任意 IO 异常包装为 RuntimeException 抛给上层 Controller。
     *
     * @param userId 调用方用户 ID，仅用于日志关联（便于排查哪个用户的截图识别失败），不参与业务逻辑
     * @param file Spring MultipartFile 上传的图片，必须为 PNG/JPG/JPEG/WEBP/GIF，非空且 ≤ 5MB
     * @return 提取出的纯文本（含是否被截断标志、原文件名、最终字符数）
     * @throws IllegalArgumentException 文件为空、超限或 MIME 不在白名单
     * @throws RuntimeException         tesseract 调用失败或 IO 异常
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
     * <p>
     * 同步等待子进程（最长 OCR_TIMEOUT_SECONDS），合并 stderr/stdout 用于失败诊断；成功后读取 .txt 并立即清理。
     *
     * @param input     tesseract 输入图片路径（绝对路径）
     * @param outputBase 输出文件基路径（不含 .txt 后缀，tesseract 会自动追加）
     * @return tesseract 识别出的原始文本
     * @throws IOException 读取输出文件失败
     * @throws RuntimeException 进程被中断、超时、退出码非 0、或未生成输出文件
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

    /**
     * 用 Tika 嗅探真实 MIME：客户端可伪造 Content-Type，必须基于文件头/扩展名二次校验；
     * 嗅探失败时回退到客户端声明值，最后兜底为 application/octet-stream，便于上层拦截而非误通过。
     */
    private String detectMime(MultipartFile file) {
        try (InputStream is = file.getInputStream()) {
            return tika.detect(is, file.getOriginalFilename());
        } catch (IOException e) {
            log.warn("[DEBUG] MIME detection failed: {}", e.getMessage());
            return file.getContentType() != null ? file.getContentType() : "application/octet-stream";
        }
    }

    /**
     * 根据 MIME 推导临时文件后缀：让 tesseract 拿到合适扩展名以正确选择解码器；
     * jpeg/jpg 共享 .jpg（两者本就是同一编码），其他不在白名单的 MIME 已被上游拦截，这里默认 .jpg 只是兜底。
     */
    private String suffixForMime(String mime) {
        return switch (mime) {
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            default -> ".jpg"; // jpeg / jpg 都用 .jpg
        };
    }

    /**
     * 静默删除临时文件：用于 finally 兜底清理，避免临时目录被上传文件堆满；
     * 故意吞掉异常——清理失败不应掩盖主流程已抛出的业务异常。
     */
    private void deleteQuietly(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // ignore
        }
    }
}
