package com.ideaparty.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;

import java.util.Map;
import java.util.HashMap;

/**
 * 角色信息抓取服务：封装 Firecrawl REST 调用，承担"用户输入角色名 → 返回结构化正文"的职责。
 * 之所以独立成 Service：一是为了把 API Key 隔离在后端（CLAUDE.md 安全约束）；
 * 二是把多策略（搜索消歧 → 直链 Wikipedia → 本地 fallback）集中在一处，便于上层 CharacterService 复用。
 *
 * <p>原 Javadoc: "用于角色信息的 Firecrawl 网页抓取服务。使用 Firecrawl API 抓取与角色相关的网页内容。"
 */
@Service
@Slf4j
public class FirecrawlService {

    // Firecrawl 鉴权密钥；按系统属性 > 环境变量优先级解析，避免硬编码到代码或配置文件
    private final String apiKey;
    // 注入的 HTTP 客户端，用于调用 Firecrawl REST 接口；由调用方传入以便测试时替换
    private final RestTemplate restTemplate;
    // v1 旧版抓取端点，作为 v2 失败时的兼容兜底（Firecrawl 仍在过渡期保留 v0 API）
    private static final String FIRECRAWL_URL_V1 = "https://api.firecrawl.dev/v0/scrape";
    // v2 当前推荐端点，结构化响应更稳定，是首选抓取入口
    private static final String FIRECRAWL_URL_V2 = "https://api.firecrawl.dev/v2/scrape";
    // 搜索端点，用于在直接抓取前先解析歧义名（如"Messi"）对应的真实 Wikipedia 链接
    private static final String FIRECRAWL_SEARCH_URL = "https://api.firecrawl.dev/v2/search";

    // Spring 注入入口：接收共享 RestTemplate，并按系统属性 > 环境变量顺序解析 API Key；
    // 缺失 Key 时仅警告不抛错，保证未配置时仍可降级运行
    public FirecrawlService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
        // 同时检查系统属性和环境变量（系统属性优先级更高）
        String propKey = System.getProperty("FIRECRAWL_API_KEY");
        String envKey = System.getenv("FIRECRAWL_API_KEY");
        this.apiKey = propKey != null ? propKey : envKey;
        // 提示运维：未配置 Key 时不要让请求报错，调用链路会自动切换到本地 fallback 内容
        if (this.apiKey == null || this.apiKey.isBlank()) {
            log.warn("[Firecrawl] FIRECRAWL_API_KEY not configured, web scraping will use fallback content");
        } else {
            // 仅记录"已配置"，避免密钥明文进入日志
            log.info("[DEBUG] FirecrawlService initialized with API key: present");
        }
    }

    /**
     * 抓取与某角色名相关的网页内容。
     * 返回关于该角色的 markdown 内容。
     *
     * <p>对上层（CharacterService）的契约：
     * 入参为用户原始输入的角色名（任意语言、可能含歧义）；
     * 返回值保证非 null，要么是 Firecrawl 抓到的 markdown，要么是 fallback 文案；
     * 无副作用，不抛异常（网络异常会被内部捕获并降级）。
     */
    public String scrape(String characterName) {
        // 哨兵判断：未配置 Key 或仍为占位符时直接走本地 fallback，避免向 Firecrawl 发送无效请求
        if (apiKey == null || apiKey.isBlank() || apiKey.equals("your-firecrawl-api-key-here")) {
            log.warn("[DEBUG] Firecrawl API key not configured, using fallback");
            return getFallbackContent(characterName);
        }

        try {
            // 先搜索正确的 URL，以处理像 "Messi" 这类有歧义的名字
            // 优先用搜索消歧：对"Messi"这种多义词，先解析出真正的人物页面 URL，避免抓到消歧义页
            String correctUrl = searchForCharacter(characterName);
            if (correctUrl != null) {
                log.info("[DEBUG] Found URL via search: {}", correctUrl);
                String result = scrapeUrl(correctUrl);
                if (result != null && !isNoArticlePage(result)) {
                    return result;
                }
                // 即便 URL 来自搜索，仍要二次校验正文不是消歧义页
                log.warn("[DEBUG] Search returned no-article page, trying direct scrape");
            }
            // 退回到直接抓取 Wikipedia URL
            // 搜索失败或搜到的是消歧义页时，退回到按 Wikipedia 直链抓取（中英文自动分流）
            String directResult = scrapeFromFirecrawl(characterName);
            if (directResult != null && !isNoArticlePage(directResult)) {
                return directResult;
            }
            // 如果直接抓取 Wikipedia 也返回无条目页，则使用兜底内容
            // 兜底兜底：Wikipedia 也没收录该角色时，使用内置人设文本，保证前端仍有内容可用
            log.warn("[DEBUG] Direct Wikipedia scrape returned no-article page, using fallback");
            return getFallbackContent(characterName);
        } catch (Exception e) {
            // 任何未预期的异常都不向上抛，改为静默降级到 fallback，避免阻塞角色创建流程
            log.error("[DEBUG] Firecrawl scrape failed: {}", e.getMessage());
            return getFallbackContent(characterName);
        }
    }

    // 判定抓回的 markdown 是否是消歧义页/无条目页：Wikipedia 在无匹配词条时会返回这类导航页，
    // 中英文都覆盖（包括"消歧义""可以指："等本地化文案），避免把它当成人物简介喂给 LLM
    private boolean isNoArticlePage(String content) {
        if (content == null) return true;
        return content.contains("Wikipedia does not have an article")
            || content.contains("does not have an article")
            || content.contains("may refer to:")
            || content.contains("may refer to\n")
            || content.contains("消歧义")
            || content.contains("disambiguation")
            || content.contains("Disambig_gray.svg")
            || content.contains("可以指：");
    }

    /**
     * 搜索某角色的 Wikipedia 页面 URL。
     * 通过找到最相关的页面来处理像 "Messi" 这类有歧义的名字。
     *
     * <p>返回值：找到则返回首个 wikipedia.org URL，否则返回 null；纯工具方法，不抛异常。
     */
    // 用 Firecrawl 搜索接口解析角色名对应的 Wikipedia URL，主要解决同名歧义问题；
    // 入参为用户输入的角色名（任意语言），返回首个匹配到的 wikipedia.org 链接，失败返回 null
    private String searchForCharacter(String characterName) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        // 根据角色名语种决定搜索查询策略
        // 按名字是否纯中文分流查询策略：英文名走通用补全，中文名需要更强的上下文（如"足球"）才能命中人物页
        boolean isChinese = characterName.matches("[\\u4e00-\\u9fa5]+");

        String[] searchQueries;
        if (isChinese) {
            // 对中文名尝试多条消歧查询
            // 中文名仅靠原名搜索大概率命中古籍/成语，所以加上领域关键词与 Wikipedia 兜底
            searchQueries = new String[]{
                characterName + " footballer",
                characterName + " 足球",
                characterName + " Wikipedia"
            };
        } else {
            // 对英文名先按原名搜索，再追加"footballer"领域词处理"Messi"这类重名
            searchQueries = new String[]{
                characterName,
                characterName + " footballer"
            };
        }

        // 顺序尝试多条查询词：任一命中 wikipedia.org 链接即立即返回，避免对同一个角色做重复抓取
        for (String searchQuery : searchQueries) {
            try {
                Map<String, Object> body = new HashMap<>();
                body.put("query", searchQuery);
                body.put("limit", 5);

                HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

                ResponseEntity<Map> response = restTemplate.exchange(
                        FIRECRAWL_SEARCH_URL,
                        HttpMethod.POST,
                        request,
                        Map.class
                );

                if (response.getBody() != null) {
                    Object data = response.getBody().get("data");
                    if (data instanceof java.util.List) {
                        java.util.List<?> results = (java.util.List<?>) data;
                        // 只认 wikipedia.org 域名：其它站点结构差异大，跳过可减少后续解析异常
                        for (Object item : results) {
                            if (item instanceof Map) {
                                Map<String, Object> result = (Map<String, Object>) item;
                                String url = (String) result.get("url");
                                if (url != null && url.contains("wikipedia.org")) {
                                    log.info("[DEBUG] Search '{}' found Wikipedia URL: {}", searchQuery, url);
                                    return url;
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // 单条查询失败不中断整体流程，下一条查询词仍可成功
                log.warn("[DEBUG] Firecrawl search '{}' failed: {}", searchQuery, e.getMessage());
            }
        }
        // 全部查询都未命中 Wikipedia 链接，由调用方决定后续是否直链抓取或 fallback
        return null;
    }

    /**
     * 抓取指定 URL。
     *
     * <p>入参为已知的完整 URL（通常是 Wikipedia 链接）；
     * 返回正文 markdown；两个端点都失败时抛 RestClientException，由 scrape() 兜底。
     */
    // 抓取指定 URL 的正文 markdown：先试 v2，v2 内容过短或失败时回退 v1；
    // 两个端点都失败时抛出异常，由上层 scrape() 捕获并降级到 fallback
    private String scrapeUrl(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        try {
            // 先尝试 v2 端点
            // v2 用顶层 onlyMainContent 字段（Firecrawl 新协议），优先尝试
            Map<String, Object> bodyV2 = new HashMap<>();
            bodyV2.put("url", url);
            bodyV2.put("onlyMainContent", true);

            HttpEntity<Map<String, Object>> requestV2 = new HttpEntity<>(bodyV2, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    FIRECRAWL_URL_V2,
                    HttpMethod.POST,
                    requestV2,
                    Map.class
            );

            String result = parseResponse(response);
            // 经验值：低于 100 字符通常意味着只抓到了导航/标题，正文尚未取到，触发 v1 重试
            if (result != null && result.length() > 100) return result;
        } catch (RestClientException e) {
            log.warn("[DEBUG] V2 scrape failed for URL {}: {}", url, e.getMessage());
        }

        // 回退到 v1
        try {
            // v1 旧协议用 pageOptions 嵌套结构，部分账号/区域仍只能走 v1 才能拿到内容
            Map<String, Object> bodyV1 = new HashMap<>();
            bodyV1.put("url", url);
            bodyV1.put("pageOptions", Map.of("onlyMainContent", true));

            HttpEntity<Map<String, Object>> requestV1 = new HttpEntity<>(bodyV1, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    FIRECRAWL_URL_V1,
                    HttpMethod.POST,
                    requestV1,
                    Map.class
            );

            String result = parseResponse(response);
            if (result != null) return result;
        } catch (RestClientException e) {
            log.error("[DEBUG] V1 scrape also failed for URL {}: {}", url, e.getMessage());
        }

        // 向上抛异常让 scrape() 统一决定是否走 fallback，避免在工具方法里静默吞错
        throw new RestClientException("Both scrape endpoints failed for URL: " + url);
    }

    /**
     * 按 Wikipedia 命名规则直接抓取角色页，作为搜索接口失败时的后备方案。
     *
     * <p>入参为用户输入的角色名（已用于消歧尝试但未找到合适 URL）；
     * 命中 Wikipedia 词条返回 markdown，失败抛 RestClientException；
     * 副作用：每次调用会向 Firecrawl 发起 1~2 次 HTTP 请求。
     */
    // 在搜索接口未命中时，直接按 Wikipedia 命名规则拼 URL 抓取，避免依赖第三方搜索质量；
    // 仍保持 v2 优先 / v1 兜底策略，与 scrapeUrl() 一致以便上层统一处理
    private String scrapeFromFirecrawl(String characterName) {
        // 判断名字是否为中文，以便使用对应的 Wikipedia
        // 根据角色名语种分流到中英文 Wikipedia：中文角色直接抓 zh.wikipedia 通常正文更全
        boolean isChinese = characterName.matches("[\\u4e00-\\u9fa5]+");
        String wikipediaBase = isChinese ? "https://zh.wikipedia.org/wiki/" : "https://en.wikipedia.org/wiki/";
        // Wikipedia URL 用下划线代替空格，这是 MediaWiki 的固定路由规则
        String url = wikipediaBase + characterName.replace(" ", "_");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        log.info("[DEBUG] Scraping Wikipedia for: {}", characterName);

        try {
            // 先尝试 v2 端点（新 API 格式）
            Map<String, Object> bodyV2 = new HashMap<>();
            bodyV2.put("url", url);
            bodyV2.put("onlyMainContent", true);

            HttpEntity<Map<String, Object>> requestV2 = new HttpEntity<>(bodyV2, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    FIRECRAWL_URL_V2,
                    HttpMethod.POST,
                    requestV2,
                    Map.class
            );

            String result = parseResponse(response);
            if (result != null) return result;
        } catch (RestClientException e) {
            log.warn("[DEBUG] V2 endpoint failed: {}", e.getMessage());
        }

        // 回退到 v1 端点
        try {
            Map<String, Object> bodyV1 = new HashMap<>();
            bodyV1.put("url", url);
            bodyV1.put("pageOptions", Map.of("onlyMainContent", true));

            HttpEntity<Map<String, Object>> requestV1 = new HttpEntity<>(bodyV1, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    FIRECRAWL_URL_V1,
                    HttpMethod.POST,
                    requestV1,
                    Map.class
            );

            String result = parseResponse(response);
            if (result != null) return result;
        } catch (RestClientException e) {
            log.error("[DEBUG] V1 endpoint failed: {}", e.getMessage());
        }

        // 抛出后由 scrape() 捕获并切到 fallback 内容
        throw new RestClientException("Both Firecrawl endpoints failed");
    }

    // 从 Firecrawl 响应里安全提取 markdown 字段；v1/v2 都把正文塞在 data.markdown，结构稳定；
    // 任意一层结构缺失都返回 null，由调用方继续尝试兜底，避免 NPE
    private String parseResponse(ResponseEntity<Map> response) {
        if (response.getBody() != null && response.getBody().containsKey("data")) {
            Object dataObj = response.getBody().get("data");
            if (dataObj instanceof Map) {
                Map<String, Object> data = (Map<String, Object>) dataObj;
                if (data.containsKey("markdown")) {
                    String markdown = (String) data.get("markdown");
                    // 记录正文长度便于排查"抓到了但太短"这类问题
                    log.info("[DEBUG] Scraped content length: {}", markdown.length());
                    return markdown;
                }
            }
        }

        log.warn("[DEBUG] No markdown content from Firecrawl, using fallback");
        return null;
    }

    // 本地人设字典：Firecrawl 不可用或 Wikipedia 无收录时的兜底来源；
    // 覆盖 demo 场景常用角色，中英文同义词都支持，未命中则给出通用模板，保证前端不会拿到空字符串
    private String getFallbackContent(String name) {
        return switch (name.toLowerCase()) {
            case "william shakespeare", "shakespeare" -> """
                William Shakespeare was an English playwright and poet, widely regarded as the greatest writer in the English language.
                He wrote approximately 39 plays, 154 sonnets, and several poems. His works continue to influence literature and drama worldwide.
                His plays are known for their profound exploration of human nature, ambition, love, jealousy, and tragedy.
                Shakespeare invented thousands of words and crafted phrases still used today like "break the ice", "wild goose chase", and "heart of gold".
                """;
            case "albert einstein", "einstein" -> """
                Albert Einstein was a German-born theoretical physicist who developed the theory of relativity, one of the two pillars of modern physics.
                His mass-energy equivalence formula E = mc² is famous worldwide. He received the Nobel Prize in Physics in 1921 for his explanation of the photoelectric effect.
                Einstein is known for his thought experiments, creative thinking, and ability to visualize complex concepts in simple ways.
                He was a passionate pacifist and civil rights advocate who believed imagination was more important than knowledge.
                """;
            case "cleopatra", "cleopatra vii" -> """
                Cleopatra VII was the last active ruler of the Ptolemaic Kingdom of Egypt.
                She was known for her political acumen, speaking multiple languages (including Egyptian, which previous Ptolemaic rulers had not learned), and her relationships with Julius Caesar and Mark Antony.
                She was a brilliant strategist who sought to restore Egypt's independence and glory through diplomacy and alliances.
                Cleopatra was highly educated, commanding in war, and used her intelligence and charm to navigate dangerous political waters.
                """;
            case "confucius" -> """
                Confucius was a Chinese philosopher and politician who emphasized personal and governmental morality, correctness of social relationships, justice, kindness, and sincerity.
                His teachings formed the foundation of East Asian culture and have been adopted by various societies beyond China.
                He believed in the power of example and moral persuasion over force and coercion in governance.
                Confucius taught that relationships form the foundation of all virtue - between ruler and subject, parent and child, husband and wife, and between friends.
                """;
            case "marie curie", "curie" -> """
                Marie Curie was a Polish-French physicist and chemist who conducted pioneering research on radioactivity.
                She was the first woman to win a Nobel Prize, and the only person to win the Nobel Prize in two different sciences (Physics and Chemistry).
                She discovered the elements polonium and radium, and her work laid the foundation for nuclear physics and cancer treatment.
                Curie was known for her relentless dedication, perseverance through adversity, and commitment to scientific truth over personal gain.
                """;
            case "socrates" -> """
                Socrates was an ancient Greek philosopher credited as the founder of Western philosophy.
                Unlike other philosophers who wrote treatises, Socrates taught through dialogue and questioning - the Socratic method.
                He believed the unexamined life is not worth living and that true wisdom comes from knowing how much one does not know.
                Socrates challenged people's assumptions and forced them to think critically about their beliefs and values.
                """;
            case "nikola tesla", "tesla" -> """
                Nikola Tesla was a Serbian-American inventor and electrical engineer who contributed to the design of the modern alternating current electricity supply system.
                He invented the Tesla coil, developed wireless transmission systems, and envisioned free energy for all.
                Tesla was known for his brilliant mind, eccentricity, and conflicts with Thomas Edison.
                He spoke with conviction about the future of electricity, wireless communication, and renewable energy.
                """;
            case "leonardo da vinci", "da vinci" -> """
                Leonardo da Vinci was an Italian polymath of the High Renaissance who excelled in painting, sculpture, architecture, science, and engineering.
                He painted the Mona Lisa and The Last Supper, and his notebooks contain detailed drawings of flying machines, tanks, and solar power concepts.
                Da Vinci exemplified the ideal of the "Renaissance man" - curious, inventive, and master of many disciplines.
                He observed nature closely, believing that art and science were interconnected.
                """;
            case "lionel messi", "messi", "梅西" -> """
                Lionel Messi is an Argentine professional footballer widely regarded as one of the greatest players in history.
                He spent the majority of his career at FC Barcelona, where he became the club's all-time top scorer and won numerous La Liga and Champions League titles.
                Messi is known for his incredible dribbling ability, vision, playmaking skills, and prolific goal-scoring.
                He has won the Ballon d'Or award multiple times and led Argentina to victory in the 2022 FIFA World Cup.
                Off the field, Messi is known for his humility, loyalty to his teammates, and charitable work through the Leo Messi Foundation.
                He speaks with quiet confidence and leads by example rather than with words.
                """;
            case "曹操", "cao cao" -> """
                Cao Cao was a warlord and poet of the late Eastern Han dynasty who founded the Wei state in the Three Kingdoms period.
                He was known for his strategic brilliance, political acumen, and pragmatic approach to statecraft.
                Cao Cao famously said "I would rather betray the world than let the world betray me" and "the world is at peace when the worthy rule."
                He was a patron of poets and scholars, and his own poetry reflected his ambition and view of himself as a unifying force.
                """;
            case "赵云", "zhao yun" -> """
                Zhao Yun was a legendary general of the Three Kingdoms era, serving under Liu Bei.
                He was known for his unwavering loyalty, courage in battle, and protective nature toward the innocent.
                Famous for his solo charge at the Battle of Chang Ban where he rescued Liu Bei's son and wife through enemy lines.
                Zhao Yun was considered a paragon of virtue and righteousness, rarely losing a battle and never compromising his principles.
                """;
            case "张飞", "zhang fei" -> """
                Zhang Fei was a famed warrior of the Three Kingdoms era, sworn brother of Liu Bei and Guan Yu.
                He was known for his fierce battle prowess, fearlessness in combat, and surprisingly artistic side (he was said to be well-versed in poetry and music).
                Though intimidating in appearance with his beard and booming voice, he had moments of surprising gentleness and loyalty.
                Zhang Fei valued brotherhood above all and fought with passionate intensity for his beliefs and allies.
                """;
            case "吕布", "lu bu" -> """
                Lu Bu was widely considered the greatest warrior of the Three Kingdoms era, unmatched in combat.
                He served under several masters throughout his life, with his loyalty shifting based on power dynamics.
                Famous for his skill with the halberd and his horse Red Hare, Lu Bu was defeated only when faced with combined forces.
                He was ambitious, proud of his martial prowess, and believed in strength as the ultimate arbiter of power.
                """;
            // 默认兜底文案：未在字典中收录的角色用通用模板，避免返回空字符串导致 LLM prompt 构造失败
            default -> String.format("""
                %s is a notable historical/cultural figure known for their significant contributions and influence.
                This character has a rich background, unique personality traits, and distinctive qualities that make them memorable.
                """, name);
        };
    }
}
