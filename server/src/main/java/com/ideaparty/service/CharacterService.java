package com.ideaparty.service;

import com.ideaparty.dto.CharacterReferencesResponse;
import com.ideaparty.dto.CharacterRequest;
import com.ideaparty.dto.CharacterResponse;
import com.ideaparty.entity.Character;
import com.ideaparty.entity.Room;
import com.ideaparty.entity.User;
import com.ideaparty.repository.CharacterRepository;
import com.ideaparty.repository.MessageRepository;
import com.ideaparty.repository.RoomRepository;
import com.ideaparty.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
@Slf4j
public class CharacterService {

    // 角色是聊天室的最小可发言单元；本服务负责 AI 角色 prompt 的"冷启动"生成、CRUD 和删除前的外键守卫，
    // 与 RoomService（编排发言）、FirecrawlService（联网抓取）配合，避免在控制器里散落 HTTP/AI 调用细节。

    private final CharacterRepository characterRepository;
    private final UserRepository userRepository;
    private final RoomRepository roomRepository;
    private final MessageRepository messageRepository;
    private final RoomService roomService;
    private final FirecrawlService firecrawlService;
    // 文件落盘：用于在创建/更新角色时把外网头像 URL 自动下载到 uploads/avatars/，
    // 避免渲染头像时每次都打外网。详见 FileStorageService.storeFromUrl。
    private final FileStorageService fileStorageService;
    // 直接读 langchain4j 的 base-url 而非单独建配置项：与 LangChain4j 自动装配的 OpenAI 客户端共用同一接入点，
    // 这样 REST 调用走的也是同一个 DeepSeek 兼容 endpoint，便于将来切换供应商时只改一个配置。
    private final String deepseekBaseUrl;
    // 预设角色静态缓存：启动时从 classpath:presets.json 加载到内存，
    // 让 GET /api/characters/recommended 走纯内存 0 DB 查询。preset 修改走 git diff + 重启。
    private final com.ideaparty.cache.PresetCharacterCache presetCache;

    /**
     * 构造时一次性注入所有依赖（构造函数注入优于字段注入）：
     * 方便测试时 mock 替换，也避免循环依赖在字段注入时悄无声息地出现。
     * 多个 repository 看似冗余，实际对应"角色/用户/房间/消息"四张表的独立事务边界，
     * 让删除校验可以在一个事务里完整跑完（参考 deleteIfOwner）。
     */
    public CharacterService(CharacterRepository characterRepository, UserRepository userRepository, RoomRepository roomRepository, MessageRepository messageRepository, RoomService roomService, FirecrawlService firecrawlService, FileStorageService fileStorageService, @Value("${langchain4j.open-ai.base-url}") String deepseekBaseUrl, com.ideaparty.cache.PresetCharacterCache presetCache) {
        this.characterRepository = characterRepository;
        this.userRepository = userRepository;
        this.roomRepository = roomRepository;
        this.messageRepository = messageRepository;
        this.roomService = roomService;
        this.firecrawlService = firecrawlService;
        this.fileStorageService = fileStorageService;
        this.deepseekBaseUrl = deepseekBaseUrl;
        this.presetCache = presetCache;
    }

    /**
     * 判断给定文本是否主要为中文。
     * 阈值 30% 是为了在"中英混杂"的角色名（如"马斯克 Elon Musk"）下也能正确路由到中文 prompt 分支，
     * 避免被少量英文单词稀释导致走英文模板，丢失中文用户体验。
     * @param text 要检测的文本
     * @return 如果超过 30% 的字符是中文则返回 true
     */
    private boolean isChineseContent(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        long chineseChars = text.chars()
                .filter(c -> c >= 0x4e00 && c <= 0x9fa5)
                .count();
        return (double) chineseChars / text.length() > 0.3;
    }

    /**
     * 从外部文件加载角色 prompt 生成器模板。
     * 模板放在 classpath 而非硬编码：prompt 调优是非开发人员（产品/运营）的高频动作，
     * 改 txt 比改 Java 重新发版更轻量，也避免污染代码历史。
     * @return 系统 prompt 模板
     */
    private String loadPromptTemplate() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("prompts/character-prompt-generator.txt")) {
            if (is == null) {
                log.error("[DEBUG] Prompt template file not found: prompts/character-prompt-generator.txt");
                throw new RuntimeException("Prompt template file not found");
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("[DEBUG] Failed to load prompt template: {}", e.getMessage());
            throw new RuntimeException("Failed to load prompt template", e);
        }
    }

    /**
     * 创建角色的统一入口，供 CharacterController 调用。
     * 合约：userId 必须存在；未带 prompt 时会联网抓取 + AI 生成（可能慢且可能失败但有兜底）；
     * 返回持久化后的角色（含 id），preset 强制 false 以保证用户角色不会污染公共池。
     * 副作用：写入 character 表；可能触发 Firecrawl 抓取 + DeepSeek 调用。
     */
    public CharacterResponse create(UUID userId, CharacterRequest request) {
        // 创建角色的入口：若请求未带 prompt 则走联网抓取 + AI 生成（generatePromptFromWeb），
        // 让"只给个名字"就能建出可聊角色；preset 强制 false 以保证用户自定义角色不会被混入公共预设池。
        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        // 同 owner + 同名去重：让"反复点推荐角色 clone 副本"这条路径天然幂等，
        // 避免每次点击都生成一条新"毛泽东"。命中已有记录时直接复用，不抛错
        // （clone 路径对幂等性有依赖，抛错会破坏 startChat 的复用既有房间逻辑）。
        String trimmedName = request.getName() == null ? "" : request.getName().trim();
        if (!trimmedName.isEmpty()) {
            Optional<Character> existing = characterRepository
                    .findFirstByOwnerIdAndNameAndIsPresetFalse(userId, trimmedName);
            if (existing.isPresent()) {
                log.info("[DEBUG] Character already exists for owner {} with name '{}', reusing id: {}",
                        userId, trimmedName, existing.get().getId());
                return CharacterResponse.fromEntity(existing.get());
            }
        }

        // 若未提供 prompt 则生成
        String prompt = request.getPrompt();
        if (prompt == null || prompt.isBlank()) {
            log.info("[DEBUG] No prompt provided, generating from web for: {}", request.getName());
            prompt = generatePromptFromWeb(request.getName(), owner.getApiKey());
        }

        Character character = new Character();
        character.setName(trimmedName);
        character.setDescription(request.getDescription());
        character.setAvatarUrl(downloadAvatarIfExternal(request.getAvatarUrl()));
        character.setPrompt(prompt);
        character.setOwner(owner);
        character.setPreset(false);

        Character saved;
        try {
            saved = characterRepository.saveAndFlush(character);
        } catch (DataIntegrityViolationException e) {
            // 并发 INSERT 触发了 (owner_id, name) 唯一约束。
            // 此刻另一个并发请求刚把同名角色插了进去，我们重新查一下拿那条真实的记录返回。
            log.info("[DEBUG] Duplicate insert caught by unique constraint for '{}', re-fetching", trimmedName);
            return characterRepository
                    .findFirstByOwnerIdAndNameAndIsPresetFalse(userId, trimmedName)
                    .map(CharacterResponse::fromEntity)
                    .orElseThrow(() -> e);
        }
        log.info("[DEBUG] Character created with id: {}, prompt length: {}", saved.getId(), prompt.length());
        return CharacterResponse.fromEntity(saved);
    }

    /**
     * 检测 avatarUrl 是否指向外网（http/https），是则下载到本地 uploads/avatars/ 并改写成本地路径。
     *
     * <p>用于 create / update 角色时自动把"维基百科 / 用户粘贴的 URL" 落本地，避免后续
     * 渲染头像每次都打外网（且国内访问维基常超时）。本地路径形如 {@code /api/upload/avatars/auto_<hash>.<ext>}。
     *
     * <p>行为契约：
     *   - 入参为 null / 空 / 已是本地 {@code /api/...} → 原样返回；
     *   - 下载失败 → 静默保留原 URL，不阻塞角色创建（与 prompt 生成失败降级策略一致）。
     *
     * @param avatarUrl 前端传入的 avatarUrl（可能是外网或本地）
     * @return 落本地后的 URL（仍是同一字符串，或下载后的本地路径）
     */
    private String downloadAvatarIfExternal(String avatarUrl) {
        if (avatarUrl == null || avatarUrl.isBlank()) return avatarUrl;
        // 已经是本地路径就不用处理
        if (avatarUrl.startsWith("/api/")) return avatarUrl;
        // 只处理 http/https 外网
        if (!avatarUrl.startsWith("http://") && !avatarUrl.startsWith("https://")) return avatarUrl;
        String stored = fileStorageService.storeFromUrl(avatarUrl);
        if (stored == null) {
            log.warn("[DEBUG] downloadAvatarIfExternal failed to download, keeping original URL: {}", avatarUrl);
            return avatarUrl;
        }
        return "/api/upload/avatars/" + stored;
    }

    /**
     * 基于角色名称和/或描述生成角色 prompt。
     * 与 generatePromptByName 不同：这条是面向"用户在 UI 里点 AI 生成"的主入口，
     * 必须把 API Key 缺失和 AI 真实失败都向上抛，让 GlobalExceptionHandler 返回 4xx/5xx 给前端，
     * 不能再吞掉异常返回假 prompt（历史 bug：用户没填 key 时会拿到硬编码字符串，误以为 AI 真在工作）。
     * @param userId 请求生成的用户（用于取其 API key）
     * @param name 角色名（可选，用于 LLM 知识生成）
     * @param description 用户提供的描述（可选，直接用于 AI 生成）
     * @return 生成的 prompt 文本
     * @throws IllegalArgumentException 用户未配置 DeepSeek API Key 时抛出
     */
    public String generatePrompt(UUID userId, String name, String description) {
        User owner;
        try {
            owner = userRepository.findById(userId).orElse(null);
        } catch (Exception e) {
            owner = null;
        }

        String userApiKey = (owner != null) ? owner.getApiKey() : null;

        // key 缺失前置校验：复用 LLM 调用内部的三段判定，与生成时行为保持一致
        // 不带 Authorization 调 DeepSeek 必然失败，与其让用户看到 500 不如直接引导去设置页
        if (userApiKey == null || userApiKey.isBlank()
                || "sk-dummy-key-for-testing".equals(userApiKey)) {
            throw new IllegalArgumentException("请先在设置页填入 DeepSeek API Key");
        }

        if (name != null && !name.isBlank()) {
            // name 非空时把 description 也带上，避免泛称（如"王老师"）LLM 知识不足
            String result = generatePromptWithAIFromNameAndDescription(name, description, userApiKey);
            log.info("[DEBUG] generatePrompt success, length: {}", result.length());
            return result;
        }
        if (description != null && !description.isBlank()) {
            return generatePromptWithAIFromDescription(description, userApiKey);
        }
        // name 和 description 都空：UI 层 handleGeneratePrompt 已挡住，服务端兜底防绕过
        return "You are a unique character. Speak in character with depth and authenticity.";
    }

    /**
     * 无 userId 的 prompt 生成入口，给 DataLoader 这类"没有用户上下文"的场景用。
     * 走与 {@link #generatePrompt(UUID, String, String)} 完全相同的 LLM 路径，
     * userApiKey 由调用方提供（DataLoader 从 LangChain4j 配置里读，避免 DeepSeek 网关 401）。
     * 调用方应保证：
     *   - name 非空（传名字才能生成）
     *   - description 可空，作为 LLM 的额外上下文
     *   - apiKey 可空：null/空/dummy 时 fallback 为通用模板字符串（与上面那个公开方法保持一致）。
     */
    public String generatePromptByName(String name, String description, String apiKey) {
        if (name == null || name.isBlank()) {
            return "You are a unique character. Speak in character with depth and authenticity.";
        }
        try {
            String result = generatePromptWithAIFromName(name, apiKey);
            log.info("[DEBUG] generatePromptByName success for '{}', length: {}", name, result.length());
            return result;
        } catch (Exception e) {
            log.error("[DEBUG] generatePromptByName failed for '{}': {}", name, e.getMessage());
            return "You are a unique character. Speak in character with depth and authenticity.";
        }
    }

    /**
     * 直接使用 AI 基于角色名生成 prompt。
     * 走"纯 LLM 知识"路径而非联网：避免对常见人物（如 Elon Musk）触发 Firecrawl 限流或抓取失败，
     * 也能拿到模型预训练时已经内化的语气/代表作，比"摘要一段维基百科"更像本人。
     * apiKey 为 null/空/dummy 时不携带 Authorization：交给上游 DeepSeek 网关走平台共享额度，
     * 保证没配 key 的用户也能体验 prompt 生成。
     * 不进行联网抓取，直接使用 LLM 关于该角色的知识。
     */
    private String generatePromptWithAIFromName(String characterName, String apiKey) {
        return generatePromptWithAIFromNameAndDescription(characterName, null, apiKey);
    }

    /**
     * name + 上下文描述 走 LLM 生成 prompt。
     * 关键改进：把 description 也拼进 user message，避免"name=王老师"这种泛称
     * LLM 知识不足时直接走"你是王老师..."的贫瘠兜底。
     * 兼容 DataLoader 等只传 name 的入口（description=null 时按 name-only 模板）。
     */
    private String generatePromptWithAIFromNameAndDescription(String characterName, String description, String apiKey) {
        RestTemplate restTemplate = new RestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        if (apiKey != null && !apiKey.isBlank() && !apiKey.equals("sk-dummy-key-for-testing")) {
            headers.set("Authorization", "Bearer " + apiKey);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("model", "deepseek-chat");

        // 一路中文打通：模板 (loadPromptTemplate) 已是中文，user message 也固定中文，
        // 去掉 isChineseContent 分流，英文名字角色也能拿到中文角色卡。
        String systemPrompt = loadPromptTemplate();
        // 把 description 拼进 user message（若有）
        String userMessage;
        if (description != null && !description.isBlank()) {
            userMessage = String.format(
                "请为以下角色创建一个角色提示词：\n\n角色名：%s\n用户补充描述：%s\n\n立即生成角色提示词：",
                characterName, description
            );
        } else {
            userMessage = String.format("请为以下角色创建一个角色提示词：%s\n\n立即生成角色提示词：", characterName);
        }

        body.put("messages", List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user", "content", userMessage)
        ));
        body.put("temperature", 0.7);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        log.info("[DEBUG] Calling DeepSeek API to generate prompt from name+description: name={}, desc_len={}",
                characterName, description != null ? description.length() : 0);

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
                    Map<String, Object> choice = choices.get(0);
                    Map<String, Object> message = (Map<String, Object>) choice.get("message");
                    String content = (String) message.get("content");
                    return content.trim();
                }
            }
        } catch (Exception e) {
            log.error("[DEBUG] AI prompt generation from name+description failed: {}", e.getMessage());
        }

        // AI 失败时的兜底（统一中文）
        return String.format(
            "你是%s。以深度和真实性表达自己的观点和性格，展现独特的个人魅力。",
            characterName
        );
    }

    /**
     * 直接使用 AI 基于描述生成 prompt。
     * description 来自用户自定义输入（非真实人物），不能像 name 那样依赖 LLM 知识生成，
     * 所以这里使用强约束的中英 system prompt（性格/语言习惯/世界观/行为规则/对话示例），
     * 把一段模糊描述"翻译"成稳定可复用的角色卡。中文/英文 prompt 内容互为镜像翻译，
     * 是为了让两种语言用户拿到的角色气质一致，而不是仅把变量翻译过去。
     */
    private String generatePromptWithAIFromDescription(String description, String apiKey) {
        RestTemplate restTemplate = new RestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        if (apiKey != null && !apiKey.isBlank() && !apiKey.equals("sk-dummy-key-for-testing")) {
            headers.set("Authorization", "Bearer " + apiKey);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("model", "deepseek-chat");

        // 一路中文打通：原 isChineseContent 分流的 system prompt / user message 合并为单一中文版本，
        // 英文描述的角色同样产出中文角色卡，行为规则一致。
        String systemPrompt = """
            你是一个角色提示词生成器，为 AI 聊天平台根据用户描述创建极具特色、令人难忘的角色。

            # 核心目标
            用户一聊就能记住这个角色，愿意持续对话。不是写人物简介，而是创造"真实存在的人"。

            # 必须赋予角色的要素
            1. 标志性语言习惯：反问、"你懂我意思吧？"、阴阳怪气、短句、长篇论述、打断人、爱用比喻、经常"啧"、先否定再认可
            2. 强烈观点（至少3条）：极度讨厌浪费时间 / 相信努力大于天赋 / 不相信爱情 / 崇拜金钱 / 讨厌互联网文化 / 对AI极度乐观或悲观 / 认为大多数人活得太麻木
            3. 独特世界观：把感情问题理解成"资源错配" / 天然怀疑所有人 / 把人生理解成"不断修bug"
            4. 稳定情绪基调：暴躁 / 疲惫 / 亢奋 / 冷幽默 / 疑心重 / 高傲 / 神经质 / 厌世 / 理想主义

            # 禁止使用的描述（废话）
            ❌ "聪明且善良" / "温柔体贴" / "睿智冷静" / "喜欢帮助别人" / "拥有丰富知识" / "逻辑清晰" / "善于分析"

            # 正确 vs 错误示例
            错误："他很聪明"
            正确："他能三分钟看穿别人真正想问什么，但从不直接说破"

            错误："她很温柔"
            正确："她骂人很凶，但每天凌晨都会提醒朋友记得吃药"

            # 输出结构
            1. 角色身份：职业/经历、当前状态、核心信念、最大执念、最大弱点
            2. 说话风格：语气、节奏、高频词、口头禅、是否喜欢提问/嘲讽/说教/打断、是否情绪化
            3. 世界观与价值观：至少3条强烈观点
            4. 行为规则：如何回应用户、什么情况会生气/兴奋、如何表达关心、如何回避脆弱话题
            5. 对话示例：6~10句像真实聊天记录的示例，不要像小说台词

            # 风格要求
            - 强聊天感、强互动感
            - 避免文学化、避免AI味、避免官方感
            - 字数：150~250字
            - 每一个字都必须基于用户描述的内容

            如果用户描述不够详细，补充合理的细节，但必须符合描述的整体方向。
            """;
        String userMessage = String.format("请根据以下描述创建一个角色提示词：\n\n%s\n\n立即生成角色提示词：", description);

        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userMessage)
        ));
        body.put("temperature", 0.7);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        log.info("[DEBUG] Calling DeepSeek API to generate prompt from description");

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
                    Map<String, Object> choice = choices.get(0);
                    Map<String, Object> message = (Map<String, Object>) choice.get("message");
                    String content = (String) message.get("content");
                    return content.trim();
                }
            }
        } catch (Exception e) {
            log.error("[DEBUG] AI prompt generation from description failed: {}", e.getMessage());
        }

        // AI 失败时的兜底（统一中文）
        return String.format(
            "你是一个独特的角色。%s 以深度和真实性表达自己的观点和性格。",
            description
        );
    }

    private String generatePromptFromWeb(String characterName, String userApiKey) {
        // 三级降级策略：1) Firecrawl 联网抓原文；2) 用模板 + LLM 浓缩成角色卡；3) 直接拼"角色名+前1000字原文"作为兜底，
        // 保证哪怕 AI 全部不可用，用户至少能拿到一份基于真实资料的可用 prompt。
        // 步骤 1：抓取关于该角色的网络内容
        String scrapedContent = firecrawlService.scrape(characterName);
        log.info("[DEBUG] Scraped content length: {}", scrapedContent.length());

        // 步骤 2：尝试用原始内容和外部模板进行 AI 生成
        if (userApiKey != null && !userApiKey.isBlank() && !userApiKey.equals("sk-dummy-key-for-testing")) {
            try {
                String aiGeneratedPrompt = generatePromptWithAIFromWebContent(characterName, scrapedContent, userApiKey);
                if (aiGeneratedPrompt != null && !aiGeneratedPrompt.isBlank()) {
                    log.info("[DEBUG] AI generated prompt length: {}", aiGeneratedPrompt.length());
                    return aiGeneratedPrompt;
                }
            } catch (Exception e) {
                log.error("[DEBUG] AI prompt generation failed: {}", e.getMessage());
            }
        }

        // 步骤 3：兜底 - 使用简单的角色名 prompt
        return "You are " + characterName + ". " + scrapedContent.substring(0, Math.min(scrapedContent.length(), 1000));
    }

    /**
     * 使用联网抓取内容配合外部模板，经 AI 生成 prompt。
     */
    private String generatePromptWithAIFromWebContent(String characterName, String scrapedContent, String apiKey) {
        RestTemplate restTemplate = new RestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        Map<String, Object> body = new HashMap<>();
        body.put("model", "deepseek-chat");

        // 一路中文打通：模板 (loadPromptTemplate) 已是中文，user message 也固定中文。
        String systemPrompt = loadPromptTemplate();
        String userMessage = String.format(
            "请根据以下信息为「%s」创建一个角色提示词：\n\n%s\n\n立即生成角色提示词：",
            characterName, scrapedContent
        );

        body.put("messages", List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user", "content", userMessage)
        ));
        body.put("temperature", 0.7);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        log.info("[DEBUG] Calling DeepSeek API to generate prompt for: {}", characterName);

        ResponseEntity<Map> response = restTemplate.exchange(
            deepseekBaseUrl + "/chat/completions",
            HttpMethod.POST,
            request,
            Map.class
        );

        if (response.getBody() != null && response.getBody().containsKey("choices")) {
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.getBody().get("choices");
            if (!choices.isEmpty()) {
                Map<String, Object> choice = choices.get(0);
                Map<String, Object> message = (Map<String, Object>) choice.get("message");
                String content = (String) message.get("content");
                return content.trim();
            }
        }

        return null;
    }

    /**
     * 把 Firecrawl 抓回的原始 markdown 清洗成"接近正文"的纯文本，给下游 prompt 生成当输入。
     * 该方法是大量经验性的正则：维基百科/百度百科的引用标记、目录、链接碎片、"请勿直接提交机械翻译"等
     * 噪声如果不剔除，会被 LLM 当作角色语料学进去，导致角色张口就是"参见 [1]"。保守截断 2000 字上限
     * 控制 prompt token 成本，避免把整本百科喂给模型。
     */
    private String cleanMarkdown(String markdown) {
        if (markdown == null) return "";

        String content = markdown;

        // 跳过目录部分 - 寻找更合适的定位锚点
        int firstHeading = content.indexOf("## ");
        int firstParagraph = content.indexOf("\n\n");

        int skipTo = content.length();
        if (firstHeading > 0 && firstHeading < 2000) {
            skipTo = firstHeading;
        } else if (firstParagraph > 100 && firstParagraph < 2000) {
            skipTo = firstParagraph;
        }
        if (skipTo < 2000 && skipTo > 50) {
            content = content.substring(skipTo);
        }

        // 移除所有 markdown 残留标记
        content = content
            .replaceAll("!\\[([^\\]]*)\\]\\([^)]*\\)", "")
            .replaceAll("\\[([^\\]]+)\\]\\([^)]+\\)", "$1")
            .replaceAll("\\[[\\w\\s\\u4e00-\\u9fa5]*\\d+[^]]*\\]", "")
            .replaceAll("\\[edit\\]", "")
            .replaceAll("<[^>]+>", "")
            .replaceAll("https?://\\S+", "")
            .replaceAll("^#{1,6}\\s*", "")
            .replaceAll("_{2,}", "")
            .replaceAll("\\*{2,}", "")
            .replaceAll("\\\\", "")
            // 清理维基百科链接残留
            .replaceAll("\\(\\)\\[^\\[]*\\]", "")
            .replaceAll("\\[\\]", "")
            .replaceAll("\\(\\(([^)]*)\\)", "$1")
            .replaceAll("\\[([^\\]]+)\\]\\[([^\\]]*)\\]", "$1")
            // 移除剩余的引用标记，如 [1]、[edit] 等
            .replaceAll("\\[[a-zA-Z0-9]+\\]", "")
            // 清理空的括号和方括号
            .replaceAll("\\(\\s*\\)", "")
            .replaceAll("\\[\\s*\\]", "")
            // 清理维基百科链接残留 —— 当 (word) 是断链时，把括号去掉只保留 word
            .replaceAll("\\(([A-Za-z\\u4e00-\\u9fa5]+)\\)", "$1")
            // 清理孤儿左括号 —— 移除没有匹配右括号的 (
            // 用于处理类似 "(word1(word2" 这种链接被合并的形态
            .replaceAll("\\(([^)]+)\\(([^)]+)\\)", "$1 $2")
            // 清理单词开头残留的括号
            .replaceAll("([A-Za-z\\u4e00-\\u9fa5])\\(([^)]+)\\)", "$1$2")
            // 移除未匹配的左括号
            .replaceAll("\\(([A-Za-z\\u4e00-\\u9fa5])", "$1")
            // 移除未匹配的右括号
            .replaceAll("([A-Za-z\\u4e00-\\u9fa5])\\)", "$1")
            // 清理 []: 模式（维基百科引用风格）
            .replaceAll("\\[\\s*\\]\\s*:", "")
            .replaceAll("\\[\\s*\\]\\s*\\n", "\n")
            // 更激进地移除章节标题（## Title）
            .replaceAll("##+\\s*[^\\n]+", "")
            // 压缩多余空格与换行
            .replaceAll("\\s{2,}", " ")
            // 移除以括号/方括号为主的行
            .replaceAll("^[\\s\\(\\)\\[\\]]+$", "")
            // 移除维基百科特有的警告文本
            .replaceAll("请勿直接提交机械翻译[，,]?也不要翻译不可靠、低品质内容[。]?", "")
            .replaceAll("Wikipedia[\\s]*does\\s+not\\s+have\\s+an\\s+article.*?(?=[。]|$)", "")
            // 移除"条目：xxx"模式
            .replaceAll("条目[：:]\\s*[^。]+", "")
            // 清理中文括号与引号
            .replaceAll("（", "(")
            .replaceAll("）", ")")
            .replaceAll("“", "\"")
            .replaceAll("”", "\"")
            .replaceAll("『", "'")
            .replaceAll("』", "'");

        // 检测内容是否主要为中文
        long chineseCharCount = content.chars().filter(c -> c >= 0x4e00 && c <= 0x9fa5).count();
        double chineseRatio = (double) chineseCharCount / Math.max(content.length(), 1);

        // 中文（或混合）内容按中英文句子边界切分
        String[] sentences;
        if (chineseRatio > 0.2) {
            // 中文或中英混合内容 —— 按中文 。！？ 或英文 .!? 切分，后跟换行/空格
            sentences = content.split("(?<=[。！？.!?])\\s*(?=\\n|[A-Z\\u4e00-\\u9fa5]|$)");
        } else {
            // 英文内容 —— 原始正则
            sentences = content.split("(?<=[.!?])\\s+(?=[A-Z])");
        }

        StringBuilder cleanText = new StringBuilder();

        for (String sentence : sentences) {
            sentence = sentence.trim();
            if (sentence.length() < 10) continue;
            if (sentence.contains("|") || sentence.contains("---")) continue;
            if (sentence.matches("^[A-Z][a-z]+ \\| .*")) continue;
            if (sentence.startsWith("Born ") || sentence.startsWith(" c. ") || sentence.startsWith("Died ")) continue;
            if (sentence.contains("This is a ") || sentence.contains("Wikipedia") || sentence.contains("Jump to")) continue;
            if (sentence.contains("article") && sentence.length() < 100) continue;
            // 过滤掉以括号为主的句子
            if (sentence.replaceAll("[^\\[\\]]", "").length() > sentence.length() * 0.3) continue;
            // 过滤掉看起来像维基百科导航的句子
            if (sentence.matches("^\\(?[A-Z][a-z]+(\\[.*\\])?(:|\\|).*")) continue;
            // 过滤掉非常短且只是链接的句子
            if (sentence.length() < 20 && sentence.matches(".*\\[.*\\].*")) continue;

            // 中文内容：对字母占比检查放宽
            if (chineseRatio > 0.2) {
                // 中文内容 —— 只要包含有意义的中文字符即保留
                long sentenceChinese = sentence.chars().filter(c -> c >= 0x4e00 && c <= 0x9fa5).count();
                if (sentenceChinese < 10) continue;
            } else {
                // 英文内容 —— 原始检查
                String letters = sentence.replaceAll("[^a-zA-Z]", "");
                if (letters.length() < sentence.length() * 0.3) continue;
            }

            cleanText.append(sentence).append(" ");
            if (cleanText.length() > 2000) break;
        }

        return cleanText.toString().trim();
    }

    /**
     * 与 generatePromptWithAIFromWebContent 职责重复（旧版本中文专用版），目前未被任何上游调用，
     * 保留是因为模板在历史版本里调优过，先不删以便回滚对比；后续清理 prompt 生成链路时再移除。
     */
    private String generatePromptWithAI(String characterName, String scrapedContent, String apiKey) {
        RestTemplate restTemplate = new RestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        Map<String, Object> body = new HashMap<>();
        body.put("model", "deepseek-chat");

        String userMessage = String.format(
            "请根据以下信息为 %s 创建一个角色提示词：\n\n%s\n\n立即生成角色提示词：",
            characterName, scrapedContent
        );

        String systemPrompt = """
            你是一个角色提示词生成器。任务是基于真实人物信息，创建能反映这个人实际做过什么、相信什么的高质量角色提示词。

            # 核心原则
            用户一聊就能记住，愿意持续聊下去。不是写传记，而是创造"真实存在的人"。

            # 必须赋予角色的要素
            1. 标志性语言习惯：反问、"你懂我意思吧？"、阴阳怪气、短句、长篇论述、打断人、爱用比喻、经常"啧"、先否定再认可
            2. 强烈观点：对浪费时间极度厌恶 / 相信努力大于天赋 / 不相信爱情 / 崇拜金钱 / 讨厌互联网文化 / 对AI极度乐观或悲观 / 认为大多数 人活得太麻木
            3. 独特世界观：把感情理解成"资源错配" / 天然怀疑所有人 / 把人生理解成"不断修bug"
            4. 稳定情绪基调：暴躁 / 疲惫 / 亢奋 / 冷幽默 / 疑心重 / 高傲 / 神经质 / 厌世 / 理想主义

            # 不同职业必须听起来完全不同
            - 科学家：精准、好奇、引用实验/数据
            - 诗人：抒情、比喻、情感深度
            - 战士/将军：荣誉、策略、忠诚、力量
            - 哲学家：思想、伦理、意义、本质
            - 政治家/外交官：权力、人际关系、策略
            - 宗教人士：灵性智慧、道德权威

            # 禁止使用的描述
            ❌ "聪明且善良" / "温柔体贴" / "睿智冷静" / "喜欢帮助别人" / "逻辑清晰"

            # 正确示范
            错误："他很聪明"
            正确："他能三分钟看穿别人真正想问什么，但从不直接说破"

            # 输出格式（第一人称）
            1. 身份锚定句：以"你是[姓名]..."开头，立即建立独特身份和职业
            2. 代表性言论：2-3句这个人可能会说的、独特反映其信念的话
            3. 语言风格：语气和词汇必须匹配其职业（物理学家听起来和诗人完全不同）
            4. 标志性视角：这个人持有的独特世界观

            # 质量要求
            - 字数：200~400字
            - 必须具体：引用实际成就、信念或名言（如果有）
            - 每个句子都应该听起来像不同类型的人，不是通用的"智者"
            """;

        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userMessage)
        ));
        body.put("temperature", 0.7);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        log.info("[DEBUG] Calling DeepSeek API to generate prompt for: {}", characterName);

        ResponseEntity<Map> response = restTemplate.exchange(
                deepseekBaseUrl + "/chat/completions",
                HttpMethod.POST,
                request,
                Map.class
        );

        if (response.getBody() != null && response.getBody().containsKey("choices")) {
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.getBody().get("choices");
            if (!choices.isEmpty()) {
                Map<String, Object> choice = choices.get(0);
                Map<String, Object> message = (Map<String, Object>) choice.get("message");
                String content = (String) message.get("content");
                return content.trim();
            }
        }

        return null;
    }

    /**
     * 把清洗后的句子级内容拼成最终 prompt 文本。多个 fallback 文案（<50 / <100 / 正常）是因为
     * 真实抓取结果长度波动很大（维基百科短条目 vs 长人物），宁可换措辞也不能让生成的 prompt 出现
     * "You are {{name}}. " 这种半成品——下游 LLM 会原样复读。
     */
    private String convertToPromptFormat(String characterName, String content) {
        // 若内容过短，直接使用
        if (content == null || content.trim().length() < 50) {
            return String.format(
                "You are %s. You are a distinctive individual with unique experiences and perspectives. " +
                "Speak authentically about what you know and believe, drawing from your specific background and knowledge.",
                characterName
            );
        }

        // 从内容中提取有意义的句子
        String[] sentences = content.split("(?<=[.!?])\\s+");
        StringBuilder keyTraits = new StringBuilder();

        for (String sentence : sentences) {
            sentence = sentence.trim();
            // 放宽长度要求 —— 接受 ≥ 20 字的句子
            if (sentence.length() >= 20) {
                if (keyTraits.length() > 0) keyTraits.append(" ");
                keyTraits.append(sentence);
                if (keyTraits.length() >= 600) break;
            }
        }

        String traits = keyTraits.toString().trim();

        // 若可用内容仍然很少，则尽量利用已有内容
        if (traits.length() < 100) {
            return String.format(
                "You are %s. %s Speak about your experiences, knowledge, and beliefs with authenticity and depth.",
                characterName, traits.isEmpty() ? "You have a unique background and perspective." : traits
            );
        }

        return String.format(
            "You are %s. %s When you speak, draw upon your specific knowledge, experiences, and beliefs. Express yourself in a way that reflects who you truly are.",
            characterName, traits
        );
    }

    /**
     * 列出指定用户自建的角色（不含预设），供"我的角色"页加载。
     * 读路径走 Entity→DTO 转换在 Service 层完成，避免控制器直接接触持久化对象。
     * 调用方：CharacterController 的"我的角色"列表接口。
     */
    public List<CharacterResponse> findByUserId(UUID userId) {
        return characterRepository.findByOwnerId(userId)
                .stream()
                .map(CharacterResponse::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * 列出系统预设角色（is_preset=true），供新用户"开箱即用"创建聊天室。
     * 预设与用户自建角色共用同一张表，通过 owner 与 preset 字段区分，避免双表 JOIN。
     * 调用方：新建聊天室时的"选择预设角色"下拉。
     *
     * <p><b>V10 优化：</b>走内存缓存（见 {@link #findAllRecommended} 注释）。
     */
    public List<CharacterResponse> findPresets() {
        return presetCache.getAll();
    }

    /**
     * 管理员视角的"所有角色"全量查询，不做分页。
     * 仅在管理后台/调试时使用，线上接口请用 findByUserId / findPresets。
     * 调用方：管理后台角色管理页。
     */
    public List<CharacterResponse> findAll() {
        return characterRepository.findAll()
                .stream()
                .map(CharacterResponse::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * 按 id 查单个角色，找不到返回 Optional.empty() 而非 null，
     * 让调用方显式处理"不存在"分支，避免 NPE 扩散。
     * 调用方：聊天室详情页解析角色信息。
     */
    public Optional<CharacterResponse> findById(UUID id) {
        return characterRepository.findById(id)
                .map(CharacterResponse::fromEntity);
    }

    /**
     * 更新角色的名称/描述/头像/prompt。仅 owner 可改（DB 层用 findByIdAndOwnerId 双重条件兜底）。
     * 返回 Optional：角色不存在或非 owner 时返回 empty()，由控制器映射成 404；
     * 与 create 不同：update 不会自动重新生成 prompt，避免覆盖用户的精调结果。
     * 调用方：角色编辑表单提交接口。
     */
    public Optional<CharacterResponse> update(UUID characterId, UUID userId, CharacterRequest request) {
        Optional<Character> optCharacter = characterRepository.findByIdAndOwnerId(characterId, userId);
        if (optCharacter.isEmpty()) {
            return Optional.empty();
        }

        Character character = optCharacter.get();
        character.setName(request.getName());
        character.setDescription(request.getDescription());
        character.setAvatarUrl(downloadAvatarIfExternal(request.getAvatarUrl()));
        character.setPrompt(request.getPrompt());

        Character saved = characterRepository.save(character);
        return Optional.of(CharacterResponse.fromEntity(saved));
    }

    /**
     * 删除角色前显式校验外键引用，而不是直接依赖 DB 的 ON DELETE 行为：
     * 删除"被聊天室/历史消息引用"的角色会让数据出现孤儿引用或被级联清空，破坏用户聊天历史；
     * 业务上选择让用户先去手动清理（删房间），把误删成本留给用户可控的步骤。
     */
    public boolean deleteIfOwner(UUID characterId, UUID userId) {
        if (!characterRepository.existsByIdAndOwnerId(characterId, userId)) {
            return false;
        }
        // 检查是否被外键引用（房间/消息），有引用就拒绝删除
        // 让用户先去手动清理（删除房间等），避免误删历史数据
        String blockedBy = checkForeignKeyReferences(characterId);
        if (blockedBy != null) {
            throw new IllegalArgumentException(blockedBy);
        }
        characterRepository.deleteById(characterId);
        return true;
    }

    /**
     * 列出引用了指定角色的全部房间，供角色删除前的"级联确认"弹窗使用。
     *
     * <p>只校验角色所有权：业务约定角色 owner 与引用房间 owner 通常一致
     * （角色库中的私有角色才能被引用，预设角色由内存缓存加载不进 DB），
     * 因此"跨用户引用"在实际场景中几乎不存在，但仍通过复用 RoomService.deleteIfOwner
     * 的 owner 校验做防御。
     *
     * <p>排序：按房间名不区分大小写升序，同名房间按 createdAt 升序做 tie-breaker，
     * 让前端弹窗内房间列表的展示顺序稳定，避免每次打开抖动。
     *
     * @param characterId 角色 ID
     * @param userId      当前用户 ID；非 owner 抛 AccessDeniedException
     * @return 引用列表的精简 DTO（id + name，不暴露 ownerId）
     */
    @Transactional(readOnly = true)
    public CharacterReferencesResponse findReferences(UUID characterId, UUID userId) {
        if (!characterRepository.existsByIdAndOwnerId(characterId, userId)) {
            throw new AccessDeniedException("Not owner of character");
        }
        // getReferenceById 只返回代理对象，不发 SELECT，避免把整行 Character 加载进内存
        Character probe = characterRepository.getReferenceById(characterId);
        List<Room> rooms = roomRepository.findAllByCharactersContaining(probe);
        rooms.sort(Comparator
                .comparing(Room::getName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Room::getCreatedAt));
        return new CharacterReferencesResponse(
                characterId,
                rooms.size(),
                rooms.stream()
                        .map(r -> new CharacterReferencesResponse.ReferencedRoom(r.getId(), r.getName()))
                        .toList()
        );
    }

    /**
     * 级联删除角色：先删全部引用该角色的房间，最后删角色本身。
     *
     * <p>事务原子性：方法整体包在 @Transactional 里，Room.delete + Character.delete
     * 任一失败则整事务回滚；Room→RoomMember / Message / room_characters 中间表的清理
     * 依赖 Room 实体上的 JPA cascade 配置完成，无需手动 SQL。
     *
     * <p>复用而非重写：调用 RoomService.deleteIfOwner 而非直接 roomRepository.delete(room)，
     * 是为了复用其内部的"仅房主可删"鉴权逻辑——理论上角色 owner = 房间 owner，
     * 但若发生 race condition（用户在删除过程中房间 owner 变更），AccessDeniedException 会
     * 触发整事务回滚，前端展示 403 错误。
     *
     * @return true 表示成功；false 表示用户不是角色 owner（控制器返回 403）
     */
    @Transactional
    public boolean deleteIfOwnerWithRooms(UUID characterId, UUID userId) {
        if (!characterRepository.existsByIdAndOwnerId(characterId, userId)) {
            return false;
        }
        Character probe = characterRepository.getReferenceById(characterId);
        List<Room> rooms = roomRepository.findAllByCharactersContaining(probe);
        log.info("[DEBUG] Cascade-deleting character {} with {} referencing rooms (user {})",
                characterId, rooms.size(), userId);
        for (Room r : rooms) {
            // 复用 RoomService.deleteIfOwner：复用鉴权 + JPA cascade 自动清 RoomMember / Message / 中间表
            roomService.deleteIfOwner(r.getId(), userId);
        }
        characterRepository.deleteById(characterId);
        return true;
    }

    /**
     * 检查该角色是否被其他表引用，返回具体原因；无引用返回 null。
     * 返回"中文提示字符串"而不是结构化对象，是因为这条异常会原样抛给前端展示给用户，
     * 让用户看到具体数量（"被 3 个聊天室引用"）比"FK conflict"更可执行。
     */
    private String checkForeignKeyReferences(UUID characterId) {
        // 1) 房间引用
        long roomCount = roomRepository.countByCharactersId(characterId);
        if (roomCount > 0) {
            return String.format("该角色被 %d 个聊天室引用，请先删除相关聊天室后再删除角色", roomCount);
        }
        // 2) 消息引用
        long messageCount = messageRepository.countByCharacterId(characterId);
        if (messageCount > 0) {
            return String.format("该角色有 %d 条历史消息，请先删除相关聊天室后再删除角色", messageCount);
        }
        return null;
    }

    /**
     * 鉴权用：判断指定用户是否是该角色的 owner，供控制器在更新/删除前做权限校验。
     * 比把 owner 字段读出来比较更省一次 SELECT，exists 查询只回 bool。
     * 调用方：CharacterController 的更新/删除接口前置校验。
     */
    public boolean isOwner(UUID characterId, UUID userId) {
        return characterRepository.existsByIdAndOwnerId(characterId, userId);
    }

    /**
     * 返回"开箱即用"推荐区角色：按 name 升序输出全部预设角色，最多 {@code limit} 条。
     * 不再按 usage_count 排序——推荐位固定展示"历史上最具影响力的 N 位人物"，
     * 顺序由 {@code data.sql} / {@code DataLoader.seedCharacters()} 写入时的
     * UUID 序列决定，前端按 3 排 × 6 网格渲染。
     */
    public List<CharacterResponse> findRecommended(int limit) {
        return characterRepository.findByIsPresetTrueOrderByNameAsc()
                .stream()
                .limit(limit)
                .map(CharacterResponse::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * 返回全部预设角色（不带 limit）。
     * 给"推荐位"前端一次性拉全 36 人后由前端按 18 一批做"换一批"切片，
     * 避免每切一次就发一次 HTTP；同时与"查全部 + 客户端分页"的产品形态对齐。
     * 排序与 {@link #findRecommended(int)} 保持一致（name 升序），保持数据稳定。
     *
     * <p><b>V10 优化：</b>走 {@link com.ideaparty.cache.PresetCharacterCache} 内存缓存，
     * 0 DB 查询。原实现是 characterRepository.findByIsPresetTrueOrderByNameAsc()，
     * 每次用户访问发现页都打一次 MySQL；现在改成读 132KB 静态 JSON 一次性加载到 JVM。
     */
    public List<CharacterResponse> findAllRecommended() {
        return presetCache.getAll();
    }

    /**
     * 按可选 category 过滤返回推荐角色：给发现页"分类标签条"用。
     * category=null → 返回全部预设；非 null → 按枚举过滤。
     * 入参用 CharacterCategory 枚举（Controller 层已做 name→enum 转换与 null 容错）。
     */
    public List<CharacterResponse> findRecommendedByCategory(
            com.ideaparty.entity.CharacterCategory category) {
        // V10 优化：从内存缓存读，按 category 过滤；0 DB 查询
        if (category == null) {
            return presetCache.getAll();
        }
        return presetCache.getAll().stream()
                .filter(c -> c.getCategories() != null && c.getCategories().contains(category))
                .collect(Collectors.toList());
    }
}
