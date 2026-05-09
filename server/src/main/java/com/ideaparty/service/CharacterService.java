package com.ideaparty.service;

import com.ideaparty.dto.CharacterRequest;
import com.ideaparty.dto.CharacterResponse;
import com.ideaparty.entity.Character;
import com.ideaparty.entity.User;
import com.ideaparty.repository.CharacterRepository;
import com.ideaparty.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class CharacterService {

    private final CharacterRepository characterRepository;
    private final UserRepository userRepository;

    public CharacterService(CharacterRepository characterRepository, UserRepository userRepository) {
        this.characterRepository = characterRepository;
        this.userRepository = userRepository;
    }

    public CharacterResponse create(UUID userId, CharacterRequest request) {
        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        Character character = new Character();
        character.setName(request.getName());
        character.setDescription(request.getDescription());
        character.setAvatarUrl(request.getAvatarUrl());
        character.setPrompt(request.getPrompt());
        character.setOwner(owner);
        character.setPreset(false);

        Character saved = characterRepository.save(character);
        return CharacterResponse.fromEntity(saved);
    }

    public List<CharacterResponse> findByUserId(UUID userId) {
        return characterRepository.findByOwnerId(userId)
                .stream()
                .map(CharacterResponse::fromEntity)
                .collect(Collectors.toList());
    }

    public List<CharacterResponse> findPresets() {
        return characterRepository.findByIsPresetTrue()
                .stream()
                .map(CharacterResponse::fromEntity)
                .collect(Collectors.toList());
    }

    public List<CharacterResponse> findAll() {
        return characterRepository.findAll()
                .stream()
                .map(CharacterResponse::fromEntity)
                .collect(Collectors.toList());
    }

    public Optional<CharacterResponse> findById(UUID id) {
        return characterRepository.findById(id)
                .map(CharacterResponse::fromEntity);
    }

    public Optional<CharacterResponse> update(UUID characterId, UUID userId, CharacterRequest request) {
        Optional<Character> optCharacter = characterRepository.findByIdAndOwnerId(characterId, userId);
        if (optCharacter.isEmpty()) {
            return Optional.empty();
        }

        Character character = optCharacter.get();
        character.setName(request.getName());
        character.setDescription(request.getDescription());
        character.setAvatarUrl(request.getAvatarUrl());
        character.setPrompt(request.getPrompt());

        Character saved = characterRepository.save(character);
        return Optional.of(CharacterResponse.fromEntity(saved));
    }

    public boolean deleteIfOwner(UUID characterId, UUID userId) {
        if (!characterRepository.existsByIdAndOwnerId(characterId, userId)) {
            return false;
        }
        characterRepository.deleteById(characterId);
        return true;
    }

    public boolean isOwner(UUID characterId, UUID userId) {
        return characterRepository.existsByIdAndOwnerId(characterId, userId);
    }
}
