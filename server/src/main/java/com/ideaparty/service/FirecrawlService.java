package com.ideaparty.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;

import java.util.Map;
import java.util.HashMap;

/**
 * Firecrawl web scraping service for character information.
 * Uses Firecrawl API to scrape web content about characters.
 */
@Service
@Slf4j
public class FirecrawlService {

    private final String apiKey;
    private static final String FIRECRAWL_URL_V1 = "https://api.firecrawl.dev/v0/scrape";
    private static final String FIRECRAWL_URL_V2 = "https://api.firecrawl.dev/v2/scrape";
    private static final String FIRECRAWL_SEARCH_URL = "https://api.firecrawl.dev/v2/search";

    public FirecrawlService() {
        // Check both environment variable and system property (set by dotenv)
        String envKey = System.getenv("FIRECRAWL_API_KEY");
        String propKey = System.getProperty("FIRECRAWL_API_KEY");
        this.apiKey = envKey != null ? envKey : propKey;
        log.info("[DEBUG] FirecrawlService initialized with API key: {}", apiKey != null ? "present" : "missing");
    }

    /**
     * Scrape web content for a character name.
     * Returns scraped markdown content about the character.
     */
    public String scrape(String characterName) {
        if (apiKey == null || apiKey.isBlank() || apiKey.equals("your-firecrawl-api-key-here")) {
            log.warn("[DEBUG] Firecrawl API key not configured, using fallback");
            return getFallbackContent(characterName);
        }

        try {
            // First, search for the correct URL to handle ambiguous names like "Messi"
            String correctUrl = searchForCharacter(characterName);
            if (correctUrl != null) {
                log.info("[DEBUG] Found URL via search: {}", correctUrl);
                String result = scrapeUrl(correctUrl);
                if (result != null && !isNoArticlePage(result)) {
                    return result;
                }
                log.warn("[DEBUG] Search returned no-article page, trying direct scrape");
            }
            // Fallback to direct Wikipedia URL
            String directResult = scrapeFromFirecrawl(characterName);
            if (directResult != null && !isNoArticlePage(directResult)) {
                return directResult;
            }
            // If direct Wikipedia also returns no-article page, use fallback
            log.warn("[DEBUG] Direct Wikipedia scrape returned no-article page, using fallback");
            return getFallbackContent(characterName);
        } catch (Exception e) {
            log.error("[DEBUG] Firecrawl scrape failed: {}", e.getMessage());
            return getFallbackContent(characterName);
        }
    }

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
     * Search for a character's Wikipedia page URL.
     * Handles ambiguous names like "Messi" by finding the most relevant page.
     */
    private String searchForCharacter(String characterName) {
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        // Determine search query based on character name language
        boolean isChinese = characterName.matches("[\\u4e00-\\u9fa5]+");

        String[] searchQueries;
        if (isChinese) {
            // For Chinese names, try multiple disambiguation queries
            searchQueries = new String[]{
                characterName + " footballer",
                characterName + " 足球",
                characterName + " Wikipedia"
            };
        } else {
            // For English names, try basic + disambiguation
            searchQueries = new String[]{
                characterName,
                characterName + " footballer"
            };
        }

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
                log.warn("[DEBUG] Firecrawl search '{}' failed: {}", searchQuery, e.getMessage());
            }
        }
        return null;
    }

    /**
     * Scrape a specific URL.
     */
    private String scrapeUrl(String url) {
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        try {
            // Try v2 endpoint
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
            if (result != null && result.length() > 100) return result;
        } catch (RestClientException e) {
            log.warn("[DEBUG] V2 scrape failed for URL {}: {}", url, e.getMessage());
        }

        // Fallback to v1
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
            log.error("[DEBUG] V1 scrape also failed for URL {}: {}", url, e.getMessage());
        }

        throw new RestClientException("Both scrape endpoints failed for URL: " + url);
    }

    private String scrapeFromFirecrawl(String characterName) {
        RestTemplate restTemplate = new RestTemplate();

        // Determine if name is Chinese and use appropriate Wikipedia
        boolean isChinese = characterName.matches("[\\u4e00-\\u9fa5]+");
        String wikipediaBase = isChinese ? "https://zh.wikipedia.org/wiki/" : "https://en.wikipedia.org/wiki/";
        String url = wikipediaBase + characterName.replace(" ", "_");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        log.info("[DEBUG] Scraping Wikipedia for: {}", characterName);

        try {
            // Try v2 endpoint first (new API format)
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

        // Fallback to v1 endpoint
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

        throw new RestClientException("Both Firecrawl endpoints failed");
    }

    private String parseResponse(ResponseEntity<Map> response) {
        if (response.getBody() != null && response.getBody().containsKey("data")) {
            Object dataObj = response.getBody().get("data");
            if (dataObj instanceof Map) {
                Map<String, Object> data = (Map<String, Object>) dataObj;
                if (data.containsKey("markdown")) {
                    String markdown = (String) data.get("markdown");
                    log.info("[DEBUG] Scraped content length: {}", markdown.length());
                    return markdown;
                }
            }
        }

        log.warn("[DEBUG] No markdown content from Firecrawl, using fallback");
        return null;
    }

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
            default -> String.format("""
                %s is a notable historical/cultural figure known for their significant contributions and influence.
                This character has a rich background, unique personality traits, and distinctive qualities that make them memorable.
                """, name);
        };
    }
}
