package com.ideaparty.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ideaparty.dto.DiscussionPhase;
import com.ideaparty.dto.DiscussionStateEvent;
import com.ideaparty.dto.ModeratorMessage;
import com.ideaparty.entity.Character;
import com.ideaparty.entity.Message;
import com.ideaparty.entity.Room;
import com.ideaparty.entity.User;
import com.ideaparty.repository.CharacterRepository;
import com.ideaparty.repository.MessageRepository;
import com.ideaparty.repository.RoomRepository;
import com.ideaparty.repository.UserRepository;
import com.ideaparty.socket.ChatSocketHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Lazy;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.core.context.SecurityContext;
import dev.langchain4j.model.chat.ChatLanguageModel;
import java.nio.charset.StandardCharsets;

/**
 * Moderator Agent for multi-round group discussion orchestration.
 *
 * Discussion flow:
 * 1. Round 1: All characters respond to the user's question in PARALLEL
 * 2. Round 2+: Characters comment on each other's responses, forming a debate
 * 3. After MAX_ROUNDS, discussion ends
 */
@Slf4j
@Service
public class ModeratorAgent implements DisposableBean {

    private final AIService aiService;
    private final MessageRepository messageRepository;
    private final SettingsService settingsService;
    private final ChatSocketHandler chatSocketHandler;
    private final ResourceLoader resourceLoader;
    private final CharacterPromptBuilder characterPromptBuilder;
    private final CharacterRepository characterRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Maximum discussion rounds
    private static final int MAX_ROUNDS = 3;

    // Delay between rounds (milliseconds) to let responses propagate
    private static final long ROUND_DELAY_MS = 1500;

    // Executor for async operations - propagates SecurityContext to child threads
    private final ExecutorService executor;

    // Room-level futures tracking for cancellation
    private final ConcurrentHashMap<String, List<CompletableFuture<?>>> roomFutures = new ConcurrentHashMap<>();

    // Room-level current discussion state
    private final ConcurrentHashMap<String, DiscussionState> roomDiscussionState = new ConcurrentHashMap<>();

    // Discussion state class
    private static class DiscussionState {
        volatile boolean isRunning = false;
        volatile boolean paused = false;
        volatile int currentRound = 0;
        volatile int currentCharacterIndex = 0;
        volatile int maxRounds = 3;
        List<Character> characters = new CopyOnWriteArrayList<>();
        List<ResponseFragment> responses = new CopyOnWriteArrayList<>();
        String userMessage = "";
        String context = "";
        AtomicBoolean userTriggered = new AtomicBoolean(false);

        // New fields for state machine
        volatile DiscussionPhase phase = DiscussionPhase.IDLE;
        volatile boolean userInterjected = false;
        volatile int aiMessageCount = 0;
        volatile int maxAiMessagesPerRound = 3;
        List<Character> selectedCharacters = new CopyOnWriteArrayList<>();
        List<Character> pendingQueue = new CopyOnWriteArrayList<>();
        volatile String moderatorMessage = "";
        volatile String currentUserMessage = "";
        AtomicBoolean currentStreamCancelled = new AtomicBoolean(false);
        String userId = "";  // User ID for API key lookup
        List<String> userMessageHistory = new CopyOnWriteArrayList<>();  // Last 10 user messages

        // Thread ownership tracking - the active conversation thread agent
        volatile String activeThreadOwner = null;  // Character name who owns the current thread
        volatile long lastThreadUpdateTime = 0;    // Timestamp of last thread update
        volatile String lastTopic = "";            // Current discussion topic for continuity check
    }

    // ========== State Machine Methods ==========

    private void transitionTo(DiscussionState state, DiscussionPhase newPhase) {
        state.phase = newPhase;
        String roomId = findRoomIdByState(state);
        if (roomId != null) {
            broadcastStateChange(roomId, newPhase, state.selectedCharacters, state.moderatorMessage);
        }
    }

    private String findRoomIdByState(DiscussionState state) {
        for (Map.Entry<String, DiscussionState> entry : roomDiscussionState.entrySet()) {
            if (entry.getValue() == state) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void broadcastStateChange(String roomId, DiscussionPhase phase,
                                      List<Character> selectedCharacters, String message) {
        try {
            DiscussionStateEvent event = new DiscussionStateEvent(
                phase,
                selectedCharacters.stream().map(Character::getId).map(UUID::toString).collect(Collectors.toList()),
                message
            );
            String eventJson = objectMapper.writeValueAsString(event);
            String socketMessage = "42[\"discussion-state\"," + eventJson + "]";
            chatSocketHandler.broadcastToRoom(roomId, socketMessage);
        } catch (Exception e) {
            log.error("[Moderator] broadcastStateChange failed: {}", e.getMessage());
        }
    }

    public void handleUserInterjection(String roomId, String userId, String userMessage) {
        DiscussionState state = roomDiscussionState.get(roomId);
        if (state == null) return;

        // 1. Set interrupt flag immediately
        state.userInterjected = true;

        // 2. Clear pending queue
        state.pendingQueue.clear();

        // 3. Reset AI message count
        state.aiMessageCount = 0;

        // 4. Add to user message history (keep last 10)
        state.userMessageHistory.add(userMessage);
        if (state.userMessageHistory.size() > 10) {
            state.userMessageHistory.remove(0);
        }

        // 5. Transition to MODERATING
        transitionTo(state, DiscussionPhase.MODERATING);
        state.currentUserMessage = userMessage;
        state.userMessage = userMessage;  // Also set userMessage for generateCharacterResponse
        state.userId = userId;

        // 6. Immediately start new Moderator analysis
        processModeratorAnalysis(roomId, userMessage);
    }

    /**
     * Update the active thread owner after a character responds.
     * This maintains conversation continuity by tracking who the user is currently talking to.
     */
    private void updateActiveThread(String roomId, DiscussionState state, String characterName) {
        if (state == null || characterName == null) return;

        long now = System.currentTimeMillis();
        state.activeThreadOwner = characterName;
        state.lastThreadUpdateTime = now;

        log.info("[Moderator] Thread updated for room {}: active owner = {}, timestamp = {}",
            roomId, characterName, now);
    }

    /**
     * Check if the current user message is likely continuing the same thread.
     * Returns true if the message should be routed to the active thread owner.
     */
    private boolean isContinuingThread(DiscussionState state, String userMessage, String selectedAgentName) {
        if (state == null || state.activeThreadOwner == null) {
            return false;
        }

        // If user explicitly mentioned a different agent, don't force thread continuity
        if (selectedAgentName != null && !selectedAgentName.equals(state.activeThreadOwner)) {
            return false;
        }

        // Short contextual messages strongly suggest thread continuation
        if (isShortContextualMessage(userMessage)) {
            return true;
        }

        // If message is very short and thread was recently active (within 2 minutes)
        long twoMinutesAgo = System.currentTimeMillis() - 120000;
        if (userMessage.trim().length() < 20 && state.lastThreadUpdateTime > twoMinutesAgo) {
            return true;
        }

        return false;
    }

    private boolean isShortContextualMessage(String message) {
        if (message == null || message.isBlank()) return false;
        String trimmed = message.trim();
        if (trimmed.length() < 10) return true;

        String[] phrases = {"继续", "为什么", "展开", "说说", "然后呢", "有意思", "有道理", "同意", "对", "没错", "不是", "我同意", "我不同意", "嗯", "哈哈"};
        for (String phrase : phrases) {
            if (trimmed.contains(phrase)) return true;
        }
        return false;
    }

    private void processModeratorAnalysis(String roomId, String userMessage) {
        DiscussionState state = roomDiscussionState.get(roomId);
        if (state == null) return;

        List<Character> availableCharacters = state.characters;

        if (availableCharacters == null || availableCharacters.isEmpty()) {
            log.warn("[Moderator] No characters available for room: {}", roomId);
            return;
        }

        // Call LLM to select characters (with user message history context)
        String selection = callModeratorForSelection(state.userId, userMessage, state.userMessageHistory, availableCharacters);
        log.info("[Moderator] LLM selection result: {}", selection);

        // Parse JSON format response
        List<Character> selected = parseModeratorResponse(selection, availableCharacters);

        // Fallback if no characters matched
        if (selected.isEmpty()) {
            selected = availableCharacters.subList(0, Math.min(1, availableCharacters.size()));
        }

        // Thread continuity check: if message is likely continuing same thread, prefer active thread owner
        if (selected.size() == 1 && isContinuingThread(state, userMessage, selected.get(0).getName())) {
            String activeOwner = state.activeThreadOwner;
            if (activeOwner != null) {
                Character threadOwner = availableCharacters.stream()
                    .filter(c -> c.getName().equals(activeOwner))
                    .findFirst()
                    .orElse(null);
                if (threadOwner != null && !selected.contains(threadOwner)) {
                    log.info("[Moderator] Thread continuity: routing to active thread owner {} instead of semantic match", activeOwner);
                    selected = new ArrayList<>();
                    selected.add(threadOwner);
                }
            }
        }

        state.selectedCharacters = new ArrayList<>(selected);
        state.pendingQueue = new ArrayList<>(selected);

        // Broadcast selection
        String selectMsg = "正在邀请: " + selected.stream().map(Character::getName).collect(Collectors.joining(", "));
        state.moderatorMessage = selectMsg;
        broadcastModeratorMessage(roomId, selectMsg, "SELECT");

        // Transition to SPEAKING
        transitionTo(state, DiscussionPhase.SPEAKING);

        // Start speaking process
        processNextInQueue(roomId);
    }

    /**
     * Parse moderator LLM response in JSON format.
     */
    private List<Character> parseModeratorResponse(String response, List<Character> availableCharacters) {
        List<Character> selected = new ArrayList<>();
        try {
            // Try to parse as JSON
            Map<String, Object> json = objectMapper.readValue(response, Map.class);
            String messageType = (String) json.get("message_type");
            List<String> targetAgents = (List<String>) json.get("target_agents");
            String confidence = (String) json.get("confidence");

            log.info("[Moderator] Parsed JSON - type: {}, confidence: {}, targets: {}",
                messageType, confidence, targetAgents);

            // Handle broadcast - all characters speak
            if ("broadcast".equals(messageType)) {
                log.info("[Moderator] Broadcast requested - all characters will speak");
                selected.addAll(availableCharacters);
                return selected;
            }

            // Handle invite - no characters selected, will be handled as INVITE
            if ("invite".equals(messageType)) {
                log.info("[Moderator] INVITE requested - no character selection");
                return selected;
            }

            // Handle direct_reply or multi_target_reply
            if (targetAgents != null) {
                for (String agentName : targetAgents) {
                    final String trimmedName = agentName.trim();
                    Character found = availableCharacters.stream()
                        .filter(c -> c.getName().trim().equals(trimmedName))
                        .findFirst()
                        .orElse(null);
                    if (found != null && !selected.contains(found)) {
                        selected.add(found);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[Moderator] Failed to parse JSON response, trying regex fallback: {}", e.getMessage());
            // Fallback to old [SELECT:xxx] format
            Pattern pattern = Pattern.compile("\\[SELECT:([^\\]]+)\\]");
            Matcher matcher = pattern.matcher(response);
            if (matcher.find()) {
                String[] selectedNames = matcher.group(1).split(",");
                for (String name : selectedNames) {
                    final String trimmedName = name.trim();
                    Character found = availableCharacters.stream()
                        .filter(c -> c.getName().trim().equals(trimmedName))
                        .findFirst()
                        .orElse(null);
                    if (found != null && !selected.contains(found)) {
                        selected.add(found);
                    }
                }
            }
        }
        return selected;
    }

    private void processNextInQueue(String roomId) {
        DiscussionState state = roomDiscussionState.get(roomId);
        if (state == null || !state.isRunning) return;

        // Check if should wait for user
        if (shouldWaitForUser(state)) {
            waitForUserInput(roomId);
            return;
        }

        // Get next character from queue - synchronized for thread safety
        synchronized (state.pendingQueue) {
            if (!state.pendingQueue.isEmpty()) {
                Character character = state.pendingQueue.remove(0);
                generateCharacterResponse(roomId, character, state);
            }
        }
    }

    private boolean shouldWaitForUser(DiscussionState state) {
        if (state.aiMessageCount >= state.maxAiMessagesPerRound) {
            return true;
        }
        if (state.pendingQueue.isEmpty() && state.selectedCharacters.isEmpty()) {
            return true;
        }
        return false;
    }

    private void waitForUserInput(String roomId) {
        DiscussionState state = roomDiscussionState.get(roomId);
        if (state == null) return;

        transitionTo(state, DiscussionPhase.WAITING_FOR_USER);

        String inviteMessage = generateModeratorInvite(state);
        state.moderatorMessage = inviteMessage;
        broadcastModeratorMessage(roomId, inviteMessage, "INVITE");
    }

    private String generateModeratorInvite(DiscussionState state) {
        if (state.selectedCharacters.isEmpty()) {
            return "你想讨论什么话题？";
        }

        String characters = state.selectedCharacters.stream()
            .map(Character::getName)
            .collect(Collectors.joining(" 和 "));

        String[] invites = {
            "你更认同谁的观点，" + characters + "？",
            "你怎么看这个问题？",
            "你有什么不同看法？",
            characters + "观点各异，你支持谁？",
            "这场讨论你怎么看？"
        };

        Random random = new Random();
        return invites[random.nextInt(invites.length)];
    }

    private void broadcastModeratorMessage(String roomId, String content, String type) {
        try {
            ModeratorMessage message = new ModeratorMessage(content, type);
            String eventJson = objectMapper.writeValueAsString(message);
            String socketMessage = "42[\"moderator-message\"," + eventJson + "]";
            chatSocketHandler.broadcastToRoom(roomId, socketMessage);
        } catch (Exception e) {
            log.error("[Moderator] broadcastModeratorMessage failed: {}", e.getMessage());
        }
    }

    public ModeratorAgent(AIService aiService, MessageRepository messageRepository, SettingsService settingsService, @Lazy ChatSocketHandler chatSocketHandler, ResourceLoader resourceLoader, CharacterPromptBuilder characterPromptBuilder, CharacterRepository characterRepository, RoomRepository roomRepository, UserRepository userRepository) {
        this.aiService = aiService;
        this.messageRepository = messageRepository;
        this.settingsService = settingsService;
        this.chatSocketHandler = chatSocketHandler;
        this.resourceLoader = resourceLoader;
        this.characterPromptBuilder = characterPromptBuilder;
        this.characterRepository = characterRepository;
        this.roomRepository = roomRepository;
        this.userRepository = userRepository;
        // Wrap executor so SecurityContext is inherited by async threads
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            // Inherit SecurityContext from the thread that submits the task
            SecurityContext ctx = SecurityContextHolder.getContext();
            t.setContextClassLoader(null);
            return new Thread(ctx != null ? new SecurityContextAwareThread(ctx, t) : t);
        });
    }

    // Wraps a Thread to set SecurityContext before run()
    private static class SecurityContextAwareThread extends Thread {
        private final SecurityContext context;

        SecurityContextAwareThread(SecurityContext context, Thread delegate) {
            super(delegate);
            this.context = context;
        }

        @Override
        public void run() {
            try {
                SecurityContextHolder.setContext(context);
                SecurityContextHolder.getContext().setAuthentication(context.getAuthentication());
                super.run();
            } finally {
                SecurityContextHolder.clearContext();
            }
        }
    }

    /**
     * Process a user message and orchestrate AI character discussion.
     *
     * @param roomId The room ID
     * @param userId The user ID (passed explicitly to avoid SecurityContext threading issues)
     * @param userMessage The user's message
     * @param characters List of characters in the room
     * @param isContinuous If true, run multiple rounds (discussion mode); if false, single round (dialogue mode)
     * @param maxRounds Maximum rounds for continuous mode
     * @param onThinking Callback for "thinking" state
     * @param onChunk Callback for streaming chunks (called as content is generated)
     * @param onResponse Callback for each character's complete response
     */
    public void processMessage(String roomId, String userId, String userMessage, List<Character> characters,
                               boolean isContinuous, int maxRounds,
                               Consumer<String> onThinking, Consumer<ResponseFragment> onChunk,
                               Consumer<ResponseFragment> onResponse) {
        if (characters == null || characters.isEmpty()) {
            return;
        }

        log.info("[Moderator] processMessage - roomId: {}, userId: {}, charCount: {}, isContinuous: {}",
            roomId, userId, characters.size(), isContinuous);

        // Build initial context with user message
        String initialContext = buildInitialContext(userMessage);

        // Track responses for each round
        Map<Integer, List<ResponseFragment>> roundResponses = new ConcurrentHashMap<>();

        try {
            if (isContinuous) {
                // Discussion mode: sequential turn-based discussion
                DiscussionState state = new DiscussionState();
                state.characters = new ArrayList<>(characters);
                state.userMessage = userMessage;
                state.currentUserMessage = userMessage;
                state.context = initialContext;
                state.currentRound = 1;
                state.maxRounds = maxRounds;
                state.isRunning = true;
                state.paused = false;
                state.userTriggered.set(false);
                state.userId = userId;
                roomDiscussionState.put(roomId, state);

                runSequentialDiscussion(roomId, userId, state, onThinking, onChunk, onResponse);
            } else {
                // Dialogue mode: single round only
                runSingleRound(roomId, userId, userMessage, initialContext, characters,
                    roundResponses, onThinking, onChunk, onResponse);
            }
        } catch (Exception e) {
            log.error("[Moderator] processMessage caught exception: {}", e.getMessage(), e);
        }
    }

    /**
     * Run sequential turn-based discussion mode.
     * Characters take turns speaking one by one with random delays between turns.
     * Supports pause/resume and user message triggering.
     */
    private void runSequentialDiscussion(String roomId, String userId, DiscussionState state,
                                         Consumer<String> onThinking, Consumer<ResponseFragment> onChunk,
                                         Consumer<ResponseFragment> onResponse) {
        log.info("[Moderator] runSequentialDiscussion START - roomId: {}, chars: {}, rounds: {}",
            roomId, state.characters.size(), state.maxRounds);

        String userApiKey = getApiKey(userId);

        // Start the discussion loop in a background thread
        CompletableFuture.runAsync(() -> {
            try {
                while (state.isRunning && state.currentRound <= state.maxRounds) {
                    Character character = state.characters.get(state.currentCharacterIndex);

                    // Wait for pause - check every 500ms
                    while (state.paused && state.isRunning) {
                        log.info("[Moderator] Discussion paused, waiting...");
                        Thread.sleep(500);
                    }

                    if (!state.isRunning) break;

                    // Random delay 1-3 seconds before this character speaks
                    int delaySeconds = 1 + (int) (Math.random() * 2);
                    log.info("[Moderator] [Round {}] Waiting {}s before {} speaks",
                        state.currentRound, delaySeconds, character.getName());

                    for (int i = 0; i < delaySeconds * 2; i++) {
                        if (!state.isRunning) break;
                        if (state.userTriggered.get()) {
                            log.info("[Moderator] User triggered, skipping delay");
                            state.userTriggered.set(false);
                            break;
                        }
                        Thread.sleep(500);
                    }

                    if (!state.isRunning) break;

                    // Character speaks (blocking stream)
                    log.info("[Moderator] [Round {}] {} is now speaking", state.currentRound, character.getName());
                    generateCharacterResponse(roomId, character, state, userApiKey, onChunk, onResponse);

                    // Move to next character
                    state.currentCharacterIndex++;

                    // If all characters have spoken this round
                    if (state.currentCharacterIndex >= state.characters.size()) {
                        state.currentCharacterIndex = 0;
                        state.currentRound++;
                        state.context = buildRoundContext(state.context, state.responses, state.currentRound - 1);
                        log.info("[Moderator] Completed round {}, moving to round {}",
                            state.currentRound - 1, state.currentRound);
                    }

                    if (!state.isRunning) break;
                }

                log.info("[Moderator] Discussion ended - rounds completed: {}", state.currentRound - 1);
                state.isRunning = false;
                roomDiscussionState.remove(roomId);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("[Moderator] Discussion interrupted");
                state.isRunning = false;
            } catch (Exception e) {
                log.error("[Moderator] Discussion error: {}", e.getMessage(), e);
                state.isRunning = false;
            }
        }, executor);
    }

    /**
     * Generate response for a single character with streaming.
     * Merged overload: internally fetches API key, broadcasts canonical events
     * (message stream / chat message / error) directly, and persists the
     * complete message via messageRepository.save.
     */
    private void generateCharacterResponse(String roomId, Character character, DiscussionState state,
                                           String userId, String userMessage, String context) {
        String userApiKey = settingsService.getApiKeyById(userId);
        if (userApiKey == null) {
            log.error("[Moderator] [{}] No API key available for user: {}", character.getName(), userId);
            return;
        }

        try {
            String characterPrompt = characterPromptBuilder.build(character, true);
            String fullPrompt = characterPrompt + "\n\n" + context + "\n\nUser's question: " + userMessage;

            log.info("[Moderator] [{}] Prompt length: {}", character.getName(), fullPrompt.length());

            StringBuilder fullResponse = new StringBuilder();
            CountDownLatch latch = new CountDownLatch(1);

            aiService.generateResponseStream(
                fullPrompt,
                userMessage,
                userApiKey,
                chunk -> {
                    fullResponse.append(chunk);
                    try {
                        // Stream chunk to frontend via canonical 'message stream' event
                        Map<String, Object> chunkData = new java.util.HashMap<>();
                        chunkData.put("content", chunk);
                        chunkData.put("senderType", "CHARACTER");
                        chunkData.put("characterId", character.getId().toString());
                        chunkData.put("characterName", character.getName());
                        chunkData.put("roomId", roomId);
                        chunkData.put("avatarUrl", character.getAvatarUrl());
                        chunkData.put("streaming", true);
                        String chunkEvent = "42[\"message stream\","
                            + objectMapper.writeValueAsString(chunkData)
                            + "]";
                        chatSocketHandler.broadcastToRoom(roomId, chunkEvent);
                    } catch (Exception e) {
                        log.warn("[Moderator] [{}] chunk broadcast failed: {}", character.getName(), e.getMessage());
                    }
                },
                completeResponse -> {
                    String responseText = fullResponse.toString();

                    // Persist message to DB
                    try {
                        Message savedMessage = persistCharacterMessage(roomId, character, userId, responseText);
                        state.responses.add(new ResponseFragment(
                            character.getId().toString(),
                            character.getName(),
                            responseText,
                            true,
                            character.getAvatarUrl()
                        ));
                        log.info("[Moderator] [{}] Complete - length: {}, saved id: {}",
                            character.getName(), responseText.length(), savedMessage.getId());

                        // Broadcast complete response via canonical 'chat message' event
                        Map<String, Object> responseData = new java.util.HashMap<>();
                        responseData.put("content", responseText);
                        responseData.put("senderType", "CHARACTER");
                        responseData.put("characterId", character.getId().toString());
                        responseData.put("characterName", character.getName());
                        responseData.put("avatarUrl", character.getAvatarUrl());
                        responseData.put("roomId", roomId);
                        responseData.put("id", savedMessage.getId());
                        responseData.put("streaming", false);
                        String responseEvent = "42[\"chat message\","
                            + objectMapper.writeValueAsString(responseData)
                            + "]";
                        chatSocketHandler.broadcastToRoom(roomId, responseEvent);
                    } catch (Exception e) {
                        log.error("[Moderator] [{}] persist/broadcast complete failed: {}",
                            character.getName(), e.getMessage());
                    }
                    latch.countDown();
                },
                error -> {
                    log.error("[Moderator] [{}] Error: {}", character.getName(), error.getMessage());
                    state.responses.add(new ResponseFragment(
                        character.getId().toString(),
                        character.getName(),
                        "Error: " + error.getMessage(),
                        true,
                        character.getAvatarUrl()
                    ));
                    // Broadcast error to frontend
                    try {
                        String errorEvent = "42[\"error\","
                            + objectMapper.writeValueAsString(Map.of("message", error.getMessage()))
                            + "]";
                        chatSocketHandler.broadcastToRoom(roomId, errorEvent);
                    } catch (Exception e) {
                        log.warn("[Moderator] [{}] error broadcast failed: {}", character.getName(), e.getMessage());
                    }
                    latch.countDown();
                }
            );

            boolean completed = latch.await(90, TimeUnit.SECONDS);
            if (!completed) {
                log.warn("[Moderator] [{}] Timeout", character.getName());
                String responseText = fullResponse.length() > 0 ? fullResponse.toString() : "Error: Response timed out (90s)";
                try {
                    Message savedMessage = persistCharacterMessage(roomId, character, userId, responseText);
                    Map<String, Object> responseData = new java.util.HashMap<>();
                    responseData.put("content", responseText);
                    responseData.put("senderType", "CHARACTER");
                    responseData.put("characterId", character.getId().toString());
                    responseData.put("characterName", character.getName());
                    responseData.put("avatarUrl", character.getAvatarUrl());
                    responseData.put("roomId", roomId);
                    responseData.put("id", savedMessage.getId());
                    responseData.put("streaming", false);
                    String responseEvent = "42[\"chat message\","
                        + objectMapper.writeValueAsString(responseData)
                        + "]";
                    chatSocketHandler.broadcastToRoom(roomId, responseEvent);
                } catch (Exception e) {
                    log.error("[Moderator] [{}] timeout persist/broadcast failed: {}",
                        character.getName(), e.getMessage());
                }
                state.responses.add(new ResponseFragment(
                    character.getId().toString(),
                    character.getName(),
                    responseText,
                    true,
                    character.getAvatarUrl()
                ));
            }

            // State machine: after completion, process next in queue
            state.aiMessageCount++;
            synchronized (state.pendingQueue) {
                if (shouldWaitForUser(state)) {
                    waitForUserInput(roomId);
                } else {
                    processNextInQueue(roomId);
                }
            }

        } catch (Exception e) {
            log.error("[Moderator] [{}] generateCharacterResponse failed: {}", character.getName(), e.getMessage());
        }
    }

    /**
     * Persist a CHARACTER message to the DB. Looks up Character/Room/User entities
     * by id, populates the Message, and returns the saved entity (with id).
     */
    private Message persistCharacterMessage(String roomId, Character character, String userId, String content) {
        UUID characterUuid = character.getId();
        UUID roomUuid = UUID.fromString(roomId);
        Character characterEntity = characterRepository.findById(characterUuid).orElse(character);
        Room roomEntity = roomRepository.findById(roomUuid).orElse(null);
        User userEntity = (userId != null && !userId.isEmpty())
            ? userRepository.findById(UUID.fromString(userId)).orElse(null)
            : null;

        Message message = new Message();
        message.setContent(content);
        message.setSenderType(Message.SenderType.CHARACTER);
        message.setCharacter(characterEntity);
        message.setRoom(roomEntity);
        message.setUser(userEntity);
        return messageRepository.save(message);
    }

    private String getApiKey(String userId) {
        try {
            return settingsService.getApiKeyById(userId);
        } catch (Exception e) {
            log.error("[Moderator] Failed to get API key: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Pause discussion for a room.
     */
    public void pauseDiscussion(String roomId) {
        DiscussionState state = roomDiscussionState.get(roomId);
        if (state != null) {
            state.paused = true;
            log.info("[Moderator] Discussion paused for room: {}", roomId);
        }
    }

    /**
     * Resume discussion for a room.
     */
    public void resumeDiscussion(String roomId) {
        DiscussionState state = roomDiscussionState.get(roomId);
        if (state != null) {
            state.paused = false;
            state.userTriggered.set(true); // Trigger immediate continuation
            log.info("[Moderator] Discussion resumed for room: {}", roomId);
        }
    }

    /**
     * Trigger discussion to continue when user sends a message.
     */
    public void triggerUserMessage(String roomId) {
        DiscussionState state = roomDiscussionState.get(roomId);
        if (state != null && state.isRunning) {
            state.userTriggered.set(true);
            // Also resume if paused
            if (state.paused) {
                state.paused = false;
            }
            log.info("[Moderator] User message triggered discussion for room: {}", roomId);
        }
    }

    /**
     * Stop discussion for a room.
     */
    public void stopDiscussion(String roomId) {
        DiscussionState state = roomDiscussionState.remove(roomId);
        if (state != null) {
            state.isRunning = false;
            log.info("[Moderator] Discussion stopped for room: {}", roomId);
        }
    }

    /**
     * Check if a discussion is currently running for a room.
     */
    public boolean isDiscussionRunning(String roomId) {
        DiscussionState state = roomDiscussionState.get(roomId);
        return state != null && state.isRunning;
    }

    /**
     * Run a single dialogue round (dialogue mode).
     */
    private void runSingleRound(String roomId, String userId, String userMessage, String context,
                                List<Character> characters,
                                Map<Integer, List<ResponseFragment>> roundResponses,
                                Consumer<String> onThinking, Consumer<ResponseFragment> onChunk,
                                Consumer<ResponseFragment> onResponse) {

        log.info("[Moderator] runSingleRound - roomId: {}, userId: {}, characters: {}, isContinuous: false",
            roomId, userId, characters.size());

        // Get API key in main thread before async tasks (avoids SecurityContext threading issues)
        String userApiKey = null;
        try {
            userApiKey = settingsService.getApiKeyById(userId);
            log.info("[Moderator] Got API key for userId: {}", userId);
        } catch (Exception e) {
            log.error("[Moderator] Failed to get API key for userId {}: {}", userId, e.getMessage());
        }
        final String userApiKeyFinal = userApiKey;

        // Notify thinking for all characters first
        for (Character character : characters) {
            log.info("[Moderator] Notifying thinking for character: {} ({})", character.getName(), character.getId());
            onThinking.accept(character.getId().toString());
        }

        List<ResponseFragment> thisRoundResponses = Collections.synchronizedList(new ArrayList<>());
        roundResponses.put(1, thisRoundResponses);

        // Process characters sequentially with random delay between them
        for (int i = 0; i < characters.size(); i++) {
            Character character = characters.get(i);

            // Random delay between 1-3 seconds before each character starts
            if (i > 0) {
                int delayMs = 1000 + (int) (Math.random() * 2000); // 1-3 seconds
                log.info("[Moderator] [{}] Waiting {}ms before starting (character {})",
                    character.getName(), delayMs, i + 1);
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            log.info("[Moderator] [{}] Starting AI generation", character.getName());

            // Build character prompt and generate response synchronously (blocking)
            try {
                String characterPrompt = characterPromptBuilder.build(character, true);
                String fullPrompt = characterPrompt + "\n\n" + context;

                log.info("[Moderator] [{}] Prompt built, length: {}, calling AI service",
                    character.getName(), fullPrompt.length());

                StringBuilder fullResponse = new StringBuilder();
                CountDownLatch latch = new CountDownLatch(1);

                aiService.generateResponseStream(
                    fullPrompt,
                    userMessage,
                    userApiKeyFinal,
                    chunk -> {
                        fullResponse.append(chunk);
                        // Stream chunk directly to frontend (not delta)
                        try {
                            String charId = character.getId().toString();
                            log.info("[Moderator] [{}] onChunk - chunkLen={}, totalLen={}",
                                character.getName(), chunk.length(), fullResponse.length());
                            if (chunk != null && !chunk.isEmpty()) {
                                onChunk.accept(new ResponseFragment(
                                    charId,
                                    character.getName(),
                                    chunk,
                                    false,
                                    character.getAvatarUrl()
                                ));
                            }
                        } catch (Exception e) {
                            log.warn("[Moderator] [{}] onChunk callback failed: {}",
                                character.getName(), e.getMessage());
                        }
                    },
                    completeResponse -> {
                        String responseText = fullResponse.toString();
                        log.info("[Moderator] [{}] onComplete - response length: {}",
                            character.getName(), responseText.length());
                        thisRoundResponses.add(new ResponseFragment(
                            character.getId().toString(),
                            character.getName(),
                            responseText,
                            true,
                            character.getAvatarUrl()
                        ));
                        latch.countDown();
                    },
                    error -> {
                        log.error("[Moderator] [{}] onError: {}", character.getName(), error.getMessage());
                        thisRoundResponses.add(new ResponseFragment(
                            character.getId().toString(),
                            character.getName(),
                            "Error: " + error.getMessage(),
                            true,
                            character.getAvatarUrl()
                        ));
                        latch.countDown();
                    }
                );

                log.info("[Moderator] [{}] Waiting for AI response (60s timeout)", character.getName());
                boolean latchReleased = latch.await(60, TimeUnit.SECONDS);
                if (!latchReleased) {
                    log.warn("[Moderator] [{}] Latch timed out after 60s", character.getName());
                    String responseText = fullResponse.toString();
                    if (responseText.isEmpty()) {
                        responseText = "Error: Response timed out (60s)";
                    }
                    thisRoundResponses.add(new ResponseFragment(
                        character.getId().toString(),
                        character.getName(),
                        responseText,
                        true,
                        character.getAvatarUrl()
                    ));
                } else {
                    log.info("[Moderator] [{}] AI response completed", character.getName());
                }

                // Send final complete response
                ResponseFragment finalFragment = thisRoundResponses.get(thisRoundResponses.size() - 1);
                try {
                    onResponse.accept(finalFragment);
                } catch (Exception e) {
                    log.error("[Moderator] [{}] onResponse callback failed: {}",
                        character.getName(), e.getMessage(), e);
                }

            } catch (Exception e) {
                log.error("[Moderator] [{}] Unexpected error: {}", character.getName(), e.getMessage(), e);
                thisRoundResponses.add(new ResponseFragment(
                    character.getId().toString(),
                    character.getName(),
                    "Error: " + e.getMessage(),
                    true,
                    character.getAvatarUrl()
                ));
            }
        }

        log.info("[Moderator] All characters completed, total responses: {}", thisRoundResponses.size());
    }

    /**
     * Build initial context with user message and system prompt.
     */
    private String buildInitialContext(String userMessage) {
        return "The user has asked: \"" + userMessage + "\"\n\n" +
               "This is a GROUP DISCUSSION. Everyone should respond to the user's question first, " +
               "then in subsequent rounds, comment on and debate each other's viewpoints.\n\n" +
               "Keep responses conversational and relatively brief (2-4 sentences).";
    }

    /**
     * Build context for subsequent rounds with previous responses.
     */
    private String buildRoundContext(String previousContext, List<ResponseFragment> responses, int roundNum) {
        StringBuilder context = new StringBuilder();
        context.append(previousContext);
        context.append("\n\n=== Round ").append(roundNum).append(" Responses ===\n");

        for (ResponseFragment r : responses) {
            context.append("[").append(r.getCharacterName()).append("]: ").append(r.getContent()).append("\n");
        }

        context.append("\n=== Round ").append(roundNum + 1).append(" ===\n");
        context.append("Now COMMENT on or RESPOND to what others said. Agree, disagree, or add new perspectives. " +
                      "Keep it conversational (2-4 sentences). Address specific points others made.");

        return context.toString();
    }

    /**
     * Build the system prompt for Moderator to select characters.
     */
    private String buildModeratorPrompt(String userMessage, List<String> userMessageHistory, List<Character> characters) {
        try {
            Resource resource = resourceLoader.getResource("classpath:prompts/moderator-prompt.txt");
            String template = resource.getContentAsString(StandardCharsets.UTF_8);

            // Build characters list
            StringBuilder charactersList = new StringBuilder();
            for (Character c : characters) {
                charactersList.append("- ").append(c.getName())
                    .append(" (专家领域: ").append(c.getExpertise() != null ? String.join(", ", c.getExpertise()) : "未知").append(")")
                    .append(" - ").append(c.getPersona() != null ? c.getPersona() : "").append("\n");
            }

            // Build user message history (last 10)
            StringBuilder historyBuilder = new StringBuilder();
            if (userMessageHistory != null && !userMessageHistory.isEmpty()) {
                int index = 1;
                for (String msg : userMessageHistory) {
                    historyBuilder.append("用户消息").append(index).append(": \"").append(msg).append("\"\n");
                    index++;
                }
            } else {
                historyBuilder.append("(暂无历史对话)\n");
            }

            // Replace placeholders
            return template.replace("{characters}", charactersList.toString())
                          .replace("{userMessage}", userMessage)
                          .replace("{conversationHistory}", historyBuilder.toString());
        } catch (Exception e) {
            log.error("[Moderator] Failed to load moderator prompt template: {}", e.getMessage());
            // Fallback to empty prompt
            return "";
        }
    }

    /**
     * Call LLM to select characters for the discussion.
     */
    private String callModeratorForSelection(String userId, String userMessage, List<String> userMessageHistory, List<Character> characters) {
        String userApiKey = getApiKey(userId);
        if (userApiKey == null) {
            log.warn("[Moderator] No API key for user {}, using fallback selection", userId);
            return "[SELECT:" + characters.stream().limit(2).map(Character::getName).collect(Collectors.joining(",")) + "]";
        }

        try {
            String prompt = buildModeratorPrompt(userMessage, userMessageHistory, characters);
            ChatLanguageModel chatModel = aiService.createChatModelWithApiKey(userApiKey);
            String response = chatModel.chat(prompt);
            log.info("[Moderator] LLM selection response: {}", response);
            return response;
        } catch (Exception e) {
            log.error("[Moderator] LLM selection failed: {}, using fallback", e.getMessage());
            return "[SELECT:" + characters.stream().limit(2).map(Character::getName).collect(Collectors.joining(",")) + "]";
        }
    }

    @Override
    public void destroy() throws Exception {
        // Cancel all ongoing room discussions
        roomFutures.keySet().forEach(this::cancelRoom);
        roomFutures.clear();

        executor.shutdown();
        // Wait up to 60 seconds for tasks to complete
        if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
            executor.shutdownNow();
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                log.error("[DEBUG] ModeratorAgent: Executor did not terminate");
            }
        }
        log.info("[DEBUG] ModeratorAgent: ExecutorService shut down");
    }

    /**
     * Cancel all ongoing AI processing for a room.
     * This method cancels all tracked CompletableFutures associated with the room.
     *
     * @param roomId The room ID to cancel
     */
    public void cancelRoom(String roomId) {
        // Stop discussion state if running
        stopDiscussion(roomId);

        List<CompletableFuture<?>> futures = roomFutures.remove(roomId);
        if (futures != null) {
            for (CompletableFuture<?> future : futures) {
                future.cancel(true);
            }
        }
    }

    /**
     * Response fragment from a character.
     */
    public static class ResponseFragment {
        private final String characterId;
        private final String characterName;
        private final String content;
        private final boolean isComplete;
        private final String avatarUrl;

        public ResponseFragment(String characterId, String characterName, String content, boolean isComplete) {
            this(characterId, characterName, content, isComplete, null);
        }

        public ResponseFragment(String characterId, String characterName, String content, boolean isComplete, String avatarUrl) {
            this.characterId = characterId;
            this.characterName = characterName;
            this.content = content;
            this.isComplete = isComplete;
            this.avatarUrl = avatarUrl;
        }

        public String getCharacterId() { return characterId; }
        public String getCharacterName() { return characterName; }
        public String getContent() { return content; }
        public boolean isComplete() { return isComplete; }
        public String getAvatarUrl() { return avatarUrl; }
    }
}
