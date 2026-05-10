package com.ideaparty.controller;

import com.ideaparty.dto.CharacterRequest;
import com.ideaparty.dto.CharacterResponse;
import com.ideaparty.dto.GeneratePromptRequest;
import com.ideaparty.dto.GeneratePromptResponse;
import com.ideaparty.service.CharacterService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/characters")
public class CharacterController {
    private static final Logger log = LoggerFactory.getLogger(CharacterController.class);

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

    @PostMapping("/generate-prompt")
    @ResponseBody
    public GeneratePromptResponse generatePrompt(
            Authentication auth,
            @RequestBody GeneratePromptRequest request) {
        UUID userId = UUID.fromString(auth.getName());
        String prompt = characterService.generatePrompt(userId, request.getName(), request.getDescription());
        return new GeneratePromptResponse(prompt);
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
