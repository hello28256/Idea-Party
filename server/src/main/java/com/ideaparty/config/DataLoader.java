package com.ideaparty.config;

import com.ideaparty.entity.Character;
import com.ideaparty.entity.User;
import com.ideaparty.repository.CharacterRepository;
import com.ideaparty.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Component
public class DataLoader implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataLoader.class);

    private final CharacterRepository characterRepository;
    private final UserRepository userRepository;

    @Value("${app.admin.user-ids:}")
    private String adminUserIdsConfig;

    public DataLoader(CharacterRepository characterRepository, UserRepository userRepository) {
        this.characterRepository = characterRepository;
        this.userRepository = userRepository;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (characterRepository.count() == 0) {
            seedCharacters();
        }
        syncAdminWhitelist();
    }

    /**
     * Promote users listed in app.admin.user-ids to is_admin=true so that
     * the frontend (which only checks the DB-backed isAdmin field) can
     * surface the Admin menu. The AdminFeedbackController also still
     * accepts this whitelist as a runtime fallback, but syncing to the
     * column makes the rest of the app (UI, future middleware) work too.
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
