package com.ideaparty.controller;

import com.ideaparty.dto.CharacterRequest;
import com.ideaparty.dto.CharacterResponse;
import com.ideaparty.dto.GeneratePromptRequest;
import com.ideaparty.dto.GeneratePromptResponse;
import com.ideaparty.service.CharacterService;
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
 * 角色管理 HTTP 入口。
 * 负责把 CharacterService 的能力暴露为 REST API，并从 Spring Security 的 Authentication 中提取 userId。
 * 设计上保持薄层：参数解析、权限归属判定、状态码映射在这里完成，业务编排下沉到 Service。
 */
@RestController
@RequestMapping("/api/characters")
public class CharacterController {
    private static final Logger log = LoggerFactory.getLogger(CharacterController.class);

    private final CharacterService characterService;

    public CharacterController(CharacterService characterService) {
        this.characterService = characterService;
    }

    @GetMapping
    public ResponseEntity<List<CharacterResponse>> getAllCharacters(Authentication auth) {
        // Returns all characters (presets + user's) for authenticated user
        List<CharacterResponse> characters = characterService.findAll();
        return ResponseEntity.ok(characters);
    }

    @GetMapping("/presets")
    public ResponseEntity<List<CharacterResponse>> getPresetCharacters() {
        List<CharacterResponse> presets = characterService.findPresets();
        return ResponseEntity.ok(presets);
    }

    @GetMapping("/recommended")
    public ResponseEntity<List<CharacterResponse>> getRecommendedCharacters() {
        List<CharacterResponse> recommended = characterService.findRecommended(10);
        return ResponseEntity.ok(recommended);
    }

    /**
     * 根据角色名称调用联网检索 + LLM 生成 system prompt。
     * 会触发外部副作用（Firecrawl / DeepSeek），由 Service 自行处理 fallback；此处仅做认证注入与结果封装。
     * 同步返回是因为前端需要在创建角色前预览 prompt；流式收益不明显。
     */
    @PostMapping("/generate-prompt")
    @ResponseBody
    public GeneratePromptResponse generatePrompt(
            Authentication auth,
            @RequestBody GeneratePromptRequest request) {
        UUID userId = UUID.fromString(auth.getName());
        String prompt = characterService.generatePrompt(userId, request.getName(), request.getDescription());
        return new GeneratePromptResponse(prompt);
    }

    @GetMapping("/{id}")
    public ResponseEntity<CharacterResponse> getCharacterById(@PathVariable UUID id) {
        return characterService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<CharacterResponse> createCharacter(
            Authentication auth,
            @Valid @RequestBody CharacterRequest request) {
        UUID userId = UUID.fromString(auth.getName());
        CharacterResponse created = characterService.create(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * 更新角色。Service 内部同时校验存在性与归属：不存在 / 非本人 均折叠为 403，
     * 避免向未授权用户泄露"角色是否存在"这一侧信道信息。
     */
    @PutMapping("/{id}")
    public ResponseEntity<CharacterResponse> updateCharacter(
            Authentication auth,
            @PathVariable UUID id,
            @Valid @RequestBody CharacterRequest request) {
        UUID userId = UUID.fromString(auth.getName());
        return characterService.update(id, userId, request)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(HttpStatus.FORBIDDEN).build());
    }

    /**
     * 删除角色。Service 通过 deleteIfOwner 保证预设角色与他人角色不会被误删，
     * 失败统一回 403；预设角色实际由另一条管理路径处理，不走此处。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCharacter(
            Authentication auth,
            @PathVariable UUID id) {
        UUID userId = UUID.fromString(auth.getName());
        boolean deleted = characterService.deleteIfOwner(id, userId);
        if (!deleted) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.noContent().build();
    }
}
