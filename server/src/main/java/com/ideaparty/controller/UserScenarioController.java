package com.ideaparty.controller;

import com.ideaparty.dto.GeneratePromptRequest;
import com.ideaparty.dto.GeneratePromptResponse;
import com.ideaparty.dto.UserScenarioRequest;
import com.ideaparty.dto.UserScenarioResponse;
import com.ideaparty.service.CharacterService;
import com.ideaparty.service.UserScenarioService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * 用户私有场景管理 HTTP 入口。
 *
 * 路由前缀 /api/scenarios/user，避免与已有的 /api/scenarios/interview/* 冲突
 * （后者服务于"面试场景动态生成"功能，由 ScenarioController 负责）。
 *
 * 设计原则（与 CharacterController 对齐）：
 * - 业务编排下沉到 Service；Controller 只做认证注入、参数解析、状态码映射
 * - 不存在的资源 / 非 owner 的资源 一律 403，避免向未授权用户泄露侧信道
 * - @Valid 触发 UserScenarioRequest 的 Bean Validation，失败由全局异常处理器转 400
 */
@RestController
@RequestMapping("/api/scenarios/user")
public class UserScenarioController {

    private static final Logger log = LoggerFactory.getLogger(UserScenarioController.class);

    private final UserScenarioService userScenarioService;
    // 用于 generate-prompt 端点：复用 CharacterService.generatePrompt 的联网检索 + LLM 合成能力
    private final CharacterService characterService;

    public UserScenarioController(UserScenarioService userScenarioService, CharacterService characterService) {
        this.userScenarioService = userScenarioService;
        this.characterService = characterService;
    }

    /**
     * 列出当前用户全部私有场景，按更新时间倒序。
     */
    @GetMapping
    public ResponseEntity<List<UserScenarioResponse>> listUserScenarios(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        return ResponseEntity.ok(userScenarioService.listByOwner(userId));
    }

    /**
     * 创建用户私有场景。返回 201 + 完整实体表示，便于前端直接渲染。
     */
    @PostMapping
    public ResponseEntity<UserScenarioResponse> createUserScenario(
            Authentication auth, @Valid @RequestBody UserScenarioRequest request) {
        UUID userId = UUID.fromString(auth.getName());
        UserScenarioResponse created = userScenarioService.create(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * 更新用户私有场景。Service 内部校验存在性与归属：不存在 / 非本人 均折叠为 403。
     */
    @PutMapping("/{id}")
    public ResponseEntity<UserScenarioResponse> updateUserScenario(
            Authentication auth,
            @PathVariable UUID id,
            @Valid @RequestBody UserScenarioRequest request) {
        UUID userId = UUID.fromString(auth.getName());
        return userScenarioService.update(id, userId, request)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(HttpStatus.FORBIDDEN).build());
    }

    /**
     * 删除用户私有场景。Service 通过 existsByIdAndOwnerId 保证预设场景与他人场景不会被误删，
     * 失败统一回 403。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUserScenario(Authentication auth, @PathVariable UUID id) {
        UUID userId = UUID.fromString(auth.getName());
        boolean deleted = userScenarioService.deleteIfOwner(id, userId);
        if (!deleted) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * 根据 characterName + description 调用 AI 生成场景 prompt 模板。
     *
     * 复用 CharacterService.generatePrompt 的能力（Firecrawl 联网检索 + DeepSeek LLM 合成）。
     * 用户填好「标题/描述/角色名」后点「AI 自动生成」即可获得一份可直接用的 system prompt，
     * 再按需微调后保存——避免从零写的门槛。
     *
     * 失败时由 CharacterService 内部兜底（"你是{name}..."），调用方不会收到 500。
     */
    @PostMapping("/generate-prompt")
    @ResponseBody
    public GeneratePromptResponse generateScenarioPrompt(Authentication auth, @RequestBody GeneratePromptRequest request) {
        UUID userId = UUID.fromString(auth.getName());
        String prompt = characterService.generatePrompt(userId, request.getName(), request.getDescription());
        return new GeneratePromptResponse(prompt);
    }
}
