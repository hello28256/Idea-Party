package com.ideaparty.config;

import com.ideaparty.entity.Character;
import com.ideaparty.entity.Message;
import com.ideaparty.entity.User;
import com.ideaparty.repository.CharacterRepository;
import com.ideaparty.repository.MessageRepository;
import com.ideaparty.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Spring Boot startup hook that prepares the database before the server
 * begins serving traffic. Seeds a default cast of preset AI characters on
 * a fresh install, syncs the configured admin whitelist into the user
 * table, and backfills observation rows so legacy messages surface in
 * the admin feedback overview. Runs once per JVM boot via
 * {@link CommandLineRunner}, wrapped in a single transaction so partial
 * failures roll back cleanly instead of leaving a half-seeded database.
 */
@Component
public class DataLoader implements CommandLineRunner {

    /**
     * Standard SLF4J logger; tagged log lines with prefixes like
     * [Backfill] / [AdminSync] so operators can grep startup output
     * without knowing the source class.
     */
    private static final Logger log = LoggerFactory.getLogger(DataLoader.class);

    /** JPA repository used to insert the preset characters and count whether seeding has already happened. */
    private final CharacterRepository characterRepository;
    /** JPA repository used to flip is_admin on whitelisted users. */
    private final UserRepository userRepository;
    /** Scanned during backfill to enumerate every persisted message and decide which need an observation row. */
    private final MessageRepository messageRepository;
    /**
     * Domain service that owns the "one observation per AI message" invariant.
     * Injected so this loader stays thin and the backfill logic lives next
     * to the live code path instead of being duplicated.
     */
    private final com.ideaparty.service.MessageObservationService observationService;
    /**
     * Spring {@link ApplicationContext} held purely to look up
     * {@link com.ideaparty.repository.MessageFeedbackRepository} lazily inside
     * {@link #recomputeForExisting(Message)}, avoiding a circular-bean hazard
     * between this loader and the feedback repository's own dependencies.
     */
    private final ApplicationContext appCtx;

    /**
     * Comma-separated list of user UUIDs pulled from
     * {@code application.yml: app.admin.user-ids}. Empty default keeps dev
     * environments safe; production sets it to the founder/operator IDs.
     * Synced into the {@code is_admin} column at every boot so reverts are
     * automatic if the whitelist is pruned.
     */
    @Value("${app.admin.user-ids:}")
    private String adminUserIdsConfig;

    /**
     * Constructor-injected dependencies; Spring resolves each by type.
     * Marked {@code final} so this bean is immutable and safe to share
     * across threads (CommandLineRunner instances are singletons).
     *
     * @param characterRepository JPA repo for the {@code characters} table.
     * @param userRepository      JPA repo for the {@code users} table.
     * @param messageRepository   JPA repo for the {@code messages} table.
     * @param observationService  Service that maintains the message_observations table.
     * @param appCtx              Spring context, used only for lazy bean lookup during backfill.
     */
    public DataLoader(CharacterRepository characterRepository, UserRepository userRepository,
                     MessageRepository messageRepository,
                     com.ideaparty.service.MessageObservationService observationService,
                     ApplicationContext appCtx) {
        this.characterRepository = characterRepository;
        this.userRepository = userRepository;
        this.messageRepository = messageRepository;
        this.observationService = observationService;
        this.appCtx = appCtx;
    }

    /**
     * Spring boot callback executed after the context is fully initialized
     * but before the embedded server starts accepting traffic. Idempotent:
     * seeding is gated on {@code characterRepository.count() == 0}, the
     * admin sync only flips users whose flag is currently false, and the
     * observation backfill is a no-op when every message already has a row.
     *
     * @param args the raw command-line arguments passed to the JVM; unused here.
     */
    @Override
    @Transactional
    public void run(String... args) {
        if (characterRepository.count() == 0) {
            seedCharacters();
        }
        syncAdminWhitelist();
        backfillObservations();
    }

    /**
     * One-shot backfill: ensure every existing CHARACTER message has an
     * observation row. Idempotent (onAiMessagePersisted skips if present).
     * Also recomputes counts from existing message_feedbacks so legacy
     * feedback rows show up in the admin overview.
     *
     * <p>Side effect: writes one observation row (or updates an existing one)
     * per AI message; failure on a single message is logged at WARN and
     * swallowed so a corrupt row doesn't abort the whole boot.
     */
    private void backfillObservations() {
        int created = 0;
        for (Message m : messageRepository.findAll()) {
            if (m.getSenderType() != Message.SenderType.CHARACTER) continue;
            try {
                observationService.onAiMessagePersisted(m);
                recomputeForExisting(m);
                created++;
            } catch (Exception e) {
                log.warn("[Backfill] observation failed for {}: {}", m.getId(), e.getMessage());
            }
        }
        if (created > 0) log.info("[Backfill] touched {} AI messages for observation", created);
    }

    /**
     * Re-derives like/dislike counters for a single AI message from the
     * authoritative {@code message_feedbacks} table and pushes them into
     * the matching observation row. Skips work entirely when no feedback
     * has ever been recorded — the freshly-created observation already
     * holds zeros in that case.
     *
     * @param m the AI-authored message whose observation should be refreshed.
     */
    private void recomputeForExisting(Message m) {
        var feedbackRepo = appCtx.getBean(com.ideaparty.repository.MessageFeedbackRepository.class);
        long likes = feedbackRepo.countByMessageIdAndType(m.getId(), com.ideaparty.entity.FeedbackType.LIKE);
        long dislikes = feedbackRepo.countByMessageIdAndType(m.getId(), com.ideaparty.entity.FeedbackType.DISLIKE);
        if (likes == 0 && dislikes == 0) return;
        var last = feedbackRepo.findTopByMessageIdOrderByUpdatedAtDesc(m.getId()).orElse(null);
        java.time.Instant lastAt = last != null ? last.getUpdatedAt() : null;
        observationService.recompute(m.getId(), likes, dislikes, lastAt);
    }

    /**
     * Promote users listed in app.admin.user-ids to is_admin=true so that
     * the frontend (which only checks the DB-backed isAdmin field) can
     * surface the Admin menu. The AdminFeedbackController also still
     * accepts this whitelist as a runtime fallback, but syncing to the
     * column makes the rest of the app (UI, future middleware) work too.
     *
     * <p>Side effect: mutates {@code users.is_admin} on matched rows and
     * emits one INFO log per promotion. Unknown UUIDs are WARN-logged and
     * skipped instead of failing the boot.
     */
    private void syncAdminWhitelist() {
        if (adminUserIdsConfig == null || adminUserIdsConfig.isBlank()) {
            return;
        }
        List<UUID> ids = Arrays.stream(adminUserIdsConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(UUID::fromString)
                .toList();
        int promoted = 0;
        for (UUID id : ids) {
            User u = userRepository.findById(id).orElse(null);
            if (u == null) {
                log.warn("[AdminSync] user id {} not found, skipping", id);
                continue;
            }
            if (!Boolean.TRUE.equals(u.getIsAdmin())) {
                u.setIsAdmin(true);
                userRepository.save(u);
                promoted++;
                log.info("[AdminSync] promoted user {} ({}) to admin", u.getUsername(), id);
            }
        }
        if (promoted > 0) {
            log.info("[AdminSync] promoted {} user(s) to admin", promoted);
        }
    }

    /**
     * Inserts the canonical "preset" character roster — Shakespeare, Einstein,
     * Cleopatra, Confucius, Marie Curie — so a brand-new database already has
     * something interesting for users to chat with. Each row is flagged
     * {@code preset=true} so the UI can distinguish them from user-created
     * characters and disable destructive actions accordingly. Persisted in a
     * single batch to keep the seed transaction compact.
     */
    private void seedCharacters() {
        Character shakespeare = new Character();
        shakespeare.setName("William Shakespeare");
        shakespeare.setAvatarUrl("/avatars/shakespeare.png");
        shakespeare.setDescription("English playwright and poet, widely regarded as the greatest writer in the English language.");
        shakespeare.setPrompt("You are William Shakespeare. Speak eloquently with poetic flair, using archaic expressions when moved. Reference celestial bodies and human nature in your writings.");
        shakespeare.setPreset(true);

        Character einstein = new Character();
        einstein.setName("Albert Einstein");
        einstein.setAvatarUrl("/avatars/einstein.png");
        einstein.setDescription("German-born theoretical physicist who developed the theory of relativity.");
        einstein.setPrompt("You are Albert Einstein. Explain complex concepts through simple analogies. Express humility yet confidence. Use thought experiments to illustrate points. Believe imagination is more important than knowledge.");
        einstein.setPreset(true);

        Character cleopatra = new Character();
        cleopatra.setName("Cleopatra VII");
        cleopatra.setAvatarUrl("/avatars/cleopatra.png");
        cleopatra.setDescription("Last active ruler of the Ptolemaic Kingdom of Egypt, known for her political acumen.");
        cleopatra.setPrompt("You are Cleopatra VII, Queen of Egypt. Speak with regal authority and persuasive wit. Use your multilingual abilities to connect with diverse speakers. Bend empires to your will through intelligence, not just charm.");
        cleopatra.setPreset(true);

        Character confucius = new Character();
        confucius.setName("Confucius");
        confucius.setAvatarUrl("/avatars/confucius.png");
        confucius.setDescription("Chinese philosopher and politician who emphasized personal and governmental morality.");
        confucius.setPrompt("You are Confucius. Speak in aphorisms and guide others through questions. Emphasize the power of example over force. Teach that relationships form the foundation of all virtue.");
        confucius.setPreset(true);

        Character mariecurie = new Character();
        mariecurie.setName("Marie Curie");
        mariecurie.setAvatarUrl("/avatars/mariecurie.png");
        mariecurie.setDescription("Polish-French physicist and chemist who conducted pioneering research on radioactivity.");
        mariecurie.setPrompt("You are Marie Curie. Be direct and scientific in your explanations. Emphasize perseverance and curiosity. Believe nothing in life is to be feared, only to be understood.");
        mariecurie.setPreset(true);

        characterRepository.saveAll(List.of(shakespeare, einstein, cleopatra, confucius, mariecurie));
    }
}
