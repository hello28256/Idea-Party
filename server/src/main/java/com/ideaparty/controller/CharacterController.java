package com.ideaparty.controller;

import com.ideaparty.dto.CharacterRequest;
import com.ideaparty.dto.CharacterResponse;
import com.ideaparty.service.CharacterService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/characters")
public class CharacterController {

    private final CharacterService characterService;

    public CharacterController(CharacterService characterService) {
        this.characterService = characterService;
    }

    @GetMapping
    public ResponseEntity<List<CharacterResponse>> getAllCharacters(Authentication auth) {
        // Returns all characters (presets + user's) for authenticated user
        List<CharacterResponse> characters = characterService.findAll();
        return ResponseEntity.ok(characters);
    }

    @GetMapping("/presets")
    public ResponseEntity<List<CharacterResponse>> getPresetCharacters() {
        List<CharacterResponse> presets = characterService.findPresets();
        return ResponseEntity.ok(presets);
    }

    @GetMapping("/{id}")
    public ResponseEntity<CharacterResponse> getCharacterById(@PathVariable UUID id) {
        return characterService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<CharacterResponse> createCharacter(
            Authentication auth,
            @Valid @RequestBody CharacterRequest request) {
        UUID userId = UUID.fromString(auth.getName());
        CharacterResponse created = characterService.create(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<CharacterResponse> updateCharacter(
            Authentication auth,
            @PathVariable UUID id,
            @Valid @RequestBody CharacterRequest request) {
        UUID userId = UUID.fromString(auth.getName());
        return characterService.update(id, userId, request)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(HttpStatus.FORBIDDEN).build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCharacter(
            Authentication auth,
            @PathVariable UUID id) {
        UUID userId = UUID.fromString(auth.getName());
        boolean deleted = characterService.deleteIfOwner(id, userId);
        if (!deleted) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.noContent().build();
    }
}
