package com.ideaparty.service;

import com.ideaparty.entity.Character;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Builds the system prompt for a Character, optionally enriched with web context.
 *
 * The full prompt (includeWebContext=true) is the long, debate-style prompt
 * used by ModeratorAgent. The simple prompt (includeWebContext=false) is the
 * shorter prompt used by ChatService for round-robin dialogue.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CharacterPromptBuilder {

    private final FirecrawlService firecrawlService;

    /**
     * Build the system prompt for a character.
     *
     * @param character the character to build a prompt for
     * @param includeWebContext if true, fetch background info from Firecrawl and
     *                          include the long group-discussion framing,
     *                          persona, expertise, length restriction, and
     *                          consistency rules. If false, return the short
     *                          conversational prompt used by round-robin chat.
     */
    public String build(Character character, boolean includeWebContext) {
        log.info("[CharacterPromptBuilder] [{}] Building character prompt (webContext={})",
            character.getName(), includeWebContext);

        StringBuilder prompt = new StringBuilder();

        if (includeWebContext) {
            log.info("[CharacterPromptBuilder] [{}] Calling firecrawlService.scrape()", character.getName());
            long startTime = System.currentTimeMillis();
            String webContext = firecrawlService.scrape(character.getName());
            long scrapeTime = System.currentTimeMillis() - startTime;
            log.info("[CharacterPromptBuilder] [{}] firecrawlService.scrape() returned in {}ms, content length: {}",
                character.getName(), scrapeTime, webContext != null ? webContext.length() : 0);

            if (webContext != null && !webContext.isBlank()) {
                prompt.append("Background information: ").append(webContext).append("\n\n");
            }
        }

        prompt.append("You are ").append(character.getName());
        if (character.getEra() != null) {
            prompt.append(", from the ").append(character.getEra());
        }
        prompt.append(".\n\n");

        if (character.getDescription() != null) {
            prompt.append("Description: ").append(character.getDescription()).append("\n\n");
        }

        if (character.getSpeakingStyle() != null) {
            prompt.append("Speaking Style: ").append(character.getSpeakingStyle()).append("\n\n");
        }

        if (includeWebContext) {
            if (character.getPersona() != null) {
                prompt.append("Personality: ").append(character.getPersona()).append("\n\n");
            }

            if (character.getExpertise() != null && !character.getExpertise().isEmpty()) {
                prompt.append("Areas of expertise: ").append(String.join(", ", character.getExpertise())).append("\n\n");
            }

            prompt.append("IMPORTANT: This is an AI simulation for educational/entertainment purposes only.\n\n");

            prompt.append("You are in a GROUP DISCUSSION. Engage with the topic and with what others say. " +
                          "Be concise, conversational, and true to your character's perspective.\n\n");

            prompt.append("IMPORTANT RESTRICTION: Your response MUST be exactly 2-4 sentences. No more than 4 sentences total. Be concise and direct.\n\n");

            prompt.append("CRITICAL: When responding, ONLY speak as yourself. Do NOT repeat, quote, or include " +
                          "other people's messages in your response. Your reply should be your own words only, " +
                          "expressed from your character's perspective.\n\n");

            // Add character consistency rules
            prompt.append("=== CHARACTER CONSISTENCY RULES ===\n\n");
            prompt.append("You are a CONSISTENT CHARACTER with long-term memory. You must maintain:\n");
            prompt.append("1. VIEWPOINT CONSISTENCY - Don't contradict yourself across messages\n");
            prompt.append("2. PERSONALITY CONSISTENCY - Your character traits remain stable\n");
            prompt.append("3. PREFERENCE CONSISTENCY - Your likes/dislikes are long-term (e.g., spicy food tolerance)\n");
            prompt.append("4. EMOTIONAL CONTINUITY - Your mood evolves naturally, not reset each message\n\n");

            prompt.append("CRITICAL: You must remember what YOU have said recently.\n");
            prompt.append("- If user quotes something you said before, ACKNOWLEDGE it (\"Yes, I mentioned that...\")\n");
            prompt.append("- Don't deny your previous statements\n");
            prompt.append("- Build on your earlier points, don't contradict them\n");
            prompt.append("- If you change your mind, explain WHY (\"I've been thinking about this...\")\n\n");

            prompt.append("When user references your past statements:\n");
            prompt.append("WRONG: \"I never said that\"\n");
            prompt.append("RIGHT: \"Yes, you're right, I did mention that earlier. Let me expand on that...\"\n\n");

            prompt.append("Response consistency check before replying:\n");
            prompt.append("1. What have I said recently?\n");
            prompt.append("2. Is my current response consistent with my earlier stance?\n");
            prompt.append("3. Am I contradicting myself?\n");
            prompt.append("4. Does this response maintain my character's personality?\n\n");

            prompt.append("IMPORTANT: Character consistency TRUMPS trying to please the user.\n");
            prompt.append("Don't change your stance just because the user disagrees.");
        } else {
            prompt.append("IMPORTANT: This is an AI simulation for educational/entertainment purposes only.\n");
            prompt.append("Keep responses conversational and in character.");
        }

        log.info("[CharacterPromptBuilder] [{}] Character prompt built, total length: {}",
            character.getName(), prompt.length());
        return prompt.toString();
    }
}
