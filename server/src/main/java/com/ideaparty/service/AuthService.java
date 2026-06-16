package com.ideaparty.service;

import com.ideaparty.dto.AuthResponse;
import com.ideaparty.dto.ChangePasswordRequest;
import com.ideaparty.dto.LoginRequest;
import com.ideaparty.dto.RegisterRequest;
import com.ideaparty.dto.UpdateProfileRequest;
import com.ideaparty.entity.User;
import com.ideaparty.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

@Service
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecretKey secretKey;
    private final long jwtExpiration;
    private final Random random = new Random();

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${jwt.secret}") String jwtSecret,
            @Value("${jwt.expiration}") long jwtExpiration) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.secretKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        this.jwtExpiration = jwtExpiration;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (request.getEmail() != null && !request.getEmail().isBlank() && userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email already registered");
        }

        if (userRepository.existsByUsername(request.getUsername())) {
            throw new IllegalArgumentException("Username already taken");
        }

        User user = User.builder()
                .email(request.getEmail())
                .username(request.getUsername().toLowerCase())
                .displayName(request.getUsername())
                .password(passwordEncoder.encode(request.getPassword()))
                .build();

        user = userRepository.save(user);
        String token = generateToken(user);

        return buildAuthResponse(token, user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user;
        String identifier = request.getIdentifier().trim();

        if (identifier.contains("@")) {
            user = userRepository.findByEmail(identifier.toLowerCase())
                    .orElseThrow(() -> new IllegalArgumentException("Invalid username or password"));
        } else {
            user = userRepository.findByUsername(identifier.toLowerCase())
                    .orElseThrow(() -> new IllegalArgumentException("Invalid username or password"));
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new IllegalArgumentException("Invalid username or password");
        }

        String token = generateToken(user);
        return buildAuthResponse(token, user);
    }

    public String generateToken(User user) {
        return Jwts.builder()
                .subject(user.getId().toString())
                .issuedAt(new java.util.Date())
                .expiration(new java.util.Date(System.currentTimeMillis() + jwtExpiration))
                .signWith(secretKey)
                .compact();
    }

    @Transactional
    public AuthResponse updateProfile(UUID userId, UpdateProfileRequest request) {
        log.info("[DEBUG] [update profile] userId = {}, request = {}", userId, request);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        log.info("[DEBUG] [update profile] current user from db: id={}, username={}, email={}",
                user.getId(), user.getUsername(), user.getEmail());

        String newUsername = null;
        String newEmail = null;

        // Validate and update username if changed
        if (request.getUsername() != null && !request.getUsername().isBlank()) {
            newUsername = request.getUsername().trim().toLowerCase();
            if (!newUsername.equals(user.getUsername())) {
                // Check username format: 3-20 chars, letters, numbers, underscores only
                if (!newUsername.matches("^[a-z0-9_]{3,20}$")) {
                    throw new IllegalArgumentException("用户名格式不正确");
                }
                // Check username uniqueness (exclude current user)
                if (userRepository.existsByUsername(newUsername)) {
                    throw new IllegalArgumentException("用户名已被占用");
                }
                // Check 30-day restriction
                if (user.getLastUsernameChangeAt() != null) {
                    Instant thirtyDaysAgo = Instant.now().minus(Duration.ofDays(30));
                    if (user.getLastUsernameChangeAt().isAfter(thirtyDaysAgo)) {
                        long daysRemaining = Duration.between(Instant.now(), user.getLastUsernameChangeAt().plus(Duration.ofDays(30))).toDays();
                        throw new IllegalArgumentException("用户名 30 天内只能修改一次，还需 " + daysRemaining + " 天才能再次修改");
                    }
                }
                user.setUsername(newUsername);
                user.setLastUsernameChangeAt(Instant.now());
                log.info("[DEBUG] [update profile] username updated to: {}, lastUsernameChangeAt set to now", newUsername);
            }
        }

        // Validate and update email if changed
        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            newEmail = request.getEmail().trim().toLowerCase();
            if (!newEmail.equals(user.getEmail())) {
                // Email format validation
                if (!newEmail.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
                    throw new IllegalArgumentException("邮箱格式不正确");
                }
                // Check email uniqueness (exclude current user)
                if (userRepository.existsByEmail(newEmail)) {
                    throw new IllegalArgumentException("邮箱已被使用");
                }
                user.setEmail(newEmail);
                log.info("[DEBUG] [update profile] email updated to: {}", newEmail);
            }
        }

        // Update displayName
        if (request.getDisplayName() != null && !request.getDisplayName().isBlank()) {
            user.setDisplayName(request.getDisplayName().trim());
        }

        user = userRepository.save(user);
        log.info("[DEBUG] [update profile] user saved, final state: id={}, username={}, email={}, displayName={}",
                user.getId(), user.getUsername(), user.getEmail(), user.getDisplayName());

        String token = generateToken(user);
        return buildAuthResponse(token, user);
    }

    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest request) {
        log.info("[DEBUG] [change password] userId = {}", userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        log.info("[DEBUG] [change password] verify current password");
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            log.warn("[DEBUG] [change password] current password mismatch");
            throw new IllegalArgumentException("当前密码不正确");
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        log.info("[DEBUG] [change password] password updated success for userId = {}", userId);
    }

    public UUID validateToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return UUID.fromString(claims.getSubject());
        } catch (ExpiredJwtException e) {
            log.warn("[DEBUG] JWT token expired: {}", e.getMessage());
            throw new IllegalArgumentException("Token has expired");
        } catch (MalformedJwtException e) {
            log.warn("[DEBUG] Malformed JWT token: {}", e.getMessage());
            throw new IllegalArgumentException("Invalid token format");
        } catch (UnsupportedJwtException e) {
            log.warn("[DEBUG] Unsupported JWT token: {}", e.getMessage());
            throw new IllegalArgumentException("Unsupported token");
        } catch (SignatureException e) {
            log.warn("[DEBUG] Invalid JWT signature: {}", e.getMessage());
            throw new IllegalArgumentException("Invalid token signature");
        } catch (IllegalArgumentException e) {
            log.warn("[DEBUG] JWT validation failed: {}", e.getMessage());
            throw new IllegalArgumentException("Token validation failed");
        }
    }

    public Optional<User> findUserById(UUID userId) {
        return userRepository.findById(userId);
    }

    private AuthResponse buildAuthResponse(String token, User user) {
        return AuthResponse.builder()
                .accessToken(token)
                .tokenType("Bearer")
                .expiresIn(jwtExpiration)
                .user(AuthResponse.UserResponse.builder()
                        .id(user.getId())
                        .username(user.getUsername())
                        .displayName(user.getDisplayName())
                        .email(user.getEmail())
                        .avatarUrl(user.getAvatarUrl())
                        .lastUsernameChangeAt(user.getLastUsernameChangeAt())
                        .themeMode(user.getThemeMode() != null ? user.getThemeMode() : "system")
                        .isAdmin(Boolean.TRUE.equals(user.getIsAdmin()))
                        .build())
                .build();
    }
}
