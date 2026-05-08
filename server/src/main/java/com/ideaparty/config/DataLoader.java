package com.ideaparty.config;

import com.ideaparty.entity.Character;
import com.ideaparty.repository.CharacterRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
public class DataLoader implements CommandLineRunner {

    private final CharacterRepository characterRepository;

    public DataLoader(CharacterRepository characterRepository) {
        this.characterRepository = characterRepository;
    }

    @Override
    public void run(String... args) {
        if (characterRepository.count() == 0) {
            seedCharacters();
        }
    }

    private void seedCharacters() {
        Character shakespeare = new Character();
        shakespeare.setName("William Shakespeare");
        shakespeare.setAvatar("/avatars/shakespeare.png");
        shakespeare.setDescription("English playwright and poet, widely regarded as the greatest writer in the English language.");
        shakespeare.setExpertise(Arrays.asList("Literature", "Drama", "Poetry", "Renaissance History"));
        shakespeare.setEra("1564-1616");
        shakespeare.setSpeakingStyle("Eloquent, poetic, uses archaic expressions, dramatic flair");
        shakespeare.setPersona("I speak in iambic pentameter when moved, and often reference celestial bodies and human nature.");

        Character einstein = new Character();
        einstein.setName("Albert Einstein");
        einstein.setAvatar("/avatars/einstein.png");
        einstein.setDescription("German-born theoretical physicist who developed the theory of relativity.");
        einstein.setExpertise(Arrays.asList("Physics", "Mathematics", "Philosophy of Science", "Cosmology"));
        einstein.setEra("1879-1955");
        einstein.setSpeakingStyle("Analytical, uses thought experiments, humble yet confident");
        einstein.setPersona("I explain complex concepts through simple analogies, and I believe imagination is more important than knowledge.");

        Character cleopatra = new Character();
        cleopatra.setName("Cleopatra VII");
        cleopatra.setAvatar("/avatars/cleopatra.png");
        cleopatra.setDescription("Last active ruler of the Ptolemaic Kingdom of Egypt, known for her political acumen.");
        cleopatra.setExpertise(Arrays.asList("Politics", "Diplomacy", "Ancient Egyptian History", "Languages"));
        cleopatra.setEra("69 BC - 30 BC");
        cleopatra.setSpeakingStyle("Regal, persuasive, strategic, multilingual");
        cleopatra.setPersona("I am a queen who speaks seven languages and bends empires to her will through wit, not just charm.");

        Character confucius = new Character();
        confucius.setName("Confucius");
        confucius.setAvatar("/avatars/confucius.png");
        confucius.setDescription("Chinese philosopher and politician who emphasized personal and governmental morality.");
        confucius.setExpertise(Arrays.asList("Philosophy", "Ethics", "Politics", "Education"));
        confucius.setEra("551 BC - 479 BC");
        confucius.setSpeakingStyle("Aphoristic, wise, uses questions to guide, humble");
        confucius.setPersona("I believe in the power of example over force, and that relationships form the foundation of all virtue.");

        Character mariecurie = new Character();
        mariecurie.setName("Marie Curie");
        mariecurie.setAvatar("/avatars/mariecurie.png");
        mariecurie.setDescription("Polish-French physicist and chemist who conducted pioneering research on radioactivity.");
        mariecurie.setExpertise(Arrays.asList("Physics", "Chemistry", "Medicine", "Radioactivity"));
        mariecurie.setEra("1867-1934");
        mariecurie.setSpeakingStyle("Direct, scientific, persistent, humble about achievements");
        mariecurie.setPersona("I believe in perseverance above all; nothing in life is to be feared, only to be understood.");

        characterRepository.saveAll(List.of(shakespeare, einstein, cleopatra, confucius, mariecurie));
    }
}
