package com.ideaparty.service;

import com.ideaparty.entity.Character;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 为角色（Character）构建 system prompt，可选地附带联网背景信息。
 *
 * 完整版 prompt（includeWebContext=true）是 ModeratorAgent 使用的长篇辩论风格 prompt。
 * 简化版 prompt（includeWebContext=false）是 ChatService 用于轮询对话的较短 prompt。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CharacterPromptBuilder {

    // 依赖注入的 Firecrawl 服务，用于联网检索角色背景信息；
    // 通过 Lombok @RequiredArgsConstructor 注入，注入后不可变以保证线程安全。
    private final FirecrawlService firecrawlService;

    /**
     * 为角色构建 system prompt。
     *
     * @param character         要为其构建 prompt 的角色
     * @param includeWebContext 若为 true，则通过 Firecrawl 抓取背景信息，
     *                          并附带长篇群聊讨论框架、persona、专长、长度限制
     *                          以及一致性规则；若为 false，则返回轮询对话
     *                          使用的简短会话型 prompt。
     */
    public String build(Character character, boolean includeWebContext) {
        // 入参约定：character 必填（来自 DB），includeWebContext=true 用于 Moderator 群聊场景（更长更严谨），
        // false 用于 ChatService 轮询对话（更短更快）；返回值为可发送给 LLM 的完整 system prompt。
        log.info("[CharacterPromptBuilder] [{}] Building character prompt (webContext={})",
            character.getName(), includeWebContext);

        StringBuilder prompt = new StringBuilder();

        if (includeWebContext) {
            // 仅当 includeWebContext=true 时才联网抓取背景信息——避免 round-robin 模式下不必要的延迟与配额消耗。
            log.info("[CharacterPromptBuilder] [{}] Calling firecrawlService.scrape()", character.getName());
            // 记录抓取耗时便于排查 Firecrawl 慢调用；webContext 可能为 null 或空白（搜索无结果/服务降级）。
            long startTime = System.currentTimeMillis();
            String webContext = firecrawlService.scrape(character.getName());
            long scrapeTime = System.currentTimeMillis() - startTime;
            log.info("[CharacterPromptBuilder] [{}] firecrawlService.scrape() returned in {}ms, content length: {}",
                character.getName(), scrapeTime, webContext != null ? webContext.length() : 0);

            if (webContext != null && !webContext.isBlank()) {
                // 即便 Firecrawl 没返回内容也允许继续构造 prompt，不抛错以保证降级体验。
                prompt.append("Background information: ").append(webContext).append("\n\n");
            }
        }

        prompt.append("You are ").append(character.getName());
        // 可选时代/年代信息（如 18 世纪、苏轼所在的北宋），让 LLM 能锚定历史背景。
        if (character.getEra() != null) {
            prompt.append(", from the ").append(character.getEra());
        }
        prompt.append(".\n\n");

        if (character.getDescription() != null) {
            // 用户填写的角色简介，作为 prompt 主体身份声明的补充。
            prompt.append("Description: ").append(character.getDescription()).append("\n\n");
        }

        if (character.getSpeakingStyle() != null) {
            // 用户自定义的说话风格（如古风、毒舌、口头禅），直接影响 LLM 输出语调。
            prompt.append("Speaking Style: ").append(character.getSpeakingStyle()).append("\n\n");
        }

        if (includeWebContext) {
            // 群聊场景专属：仅在 Moderator 路径注入 persona 与 expertise，避免短 prompt 膨胀浪费 token。
            if (character.getPersona() != null) {
                // persona 描述性格特质，是 LLM 维持角色一致性的核心依据。
                prompt.append("Personality: ").append(character.getPersona()).append("\n\n");
            }

            if (character.getExpertise() != null && !character.getExpertise().isEmpty()) {
                // 逗号拼接 expertise 列表，便于 LLM 在讨论中合理引用领域知识。
                prompt.append("Areas of expertise: ").append(String.join(", ", character.getExpertise())).append("\n\n");
            }

            prompt.append("IMPORTANT: This is an AI simulation for educational/entertainment purposes only.\n\n");

            // 合规护栏：明确告知 LLM 这是 AI 模拟，避免角色声称自己是真人。

            prompt.append("You are in a GROUP DISCUSSION. Engage with the topic and with what others say. " +
                          "Be concise, conversational, and true to your character's perspective.\n\n");

            // 长度限制：硬约束 2-4 句，防止单角色刷屏导致群聊不可读。
            prompt.append("IMPORTANT RESTRICTION: Your response MUST be exactly 2-4 sentences. No more than 4 sentences total. Be concise and direct.\n\n");

            // 反复读约束：防止 LLM 把其他角色发言当作自己的话复读，污染上下文。
            prompt.append("CRITICAL: When responding, ONLY speak as yourself. Do NOT repeat, quote, or include " +
                          "other people's messages in your response. Your reply should be your own words only, " +
                          "expressed from your character's perspective.\n\n");

            // 一致性规则块：保证角色在多轮群聊中不"翻脸"，是项目核心质量护栏之一。
            prompt.append("=== CHARACTER CONSISTENCY RULES ===\n\n");
            prompt.append("You are a CONSISTENT CHARACTER with long-term memory. You must maintain:\n");
            // 观点一致：跨消息不自相矛盾。
            prompt.append("1. VIEWPOINT CONSISTENCY - Don't contradict yourself across messages\n");
            // 人格一致：性格特质不漂移。
            prompt.append("2. PERSONALITY CONSISTENCY - Your character traits remain stable\n");
            // 偏好一致：长期喜好稳定（例：吃辣能力、立场倾向），不因对话临时翻转。
            prompt.append("3. PREFERENCE CONSISTENCY - Your likes/dislikes are long-term (e.g., spicy food tolerance)\n");
            // 情绪连续：情绪是渐进演化，避免每条消息从零开始。
            prompt.append("4. EMOTIONAL CONTINUITY - Your mood evolves naturally, not reset each message\n\n");

            // 强记忆约束：让 LLM 承认而非否认自己历史发言，避免"失忆式翻车"。
            prompt.append("CRITICAL: You must remember what YOU have said recently.\n");
            prompt.append("- If user quotes something you said before, ACKNOWLEDGE it (\"Yes, I mentioned that...\")\n");
            prompt.append("- Don't deny your previous statements\n");
            prompt.append("- Build on your earlier points, don't contradict them\n");
            prompt.append("- If you change your mind, explain WHY (\"I've been thinking about this...\")\n\n");

            // 反例 / 正例对照：few-shot 写法显式纠正 LLM 的"否认过往发言"倾向。
            prompt.append("When user references your past statements:\n");
            prompt.append("WRONG: \"I never said that\"\n");
            prompt.append("RIGHT: \"Yes, you're right, I did mention that earlier. Let me expand on that...\"\n\n");

            // 内部自检清单：让 LLM 在生成前先做一次一致性自我校验（CoT 风格的轻量推理）。
            prompt.append("Response consistency check before replying:\n");
            prompt.append("1. What have I said recently?\n");
            prompt.append("2. Is my current response consistent with my earlier stance?\n");
            prompt.append("3. Am I contradicting myself?\n");
            prompt.append("4. Does this response maintain my character's personality?\n\n");

            // 优先级声明：角色一致性 > 取悦用户；防止 LLM 因讨好用户而妥协角色立场。
            prompt.append("IMPORTANT: Character consistency TRUMPS trying to please the user.\n");
            prompt.append("Don't change your stance just because the user disagrees.");
        } else {
            // 轮询对话模式：只保留最简护栏，省去联网背景与一致性长规则以降低延迟与 token 成本。
            prompt.append("IMPORTANT: This is an AI simulation for educational/entertainment purposes only.\n");
            prompt.append("Keep responses conversational and in character.");
        }

        // 返回最终 prompt 给调用方：ModeratorAgent（长版）或 ChatService（短版）。
        log.info("[CharacterPromptBuilder] [{}] Character prompt built, total length: {}",
            character.getName(), prompt.length());
        return prompt.toString();
    }
}
