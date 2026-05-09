package com.ideaparty.controller;

import com.ideaparty.service.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService settingsService;

    @GetMapping("/api-key")
    public ResponseEntity<Map<String, String>> getApiKey() {
        String apiKey = settingsService.getApiKey();
        return ResponseEntity.ok(Map.of("apiKey", apiKey != null ? apiKey : ""));
    }

    @PostMapping("/api-key")
    public ResponseEntity<Void> setApiKey(@RequestBody Map<String, String> body) {
        String apiKey = body.get("apiKey");
        if (apiKey != null && !apiKey.isBlank()) {
            settingsService.setApiKey(apiKey.trim());
        }
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/api-key")
    public ResponseEntity<Void> clearApiKey() {
        settingsService.clearApiKey();
        return ResponseEntity.ok().build();
    }
}
