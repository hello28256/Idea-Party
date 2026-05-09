package com.ideaparty.service;

import com.ideaparty.entity.Character;
import com.ideaparty.entity.Message;
import com.ideaparty.repository.MessageRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Moderator Agent for intelligent speaker selection and response orchestration.
 * Replaces the simple round-robin logic with context-aware selection.
 *
 * Selection rules:
 * 1. Same character cannot speak more than 2 consecutive turns
 * 2. Characters who haven't spoken recently (30+ seconds) get priority
 * 3. When all characters have spoken consecutively, reset the tracking
 */
@Service
public class ModeratorAgent {

    private final AIService aiService;
    private final MessageRepository messageRepository;

    // Track the last speaking index for each character (by roomId)
    private final Map<String, Map<UUID, Integer>> characterLastSpokeIndex = new ConcurrentHashMap<>();

    // Track the last speaking time for each character (by roomId)
    private final Map<String, Map<UUID, Long>> characterLastSpokeTime = new ConcurrentHashMap<>();

    // Track consecutive speaking count for each character (by roomId)
    private final Map<String, Map<UUID, Integer>> characterConsecutiveCount = new ConcurrentHashMap<>();

    // Time threshold in seconds for "recently spoken" consideration
    private static final long IDLE_THRESHOLD_SECONDS = 30;

    // Maximum consecutive turns a character can speak
    private static final int MAX_CONSECUTIVE_TURNS = 2;

    public ModeratorAgent(AIService aiService, MessageRepository messageRepository) {
        this.aiService = aiService;
        this.messageRepository = messageRepository;
    }

    /**
     * Process a user message and orchestrate AI character responses.
     * Each character speaks once in a round, with intelligent ordering.
     *
     * @param roomId The room ID
     * @param userMessage The user's message
     * @param characters List of characters in the room
     * @param onThinking Callback for "thinking" state
     * @param onResponse Callback for each character's response
     */
    public void processMessage(String roomId, String userMessage, List<Character> characters,
                               Consumer<String> onThinking, Consumer<ResponseFragment> onResponse) {
        if (characters == null || characters.isEmpty()) {
            return;
        }

        // Get or initialize room tracking
        Map<UUID, Integer> lastIndex = characterLastSpokeIndex.computeIfAbsent(roomId, k -> new ConcurrentHashMap<>());
        Map<UUID, Long> lastTime = characterLastSpokeTime.computeIfAbsent(roomId, k -> new ConcurrentHashMap<>());
        Map<UUID, Integer> consecutiveCount = characterConsecutiveCount.computeIfAbsent(roomId, k -> new ConcurrentHashMap<>());

        // Build conversation context from recent messages
        String conversationContext = buildConversationContext(roomId, userMessage);

        // Process each character in order
        List<Character> speakingOrder = determineSpeakingOrder(characters, lastIndex, lastTime, consecutiveCount);

        for (Character character : speakingOrder) {
            UUID characterId = character.getId();

            // Notify thinking state
            onThinking.accept(character.getName());

            // Build character-specific prompt
            String characterPrompt = buildCharacterPrompt(character);

            // Use streaming response
            StringBuilder fullResponse = new StringBuilder();
            aiService.generateResponseStream(
                characterPrompt + "\n\n" + conversationContext,
                userMessage,
                // onChunk - each token as it arrives
                chunk -> {
                    fullResponse.append(chunk);
                },
                // onComplete - full response ready
                completeResponse -> {
                    // Update tracking
                    int currentIndex = lastIndex.getOrDefault(characterId, -1);
                    lastIndex.put(characterId, currentIndex + 1);
                    lastTime.put(characterId, System.currentTimeMillis());

                    int currentConsecutive = consecutiveCount.getOrDefault(characterId, 0);
                    consecutiveCount.put(characterId, currentConsecutive + 1);

                    // Send response fragment
                    ResponseFragment fragment = new ResponseFragment(
                        characterId.toString(),
                        character.getName(),
                        fullResponse.toString(),
                        true
                    );
                    onResponse.accept(fragment);
                },
                // onError
                error -> {
                    ResponseFragment errorFragment = new ResponseFragment(
                        characterId.toString(),
                        character.getName(),
                        "Sorry, an error occurred: " + error.getMessage(),
                        true
                    );
                    onResponse.accept(errorFragment);
                }
            );
        }
    }

    /**
     * Select the next speaker based on context-aware rules.
     * This is called internally during processMessage orchestration.
     */
    Character selectNextSpeaker(List<Character> characters, List<Message> recentMessages) {
        if (characters == null || characters.isEmpty()) {
            return null;
        }

        // Get room tracking - use a default room key since we don't have room context
        Map<UUID, Integer> lastIndex = new ConcurrentHashMap<>();
        Map<UUID, Long> lastTime = new ConcurrentHashMap<>();
        Map<UUID, Integer> consecutiveCount = new ConcurrentHashMap<>();

        // Try to find existing room tracking
        for (String roomKey : characterLastSpokeIndex.keySet()) {
            lastIndex = characterLastSpokeIndex.get(roomKey);
            break;
        }
        for (String roomKey : characterLastSpokeTime.keySet()) {
            lastTime = characterLastSpokeTime.get(roomKey);
            break;
        }
        for (String roomKey : characterConsecutiveCount.keySet()) {
            consecutiveCount = characterConsecutiveCount.get(roomKey);
            break;
        }

        return determineNextSpeaker(characters, lastIndex, lastTime, consecutiveCount);
    }

    /**
     * Internal method to determine the next speaker based on rules.
     */
    private Character determineNextSpeaker(List<Character> characters,
                                           Map<UUID, Integer> lastIndex,
                                           Map<UUID, Long> lastTime,
                                           Map<UUID, Integer> consecutiveCount) {
        long now = System.currentTimeMillis();

        // First, filter out characters who have spoken too many consecutive times
        List<Character> eligible = characters.stream()
            .filter(c -> consecutiveCount.getOrDefault(c.getId(), 0) < MAX_CONSECUTIVE_TURNS)
            .collect(Collectors.toList());

        // If all characters have hit the consecutive limit, reset and allow all
        if (eligible.isEmpty()) {
            consecutiveCount.clear();
            eligible = new ArrayList<>(characters);
        }

        // Score each eligible character
        Map<UUID, Double> scores = new HashMap<>();
        for (Character character : eligible) {
            UUID id = character.getId();
            double score = 0;

            // Time since last spoke (higher = more priority)
            Long lastSpoke = lastTime.get(id);
            if (lastSpoke != null) {
                long secondsSince = ChronoUnit.SECONDS.between(
                    Instant.ofEpochMilli(lastSpoke), Instant.ofEpochMilli(now));
                if (secondsSince >= IDLE_THRESHOLD_SECONDS) {
                    score += 50; // Significant boost for idle characters
                } else {
                    score += secondsSince;
                }
            } else {
                // Never spoke = highest priority
                score += 100;
            }

            // Lower consecutive count = higher priority
            score += (MAX_CONSECUTIVE_TURNS - consecutiveCount.getOrDefault(id, 0)) * 10;

            scores.put(id, score);
        }

        // Return character with highest score
        return eligible.stream()
            .max(Comparator.comparingDouble(c -> scores.getOrDefault(c.getId(), 0.0)))
            .orElse(characters.get(0));
    }

    /**
     * Determine speaking order for all characters in a round.
     */
    private List<Character> determineSpeakingOrder(List<Character> characters,
                                                  Map<UUID, Integer> lastIndex,
                                                  Map<UUID, Long> lastTime,
                                                  Map<UUID, Integer> consecutiveCount) {
        List<Character> result = new ArrayList<>();
        List<Character> remaining = new ArrayList<>(characters);

        // Check if we need to reset consecutive counts
        boolean allMaxed = remaining.stream()
            .allMatch(c -> consecutiveCount.getOrDefault(c.getId(), 0) >= MAX_CONSECUTIVE_TURNS);

        if (allMaxed) {
            consecutiveCount.clear();
        }

        // Round-robin with idle priority
        while (!remaining.isEmpty()) {
            Character next = determineNextSpeaker(remaining, lastIndex, lastTime, consecutiveCount);
            result.add(next);
            remaining.remove(next);
        }

        return result;
    }

    /**
     * Build the conversation context from recent messages.
     */
    private String buildConversationContext(String roomId, String userMessage) {
        StringBuilder context = new StringBuilder();
        context.append("Recent conversation:\n");

        try {
            UUID roomUuid = UUID.fromString(roomId);
            List<Message> recentMessages = messageRepository.findByRoomIdOrderByCreatedAtAsc(roomUuid);
            // Take last 20 messages
            if (recentMessages.size() > 20) {
                recentMessages = recentMessages.subList(recentMessages.size() - 20, recentMessages.size());
            }

            for (Message msg : recentMessages) {
                String sender;
                if (msg.getSenderType() == Message.SenderType.USER) {
                    sender = "User";
                } else if (msg.getCharacter() != null) {
                    sender = msg.getCharacter().getName();
                } else {
                    sender = "Unknown";
                }
                context.append("[").append(sender).append("]: ").append(msg.getContent()).append("\n");
            }
        } catch (Exception e) {
            // If we can't fetch messages, just continue with empty context
            context.append("(No recent messages available)\n");
        }

        context.append("\nUser's latest message: ").append(userMessage).append("\n");
        return context.toString();
    }

    /**
     * Build the system prompt for a character.
     */
    private String buildCharacterPrompt(Character character) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are playing the role of ").append(character.getName()).append(".\n\n");

        if (character.getEra() != null) {
            prompt.append("Era: ").append(character.getEra()).append("\n\n");
        }

        if (character.getDescription() != null) {
            prompt.append("Description: ").append(character.getDescription()).append("\n\n");
        }

        if (character.getSpeakingStyle() != null) {
            prompt.append("Speaking Style: ").append(character.getSpeakingStyle()).append("\n\n");
        }

        if (character.getPersona() != null) {
            prompt.append("Personality: ").append(character.getPersona()).append("\n\n");
        }

        if (character.getExpertise() != null && !character.getExpertise().isEmpty()) {
            prompt.append("Your areas of expertise include: ");
            prompt.append(String.join(", ", character.getExpertise()));
            prompt.append(".\n\n");
        }

        prompt.append("IMPORTANT DISCLAIMER: This is an AI simulation based on publicly available information, ");
        prompt.append("not the actual person. This is generated for educational and entertainment purposes only.\n\n");

        prompt.append("Respond in character as ").append(character.getName()).append(" would speak, ");
        prompt.append("using your unique speaking style and drawing from your expertise. ");
        prompt.append("Keep responses concise and conversational, as if in a group chat.");

        return prompt.toString();
    }

    /**
     * Response fragment from a character.
     */
    public static class ResponseFragment {
        private final String characterId;
        private final String characterName;
        private final String content;
        private final boolean isComplete;

        public ResponseFragment(String characterId, String characterName, String content, boolean isComplete) {
            this.characterId = characterId;
            this.characterName = characterName;
            this.content = content;
            this.isComplete = isComplete;
        }

        public String getCharacterId() { return characterId; }
        public String getCharacterName() { return characterName; }
        public String getContent() { return content; }
        public boolean isComplete() { return isComplete; }
    }
}
