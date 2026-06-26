package com.ideaparty.controller;

import com.ideaparty.dto.CharacterRequest;
import com.ideaparty.dto.CharacterResponse;
import com.ideaparty.dto.GeneratePromptRequest;
import com.ideaparty.dto.GeneratePromptResponse;
import com.ideaparty.service.CharacterService;
import com.ideaparty.service.FirecrawlService;
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

    // 业务编排委托给 Service；Controller 只做参数解析、认证注入、状态码映射，保持薄层便于单元测试
    private final CharacterService characterService;
    // 头像搜索：被 /avatar-search 直接调用，跳过 Service 层避免污染 CharacterService 业务面。
    private final FirecrawlService firecrawlService;

    /**
     * Spring 容器注入业务服务。
     * 走构造器注入而非字段注入，便于在测试中手动传入 mock 实现，并保证字段不可变。
     */
    public CharacterController(CharacterService characterService, FirecrawlService firecrawlService) {
        this.characterService = characterService;
        this.firecrawlService = firecrawlService;
    }

    /**
     * 列出当前用户可见的全部角色（预设 + 本人创建）。
     * Authentication 由 Spring Security 注入：访问此接口本身已被 SecurityFilterChain 保护，
     * 此处暂未基于 auth 做差异化过滤，等价于"登录后即可看到全部预设 + 自己的角色"。
     */
    @GetMapping
    public ResponseEntity<List<CharacterResponse>> getAllCharacters(Authentication auth) {
        // 为已认证用户返回全部角色（预设 + 本人的）
        List<CharacterResponse> characters = characterService.findAll();
        return ResponseEntity.ok(characters);
    }

    /**
     * 列出系统预设角色（与具体用户无关，因此不需要 Authentication）。
     * 用于"加入聊天室前先挑一个预设人物"的场景，与 /recommended 互为补充。
     */
    @GetMapping("/presets")
    public ResponseEntity<List<CharacterResponse>> getPresetCharacters() {
        List<CharacterResponse> presets = characterService.findPresets();
        return ResponseEntity.ok(presets);
    }

    /**
     * 列出全部推荐角色（首页推荐位）。
     * 一次性返回所有 preset（约 36 人），由前端按 18 一批切片做"换一批"切换。
     * 这里不再写死 limit：旧的 findRecommended(18) 保留在 Service 层供向后兼容；
     * 此端点是"发现页推荐位"的事实入口，需要的就是全集。
     */
    @GetMapping("/recommended")
    public ResponseEntity<List<CharacterResponse>> getRecommendedCharacters() {
        List<CharacterResponse> recommended = characterService.findAllRecommended();
        return ResponseEntity.ok(recommended);
    }

    /**
     * 根据角色名称调用联网检索 + LLM 生成 system prompt。
     * 会触发外部副作用（Firecrawl / DeepSeek），由 Service 自行处理 fallback；此处仅做认证注入与结果封装。
     * 同步返回是因为前端需要在创建角色前预览 prompt；流式收益不明显。
     */
    @PostMapping("/generate-prompt")
    @ResponseBody
    public GeneratePromptResponse generatePrompt(Authentication auth, @RequestBody GeneratePromptRequest request) {
        UUID userId = UUID.fromString(auth.getName());
        String prompt = characterService.generatePrompt(userId, request.getName(), request.getDescription());
        return new GeneratePromptResponse(prompt);
    }

    /**
     * 头像搜索：根据角色名返回维基百科候选头像（缩略图 URL）。
     *
     * <p>流程：
     *   1) FirecrawlService.searchCharacterAvatarCandidates(name) 拉多个 wikipedia 页面 URL
     *   2) 对每个候选调 fetchWikipediaThumbnail(...) 拿 REST summary API 的 thumbnail 直链
     *   3) 返回 List<{thumbnailUrl, title, wikiUrl}> 给前端弹选择器
     *
     * <p>为什么搜索和取缩略图分两步：Firecrawl search 响应里 metadata 不一定有图片字段，
     * 而维基官方 REST summary API 专门返回 thumbnail / originalimage，命中率最高。
     *
     * <p>空数组即"未找到"：前端用 toast 提示并显示 DiceBear fallback。
     */
    @GetMapping("/avatar-search")
    @ResponseBody
    public java.util.List<java.util.Map<String, String>> avatarSearch(@RequestParam("name") String name) {
        java.util.List<FirecrawlService.AvatarCandidate> candidates =
                firecrawlService.searchCharacterAvatarCandidates(name, 3);
        java.util.List<java.util.Map<String, String>> result = new java.util.ArrayList<>();
        for (FirecrawlService.AvatarCandidate c : candidates) {
            // 用 title 而不是 wikiUrl，因为 list=search 返回的 wikiUrl 是 ?curid= 形式，
            // summary API 需要 title 才能正确路由（curid 不被 summary 端点接受）。
            String thumb = firecrawlService.fetchWikipediaThumbnailByTitle(c.getTitle());
            if (thumb == null || thumb.isBlank()) continue;
            java.util.Map<String, String> entry = new java.util.LinkedHashMap<>();
            entry.put("thumbnailUrl", thumb);
            entry.put("title", c.getTitle());
            entry.put("wikiUrl", c.getWikiUrl());
            result.add(entry);
        }
        log.info("[DEBUG] /avatar-search '{}' returned {} candidates", name, result.size());
        return result;
    }

    /**
     * 按 ID 查询单个角色。Service 返回 Optional，此处把"不存在"显式映射为 404。
     * 未做归属校验：预设角色允许匿名读，自己创建的角色只有本人能改/删（见 update/delete）。
     */
    @GetMapping("/{id}")
    public ResponseEntity<CharacterResponse> getCharacterById(@PathVariable UUID id) {
        return characterService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 创建角色。@Valid 触发 CharacterRequest 上的 Bean Validation 注解（name 非空、长度等），
     * 失败由全局异常处理器转 400。成功返回 201 + 新建实体的完整表示，便于前端直接渲染。
     */
    @PostMapping
    public ResponseEntity<CharacterResponse> createCharacter(Authentication auth, @Valid @RequestBody CharacterRequest request) {
        UUID userId = UUID.fromString(auth.getName());
        CharacterResponse created = characterService.create(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * 更新角色。Service 内部同时校验存在性与归属：不存在 / 非本人 均折叠为 403，
     * 避免向未授权用户泄露"角色是否存在"这一侧信道信息。
     */
    @PutMapping("/{id}")
    public ResponseEntity<CharacterResponse> updateCharacter(Authentication auth, @PathVariable UUID id, @Valid @RequestBody CharacterRequest request) {
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
    public ResponseEntity<Void> deleteCharacter(Authentication auth, @PathVariable UUID id) {
        UUID userId = UUID.fromString(auth.getName());
        boolean deleted = characterService.deleteIfOwner(id, userId);
        if (!deleted) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.noContent().build();
    }
}
