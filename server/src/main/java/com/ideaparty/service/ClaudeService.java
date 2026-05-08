package com.ideaparty.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ideaparty.entity.Character;
import com.ideaparty.repository.CharacterRepository;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ClaudeService {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final CharacterRepository characterRepository;

    private static final String CLAUDE_API_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL = "claude-sonnet-4-20250514";

    /**
     * AI response wrapper that carries character info with each chunk.
     */
    public record AIResponse(String chunk, String characterId, String characterName, boolean isComplete) {}

    public ClaudeService(CharacterRepository characterRepository) {
        this.characterRepository = characterRepository;
        this.objectMapper = new ObjectMapper();
        this.webClient = WebClient.builder()
            .baseUrl(CLAUDE_API_URL)
            .defaultHeader("x-api-key", System.getenv("ANTHROPIC_API_KEY"))
            .defaultHeader("anthropic-version", "2023-06-01")
            .build();
    }

    /**
     * Selects the responding character based on expertise matching and turn logic.
     * Currently selects the first character; can be extended for smarter routing.
     */
    private Character selectRespondingCharacter(List<Character> characters, String userMessage) {
        if (characters.isEmpty()) {
            return null;
        }
        // TODO: Implement smarter routing based on expertise matching
        return characters.get(0);
    }

    public String buildSystemPrompt(Character character) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are playing the role of ").append(character.getName()).append(".\n\n");
        prompt.append("Era: ").append(character.getEra()).append("\n\n");
        prompt.append("Description: ").append(character.getDescription()).append("\n\n");
        prompt.append("Speaking Style: ").append(character.getSpeakingStyle()).append("\n\n");
        prompt.append("Personality: ").append(character.getPersona()).append("\n\n");
        prompt.append("Your areas of expertise include: ");
        prompt.append(String.join(", ", character.getExpertise()));
        prompt.append(".\n\n");
        prompt.append("Respond in character as ").append(character.getName()).append(" would speak, ");
        prompt.append("using your unique speaking style and drawing from your expertise. ");
        prompt.append("Keep responses concise and conversational, as if in a group chat.");
        return prompt.toString();
    }

    public Flux<AIResponse> streamMessage(String roomId, List<Character> characters, String userMessage) {
        Character character = selectRespondingCharacter(characters, userMessage);
        if (character == null) {
            return Flux.just(new AIResponse("No characters available to respond.", null, null, true));
        }

        String systemPrompt = buildSystemPrompt(character);
        String conversationContext = buildConversationContext(characters, userMessage);

        Map<String, Object> requestBody = Map.of(
            "model", MODEL,
            "max_tokens", 1024,
            "stream", true,
            "system", systemPrompt,
            "messages", List.of(
                Map.of("role", "user", "content", conversationContext)
            )
        );

        final String characterId = character.getId();
        final String characterName = character.getName();

        return webClient.post()
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .retrieve()
            .bodyToFlux(String.class)
            .filter(line -> line.startsWith("data: "))
            .map(line -> line.substring(6))
            .filter(data -> !data.equals("[DONE]"))
            .map(this::extractTextFromEvent)
            .filter(chunk -> !chunk.isEmpty())
            .map(chunk -> new AIResponse(chunk, characterId, characterName, false))
            .concatWith(Flux.just(new AIResponse("", characterId, characterName, true)));
    }

    private String buildConversationContext(List<Character> characters, String userMessage) {
        String characterNames = characters.stream()
            .map(Character::getName)
            .collect(Collectors.joining(", "));

        return String.format(
            "The user said: \"%s\"\n\n" +
            "In this conversation, you are joined by: %s\n\n" +
            "Please respond to the user's message as your character.",
            userMessage, characterNames
        );
    }

    private String extractTextFromEvent(String eventData) {
        try {
            JsonNode event = objectMapper.readTree(eventData);
            if ("content_block_delta".equals(event.get("type").asText())) {
                JsonNode delta = event.get("delta");
                if (delta != null && "text_delta".equals(delta.get("type").asText())) {
                    return delta.get("text").asText();
                }
            }
        } catch (Exception e) {
            // Ignore parsing errors
        }
        return "";
    }
}
