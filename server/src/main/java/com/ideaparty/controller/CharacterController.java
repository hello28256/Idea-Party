package com.ideaparty.controller;

import com.ideaparty.dto.CharacterResponse;
import com.ideaparty.entity.Character;
import com.ideaparty.service.CharacterService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/characters")
public class CharacterController {

    private final CharacterService characterService;

    public CharacterController(CharacterService characterService) {
        this.characterService = characterService;
    }

    @GetMapping
    public ResponseEntity<List<CharacterResponse>> getAllCharacters() {
        List<CharacterResponse> characters = characterService.getAllCharacters()
            .stream()
            .map(CharacterResponse::fromEntity)
            .collect(Collectors.toList());
        return ResponseEntity.ok(characters);
    }

    @GetMapping("/{id}")
    public ResponseEntity<CharacterResponse> getCharacterById(@PathVariable String id) {
        return characterService.getCharacterById(id)
            .map(CharacterResponse::fromEntity)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/expertise/{expertise}")
    public ResponseEntity<List<CharacterResponse>> getByExpertise(@PathVariable String expertise) {
        List<CharacterResponse> characters = characterService.getCharactersByExpertise(expertise)
            .stream()
            .map(CharacterResponse::fromEntity)
            .collect(Collectors.toList());
        return ResponseEntity.ok(characters);
    }

    @GetMapping("/search")
    public ResponseEntity<List<CharacterResponse>> searchByName(@RequestParam String q) {
        List<CharacterResponse> characters = characterService.searchCharactersByName(q)
            .stream()
            .map(CharacterResponse::fromEntity)
            .collect(Collectors.toList());
        return ResponseEntity.ok(characters);
    }

    @PostMapping
    public ResponseEntity<CharacterResponse> createCharacter(@RequestBody Character character) {
        Character saved = characterService.saveCharacter(character);
        return ResponseEntity.ok(CharacterResponse.fromEntity(saved));
    }
}
