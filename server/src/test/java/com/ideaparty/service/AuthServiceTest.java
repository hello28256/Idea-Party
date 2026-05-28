package com.ideaparty.service;

import com.ideaparty.dto.AuthResponse;
import com.ideaparty.dto.LoginRequest;
import com.ideaparty.dto.RegisterRequest;
import com.ideaparty.entity.User;
import com.ideaparty.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AuthService.
 * Tests authentication, registration, and token validation.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private AuthService authService;

    private User testUser;
    private UUID userId;

    // Use a valid 256-bit key for HMAC-SHA256
    private static final String TEST_SECRET = "test-secret-key-that-is-at-least-256-bits-long-for-hs256";
    private static final long JWT_EXPIRATION = 3600000; // 1 hour

    @BeforeEach
    void setUp() {
        // Create AuthService with required constructor arguments
        authService = new AuthService(
                userRepository,
                passwordEncoder,
                TEST_SECRET,
                JWT_EXPIRATION
        );

        userId = UUID.randomUUID();
        testUser = User.builder()
                .id(userId)
                .email("test@example.com")
                .username("testuser")
                .displayName("Test User")
                .password("encoded-password")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    @Test
    @DisplayName("login should return token for valid credentials")
    void login_shouldReturnToken() {
        // Given
        LoginRequest request = new LoginRequest("test@example.com", "password123");

        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("password123", "encoded-password")).thenReturn(true);

        // When
        AuthResponse response = authService.login(request);

        // Then
        assertNotNull(response);
        assertNotNull(response.getAccessToken());
        assertEquals("Bearer", response.getTokenType());
        assertEquals(JWT_EXPIRATION, response.getExpiresIn());
        assertNotNull(response.getUser());
        assertEquals(testUser.getId(), response.getUser().getId());
        assertEquals(testUser.getEmail(), response.getUser().getEmail());

        verify(userRepository).findByEmail("test@example.com");
        verify(passwordEncoder).matches("password123", "encoded-password");
    }

    @Test
    @DisplayName("login should throw for invalid email")
    void login_shouldThrowWhenInvalidEmail() {
        // Given
        LoginRequest request = new LoginRequest("nonexistent@example.com", "password123");

        when(userRepository.findByEmail("nonexistent@example.com")).thenReturn(Optional.empty());

        // When & Then
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> authService.login(request)
        );

        assertEquals("Invalid email or password", exception.getMessage());
        verify(userRepository).findByEmail("nonexistent@example.com");
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    @DisplayName("login should throw for invalid password")
    void login_shouldThrowWhenInvalidPassword() {
        // Given
        LoginRequest request = new LoginRequest("test@example.com", "wrong-password");

        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("wrong-password", "encoded-password")).thenReturn(false);

        // When & Then
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> authService.login(request)
        );

        assertEquals("Invalid email or password", exception.getMessage());
        verify(userRepository).findByEmail("test@example.com");
        verify(passwordEncoder).matches("wrong-password", "encoded-password");
    }

    @Test
    @DisplayName("register should create new user and return token")
    void register_shouldCreateUserAndReturnToken() {
        // Given
        // RegisterRequest constructor order: email, password, name
        RegisterRequest request = new RegisterRequest("new@example.com", "password123", "New User");

        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("encoded-new-password");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(UUID.randomUUID());
            return user;
        });

        // When
        AuthResponse response = authService.register(request);

        // Then
        assertNotNull(response);
        assertNotNull(response.getAccessToken());
        assertEquals("Bearer", response.getTokenType());

        verify(userRepository).existsByEmail("new@example.com");
        verify(passwordEncoder).encode("password123");
        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("register should throw when email already exists")
    void register_shouldThrowWhenEmailExists() {
        // Given
        // RegisterRequest constructor order: email, password, name
        RegisterRequest request = new RegisterRequest("existing@example.com", "password123", "Existing User");

        when(userRepository.existsByEmail("existing@example.com")).thenReturn(true);

        // When & Then
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> authService.register(request)
        );

        assertEquals("Email already registered", exception.getMessage());
        verify(userRepository).existsByEmail("existing@example.com");
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("validateToken should return user ID for valid token")
    void validateToken_shouldReturnUserId() {
        // Given
        String token = authService.generateToken(testUser);

        // When
        UUID result = authService.validateToken(token);

        // Then
        assertNotNull(result);
        assertEquals(testUser.getId(), result);
    }

    @Test
    @DisplayName("validateToken should throw for invalid token")
    void validateToken_shouldThrowForInvalidToken() {
        // Given
        String invalidToken = "invalid.token.here";

        // When & Then
        assertThrows(
                IllegalArgumentException.class,
                () -> authService.validateToken(invalidToken)
        );
    }

    @Test
    @DisplayName("validateToken should throw for expired token")
    void validateToken_shouldThrowForExpiredToken() {
        // Given - create service with very short expiration
        AuthService shortExpService = new AuthService(
                userRepository,
                passwordEncoder,
                TEST_SECRET,
                -1000 // Negative expiration = already expired
        );

        String expiredToken = shortExpService.generateToken(testUser);

        // When & Then
        assertThrows(
                IllegalArgumentException.class,
                () -> authService.validateToken(expiredToken)
        );
    }

    @Test
    @DisplayName("generateToken should create valid JWT token")
    void generateToken_shouldCreateValidToken() {
        // When
        String token = authService.generateToken(testUser);

        // Then
        assertNotNull(token);
        assertTrue(token.split("\\.").length == 3); // JWT has 3 parts

        // Token should be valid
        UUID userIdFromToken = authService.validateToken(token);
        assertEquals(testUser.getId(), userIdFromToken);
    }

    @Test
    @DisplayName("findUserById should return user when exists")
    void findUserById_shouldReturnUser() {
        // Given
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));

        // When
        Optional<User> result = authService.findUserById(userId);

        // Then
        assertTrue(result.isPresent());
        assertEquals(testUser.getId(), result.get().getId());
        assertEquals(testUser.getEmail(), result.get().getEmail());
    }

    @Test
    @DisplayName("findUserById should return empty when user not found")
    void findUserById_shouldReturnEmptyWhenNotFound() {
        // Given
        UUID nonExistentId = UUID.randomUUID();
        when(userRepository.findById(nonExistentId)).thenReturn(Optional.empty());

        // When
        Optional<User> result = authService.findUserById(nonExistentId);

        // Then
        assertTrue(result.isEmpty());
    }
}
