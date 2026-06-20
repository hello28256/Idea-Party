package com.ideaparty.controller;

import com.ideaparty.dto.ExtractTextFromImageResponse;
import com.ideaparty.dto.InterviewScenarioRequest;
import com.ideaparty.dto.InterviewScenarioResponse;
import com.ideaparty.dto.ParseResumeResponse;
import com.ideaparty.service.ImageOcrService;
import com.ideaparty.service.ResumeParseService;
import com.ideaparty.service.ScenarioService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * 场景动态 prompt 生成接口。
 * 把"用户输入"（岗位 JD / 简历 / 图片）转成 LLM 可消费的上下文，
 * 由对应的 Service 完成真正的工作，Controller 仅做鉴权、日志与编排；
 * 这样后续替换底层实现（Firecrawl 联网抓取、OCR 切换）不会影响 HTTP 契约。
 * 当前支持：
 *   - POST /api/scenarios/interview/generate-prompt
 *   - POST /api/scenarios/interview/parse-resume
 *   - POST /api/scenarios/interview/extract-text-from-image
 */
@RestController
@RequestMapping("/api/scenarios")
public class ScenarioController {

    // SLF4J Logger：用 class 作 logger name，可在 logback 配置里按 Controller 类名单独调整级别/采样率。
    private static final Logger log = LoggerFactory.getLogger(ScenarioController.class);

    // 字段由构造器注入（无 @Autowired 字段注入），便于单测替换为 Mock。
    // 三种 Service 对应三种不同的输入形态：自由文本 -> ScenarioService；文件解析 -> ResumeParseService；图片 OCR -> ImageOcrService。
    private final ScenarioService scenarioService;
    // 用于 POST /interview/parse-resume，与下方 OCR Service 解耦是因为简历解析走文本抽取而非 OCR，计费/配额独立。
    private final ResumeParseService resumeParseService;
    // 用于 POST /interview/extract-text-from-image，独立成 Service 是因为 OCR 链路可能接入外部计费 API，需要单独扩缩容。
    private final ImageOcrService imageOcrService;

    /**
     * 构造器注入三个 Service：避免字段注入带来的循环依赖与隐式状态，所有依赖在对象创建期一次性确定。
     * 调用方：Spring IoC 容器在初始化 Controller Bean 时自动匹配。
     */
    public ScenarioController(ScenarioService scenarioService, ResumeParseService resumeParseService, ImageOcrService imageOcrService) {
        this.scenarioService = scenarioService;
        this.resumeParseService = resumeParseService;
        this.imageOcrService = imageOcrService;
    }

    /**
     * 根据用户填写的岗位/JD/简历 动态生成面试官 prompt
     * 契约：调用方必须已登录，request.position 必填；简历可选提供原文，Service 内部按是否提供决定是否走联网检索补全。
     * 副作用：会在 DB 中持久化场景快照（便于回溯与复用），不直接返回 chat room。
     */
    @PostMapping("/interview/generate-prompt")
    public ResponseEntity<InterviewScenarioResponse> generateInterviewPrompt(Authentication auth, @RequestBody InterviewScenarioRequest request) {
        // auth.getName() 在本项目 = userId 字符串（JwtAuthFilter 写入 UserDetails），强转为 UUID 供下游使用。
        UUID userId = UUID.fromString(auth.getName());
        // 日志用 [DEBUG] 前缀 + 是否提供简历，便于排查"用户说没生成 prompt"时区分输入形态差异。
        log.info("[DEBUG] generateInterviewPrompt userId={}, position={}, hasResume={}",
                userId, request.getPosition(), request.getResumeContent() != null);
        InterviewScenarioResponse response = scenarioService.generateInterviewPrompt(userId, request);
        return ResponseEntity.ok(response);
    }

    /**
     * 解析上传的简历文件（docx/pdf/txt），返回纯文本
     * 设计动机：上传后立即出文本，便于前端把简历内容回填到 generate-prompt 请求中，
     * 避免让前端自行解析文件导致格式不一致；userId 仅记日志，不参与解析逻辑（简历解析本身是无状态转换）。
     */
    @PostMapping("/interview/parse-resume")
    public ResponseEntity<ParseResumeResponse> parseResume(Authentication auth, @RequestParam("file") MultipartFile file) {
        UUID userId = UUID.fromString(auth.getName());
        // 记录文件名+大小便于排查"上传成功但解析空"类问题；file.size 反映请求体大小，可与 nginx/网关层限流对照。
        log.info("[DEBUG] parseResume userId={}, filename={}, size={}",
                userId, file.getOriginalFilename(), file.getSize());
        ParseResumeResponse response = resumeParseService.parse(file);
        return ResponseEntity.ok(response);
    }

    /**
     * 识别 JD 截图（png/jpg/webp/gif），返回提取的纯文本
     * 设计动机：用户常从招聘 App 截图 JD 而非粘贴文字，OCR 后再走 generate-prompt 形成完整链路；
     * 与 parse-resume 共用文件上传通道但走不同 Service，便于按 OCR 配额/计费独立扩展。
     */
    @PostMapping("/interview/extract-text-from-image")
    public ResponseEntity<ExtractTextFromImageResponse> extractTextFromImage(Authentication auth, @RequestParam("file") MultipartFile file) {
        UUID userId = UUID.fromString(auth.getName());
        log.info("[DEBUG] extractTextFromImage userId={}, filename={}, size={}",
                userId, file.getOriginalFilename(), file.getSize());
        ExtractTextFromImageResponse response = imageOcrService.extractText(userId, file);
        return ResponseEntity.ok(response);
    }
}
