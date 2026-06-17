package com.ideaparty.service;

import com.ideaparty.dto.InterviewScenarioRequest;
import com.ideaparty.dto.InterviewScenarioResponse;
import com.ideaparty.entity.User;
import com.ideaparty.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 场景动态 prompt 生成服务。
 * 当前只实现"面试模拟"场景，结构设计成可扩展（其他场景只需新增一个 generateXxx 方法）。
 */
@Service
@Slf4j
public class ScenarioService {

    private final UserRepository userRepository;
    private final String deepseekBaseUrl;

    public ScenarioService(
            UserRepository userRepository,
            @Value("${langchain4j.open-ai.base-url}") String deepseekBaseUrl) {
        this.userRepository = userRepository;
        this.deepseekBaseUrl = deepseekBaseUrl;
    }

    /**
     * 根据用户填写的岗位/JD 描述，动态生成专属面试官 prompt。
     *
     * @param userId 当前用户（用于取 API key）
     * @param request 用户填写的岗位信息
     * @return 解析后的（角色名 + prompt）
     */
    public InterviewScenarioResponse generateInterviewPrompt(UUID userId, InterviewScenarioRequest request) {
        if (request.getPosition() == null || request.getPosition().isBlank()) {
            throw new IllegalArgumentException("岗位信息不能为空");
        }

        String userApiKey = resolveUserApiKey(userId);
        String rawOutput = callDeepSeekForInterview(request, userApiKey);

        // 解析 LLM 输出：第一行 "角色名：xxx"，后面是 prompt
        return parseInterviewOutput(rawOutput, request.getPosition());
    }

    // ---------- 私有方法 ----------

    private String resolveUserApiKey(UUID userId) {
        try {
            User owner = userRepository.findById(userId).orElse(null);
            String key = owner != null ? owner.getApiKey() : null;
            if (key != null && !key.isBlank() && !key.equals("sk-dummy-key-for-testing")) {
                return key;
            }
        } catch (Exception e) {
            log.warn("[DEBUG] Failed to resolve user API key: {}", e.getMessage());
        }
        return null;
    }

    private String callDeepSeekForInterview(InterviewScenarioRequest req, String apiKey) {
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null) {
            headers.set("Authorization", "Bearer " + apiKey);
        }

        // 加载 prompt 模板，把 {{xxx}} 占位符替换为用户输入
        String template = loadPromptTemplate("prompts/interview-prompt-generator.txt");
        String resumeSection = buildResumeSection(req.getResumeContent());
        String userMessage = template
                .replace("{{position}}", nullToEmpty(req.getPosition()))
                .replace("{{industry}}", nullToEmpty(req.getIndustry()))
                .replace("{{experienceYears}}", req.getExperienceYears() != null ? String.valueOf(req.getExperienceYears()) : "未提供")
                .replace("{{jobDescription}}", nullToEmpty(req.getJobDescription()))
                .replace("{{resumeSection}}", resumeSection);

        Map<String, Object> body = new HashMap<>();
        body.put("model", "deepseek-chat");
        body.put("messages", List.of(
                Map.of("role", "user", "content", userMessage)
        ));
        body.put("temperature", 0.7);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        log.info("[DEBUG] Calling DeepSeek for interview prompt, position: {}", req.getPosition());

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    deepseekBaseUrl + "/chat/completions",
                    HttpMethod.POST,
                    request,
                    Map.class
            );
            if (response.getBody() != null && response.getBody().containsKey("choices")) {
                List<Map<String, Object>> choices = (List<Map<String, Object>>) response.getBody().get("choices");
                if (!choices.isEmpty()) {
                    Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                    String content = (String) message.get("content");
                    return content.trim();
                }
            }
        } catch (Exception e) {
            log.error("[DEBUG] AI interview prompt generation failed: {}", e.getMessage());
        }

        // Fallback：AI 失败时返回一份简单但可用的 prompt
        return buildFallbackPrompt(req);
    }

    private String buildFallbackPrompt(InterviewScenarioRequest req) {
        String position = nullToEmpty(req.getPosition());
        String industry = nullToEmpty(req.getIndustry());
        String exp = req.getExperienceYears() != null ? req.getExperienceYears() + " 年" : "";

        String characterName = (industry.isEmpty() ? "" : industry + " · ") + position + "面试官";
        String prompt = String.format("""
                你是一位资深的 %s%s%s 面试官。请基于候选人提供的岗位描述，模拟一场真实的面试。

                【面试流程】
                1. 开场：先做自我介绍，然后请候选人自我介绍（控制在 2 分钟内）
                2. 行为面：询问 1-2 个过去项目经历（STAR 法则）
                3. 技术面：针对候选人简历中的技术栈出 3-5 个递进式问题
                4. 反问：留 1-2 个让候选人反问的环节
                5. 总结：给出明确的"通过/待定/不通过"判断 + 详细反馈

                【风格要求】
                - 像真正的面试官一样严格，不要客套
                - 每次只问一个问题，等候选人回答完再继续
                - 涉及技术细节时，验证候选人是否真的理解原理
                """, industry.isEmpty() ? "" : industry + " 行业 ", position, exp.isEmpty() ? "" : "（" + exp + "）");
        return "角色名：" + characterName + "\n\n" + prompt;
    }

    /**
     * 解析 LLM 输出：第一行 "角色名：xxx"，其余为 prompt。
     * 如果解析失败（LLM 没按格式输出），用 position 拼一个兜底角色名。
     */
    private InterviewScenarioResponse parseInterviewOutput(String raw, String fallbackPosition) {
        if (raw == null || raw.isBlank()) {
            String name = fallbackPosition + " 面试官";
            return new InterviewScenarioResponse(name, "你是一位资深的 " + fallbackPosition + " 面试官。请开始面试。");
        }

        // 找第一个换行符
        int newlineIdx = raw.indexOf('\n');
        if (newlineIdx > 0) {
            String firstLine = raw.substring(0, newlineIdx).trim();
            String rest = raw.substring(newlineIdx).trim();
            // 匹配 "角色名：xxx" 或 "角色名:xxx"
            if (firstLine.startsWith("角色名") && (firstLine.contains("：") || firstLine.contains(":"))) {
                int sepIdx = Math.max(firstLine.indexOf('：'), firstLine.indexOf(':'));
                String name = firstLine.substring(sepIdx + 1).trim();
                if (!name.isEmpty()) {
                    return new InterviewScenarioResponse(name, rest);
                }
            }
        }

        // 解析失败：把整段当 prompt，名字用 position 兜底
        return new InterviewScenarioResponse(fallbackPosition + " 面试官", raw);
    }

    private String loadPromptTemplate(String resourcePath) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                log.error("[DEBUG] Prompt template not found: {}", resourcePath);
                throw new RuntimeException("Prompt template not found: " + resourcePath);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("[DEBUG] Failed to load prompt template {}: {}", resourcePath, e.getMessage());
            throw new RuntimeException("Failed to load prompt template", e);
        }
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * 把简历内容拼成 prompt 里的一段。空内容时返回空串。
     */
    private String buildResumeSection(String resumeContent) {
        if (resumeContent == null || resumeContent.isBlank()) {
            return "";
        }
        return "\n\n# 候选人简历（已由系统解析为纯文本）\n" + resumeContent.trim();
    }
}
