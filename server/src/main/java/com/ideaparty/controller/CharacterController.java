package com.ideaparty.controller;

import com.ideaparty.dto.CharacterReferencesResponse;
import com.ideaparty.dto.CharacterRequest;
import com.ideaparty.dto.CharacterResponse;
import com.ideaparty.dto.GeneratePromptRequest;
import com.ideaparty.dto.GeneratePromptResponse;
import com.ideaparty.service.CharacterService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
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

    /**
     * Spring 容器注入业务服务。
     * 走构造器注入而非字段注入，便于在测试中手动传入 mock 实现，并保证字段不可变。
     */
    public CharacterController(CharacterService characterService) {
        this.characterService = characterService;
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
        // preset 是静态系统数据（V10 之后走内存缓存），响应可由浏览器/网关缓存 5 分钟。
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(5, java.util.concurrent.TimeUnit.MINUTES).cachePublic())
                .body(presets);
    }

    /**
     * 列出全部推荐角色（首页推荐位）。
     * 一次性返回所有 preset（约 120 人），由前端按 12 一批切片做"换一批"切换。
     * 这里不再写死 limit：旧的 findRecommended(18) 保留在 Service 层供向后兼容；
     * 此端点是"发现页推荐位"的事实入口，需要的就是全集。
     *
     * 可选 ?category= 参数：传入枚举 name（SCIENTIST/STAR/ENTREPRENEUR/.../ARTIST）按分类过滤；
     * 非法值或缺失都按"全部"处理（前端分类标签条的"全部"chip 不带参数）。
     */
    @GetMapping("/recommended")
    public ResponseEntity<List<CharacterResponse>> getRecommendedCharacters(
            @RequestParam(value = "category", required = false) String category
    ) {
        com.ideaparty.entity.CharacterCategory catEnum =
                com.ideaparty.entity.CharacterCategory.fromName(category);
        List<CharacterResponse> recommended = characterService.findRecommendedByCategory(catEnum);
        // 同 /presets：preset 是静态数据，5 分钟浏览器缓存
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(5, java.util.concurrent.TimeUnit.MINUTES).cachePublic())
                .body(recommended);
    }

    /**
     * 根据角色名 + 用户描述，调用 DeepSeek 生成 system prompt。
     * 注：当前实现不走联网检索，仅依赖 LLM 自身知识
     * （见 CharacterService#generatePromptWithAIFromNameAndDescription）。
     * Service 层会校验调用方是否配置了 DeepSeek API Key；缺失时抛 IllegalArgumentException，
     * 由 GlobalExceptionHandler 返回 400 + 提示文案「请先在设置页填入 DeepSeek API Key」。
     * 此处仅做认证注入与结果封装，不捕获业务异常。
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
     * 查询角色被哪些聊天室引用：供角色删除前的"级联确认"弹窗使用，
     * 返回精简的 {id, name} 列表，避免向客户端泄露 ownerId 等敏感字段。
     *
     * <p>非 owner 一律回 403（不返 404，避免侧信道探测"角色是否存在"）。
     */
    @GetMapping("/{id}/references")
    public ResponseEntity<CharacterReferencesResponse> getCharacterReferences(
            Authentication auth, @PathVariable UUID id) {
        UUID userId = UUID.fromString(auth.getName());
        if (!characterService.isOwner(id, userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(characterService.findReferences(id, userId));
    }

    /**
     * 删除角色。Service 通过 deleteIfOwner 保证预设角色与他人角色不会被误删，
     * 失败统一回 403；预设角色实际由另一条管理路径处理，不走此处。
     *
     * <p>cascade 查询参数：
     * <ul>
     *   <li>缺省 / cascade=false：保持旧行为——若被房间引用则 Service 抛 400，前端走兜底提示。</li>
     *   <li>cascade=true：调用 deleteIfOwnerWithRooms，事务内一并删除全部引用房间与角色。</li>
     * </ul>
     * 仅接受字面量 true（用 Boolean.TRUE.equals 严格判断），其余值一律按 false 处理，
     * 避免误传 cascade=yes 等被当作 truthy 触发级联。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCharacter(
            Authentication auth,
            @PathVariable UUID id,
            @RequestParam(value = "cascade", required = false) Boolean cascade) {
        UUID userId = UUID.fromString(auth.getName());
        boolean cascadeEnabled = Boolean.TRUE.equals(cascade);
        boolean deleted = cascadeEnabled
                ? characterService.deleteIfOwnerWithRooms(id, userId)
                : characterService.deleteIfOwner(id, userId);
        if (!deleted) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.noContent().build();
    }
}
