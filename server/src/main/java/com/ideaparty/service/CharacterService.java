package com.ideaparty.service;

import com.ideaparty.entity.Character;
import com.ideaparty.repository.CharacterRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class CharacterService {

    private final CharacterRepository characterRepository;

    public CharacterService(CharacterRepository characterRepository) {
        this.characterRepository = characterRepository;
    }

    public List<Character> getAllCharacters() {
        return characterRepository.findAll();
    }

    public Optional<Character> getCharacterById(String id) {
        return characterRepository.findById(id);
    }

    public List<Character> getCharactersByExpertise(String expertise) {
        return characterRepository.findByExpertise(expertise);
    }

    public List<Character> searchCharactersByName(String name) {
        return characterRepository.findByNameContainingIgnoreCase(name);
    }

    public Character saveCharacter(Character character) {
        return characterRepository.save(character);
    }
}
