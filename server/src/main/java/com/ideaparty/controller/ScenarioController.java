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
 * 当前支持：
 *   - POST /api/scenarios/interview/generate-prompt
 *   - POST /api/scenarios/interview/parse-resume
 *   - POST /api/scenarios/interview/extract-text-from-image
 */
@RestController
@RequestMapping("/api/scenarios")
public class ScenarioController {

    private static final Logger log = LoggerFactory.getLogger(ScenarioController.class);

    private final ScenarioService scenarioService;
    private final ResumeParseService resumeParseService;
    private final ImageOcrService imageOcrService;

    public ScenarioController(
            ScenarioService scenarioService,
            ResumeParseService resumeParseService,
            ImageOcrService imageOcrService) {
        this.scenarioService = scenarioService;
        this.resumeParseService = resumeParseService;
        this.imageOcrService = imageOcrService;
    }

    /**
     * 根据用户填写的岗位/JD/简历 动态生成面试官 prompt
     */
    @PostMapping("/interview/generate-prompt")
    public ResponseEntity<InterviewScenarioResponse> generateInterviewPrompt(
            Authentication auth,
            @RequestBody InterviewScenarioRequest request) {
        UUID userId = UUID.fromString(auth.getName());
        log.info("[DEBUG] generateInterviewPrompt userId={}, position={}, hasResume={}",
                userId, request.getPosition(), request.getResumeContent() != null);
        InterviewScenarioResponse response = scenarioService.generateInterviewPrompt(userId, request);
        return ResponseEntity.ok(response);
    }

    /**
     * 解析上传的简历文件（docx/pdf/txt），返回纯文本
     */
    @PostMapping("/interview/parse-resume")
    public ResponseEntity<ParseResumeResponse> parseResume(
            Authentication auth,
            @RequestParam("file") MultipartFile file) {
        UUID userId = UUID.fromString(auth.getName());
        log.info("[DEBUG] parseResume userId={}, filename={}, size={}",
                userId, file.getOriginalFilename(), file.getSize());
        ParseResumeResponse response = resumeParseService.parse(file);
        return ResponseEntity.ok(response);
    }

    /**
     * 识别 JD 截图（png/jpg/webp/gif），返回提取的纯文本
     */
    @PostMapping("/interview/extract-text-from-image")
    public ResponseEntity<ExtractTextFromImageResponse> extractTextFromImage(
            Authentication auth,
            @RequestParam("file") MultipartFile file) {
        UUID userId = UUID.fromString(auth.getName());
        log.info("[DEBUG] extractTextFromImage userId={}, filename={}, size={}",
                userId, file.getOriginalFilename(), file.getSize());
        ExtractTextFromImageResponse response = imageOcrService.extractText(userId, file);
        return ResponseEntity.ok(response);
    }
}
