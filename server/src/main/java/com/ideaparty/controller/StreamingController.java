package com.ideaparty.controller;

import com.ideaparty.entity.Character;
import com.ideaparty.entity.Room;
import com.ideaparty.repository.CharacterRepository;
import com.ideaparty.repository.RoomRepository;
import com.ideaparty.service.ClaudeService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/ai")
public class StreamingController {

    private final ClaudeService claudeService;
    private final RoomRepository roomRepository;
    private final CharacterRepository characterRepository;

    public StreamingController(ClaudeService claudeService, RoomRepository roomRepository, CharacterRepository characterRepository) {
        this.claudeService = claudeService;
        this.roomRepository = roomRepository;
        this.characterRepository = characterRepository;
    }

    @GetMapping(value = "/stream/{roomId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter<Void>> streamAIResponse(
            @PathVariable String roomId,
            @RequestParam String message) {

        Room room = roomRepository.findWithCharactersById(roomId).orElse(null);
        if (room == null) {
            return ResponseEntity.notFound().build();
        }

        Set<Character> characters = room.getCharacters();
        if (characters.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        List<Character> characterList = List.copyOf(characters);

        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);

        claudeService.streamMessage(roomId, characterList, message)
            .subscribe(
                chunk -> {
                    try {
                        emitter.send(SseEmitter.event()
                            .name("ai-chunk")
                            .data(chunk));
                    } catch (Exception e) {
                        emitter.completeWithError(e);
                    }
                },
                error -> emitter.completeWithError(error),
                () -> {
                    try {
                        emitter.send(SseEmitter.event()
                            .name("ai-complete")
                            .data(""));
                    } catch (Exception e) {
                        // Ignore
                    }
                    emitter.complete();
                }
            );

        return ResponseEntity.ok(emitter);
    }
}
