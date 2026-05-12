package com.ideaparty.controller;

import com.ideaparty.dto.AuthResponse;
import com.ideaparty.dto.ChangePasswordRequest;
import com.ideaparty.dto.LoginRequest;
import com.ideaparty.dto.RegisterRequest;
import com.ideaparty.dto.UpdateProfileRequest;
import com.ideaparty.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        log.info("[DEBUG] Register request received for username: {}", request.getUsername());
        AuthResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        log.info("[DEBUG] Login request received for identifier: {}", request.getIdentifier());
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/profile")
    public ResponseEntity<AuthResponse> updateProfile(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody UpdateProfileRequest request) {
        log.info("[DEBUG] [update profile] headers auth = {}", authHeader);
        log.info("[DEBUG] [update profile] body = {}", request);

        UUID userId = extractUserIdFromToken(authHeader);
        log.info("[DEBUG] [update profile] extracted userId = {}", userId);

        AuthResponse response = authService.updateProfile(userId, request);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/change-password")
    public ResponseEntity<Void> changePassword(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody ChangePasswordRequest request) {
        UUID userId = extractUserIdFromToken(authHeader);
        log.info("[DEBUG] [change password] extracted userId = {}", userId);
        authService.changePassword(userId, request);
        return ResponseEntity.ok().build();
    }

    private UUID extractUserIdFromToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new IllegalArgumentException("Missing or invalid Authorization header");
        }
        String token = authHeader.substring(7);
        return authService.validateToken(token);
    }
}
