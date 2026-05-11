package com.ideaparty.service;

import com.ideaparty.entity.User;
import com.ideaparty.repository.UserRepository;
import com.ideaparty.util.EncryptionUtil;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    private final UserRepository userRepository;
    private final Optional<EncryptionUtil> encryptionUtil;

    public String getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() != null) {
            return authentication.getPrincipal().toString();
        }
        throw new RuntimeException("User not authenticated");
    }

    public String getApiKey() {
        UUID userId = UUID.fromString(getCurrentUserId());
        String apiKey = userRepository.findById(userId)
                .map(User::getApiKey)
                .orElse(null);

        // Decrypt if encryption is enabled and we have an encrypted value
        if (apiKey != null && encryptionUtil.isPresent() && encryptionUtil.get().isEncryptionEnabled()) {
            try {
                return encryptionUtil.get().decrypt(apiKey);
            } catch (RuntimeException e) {
                log.warn("Failed to decrypt API key, returning as-is: {}", e.getMessage());
                return apiKey;
            }
        }

        return apiKey;
    }

    @Transactional
    public void setApiKey(String apiKey) {
        UUID userId = UUID.fromString(getCurrentUserId());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Encrypt if encryption is enabled
        if (apiKey != null && encryptionUtil.isPresent() && encryptionUtil.get().isEncryptionEnabled()) {
            try {
                user.setApiKey(encryptionUtil.get().encrypt(apiKey));
            } catch (RuntimeException e) {
                log.error("Failed to encrypt API key, storing as plain text: {}", e.getMessage());
                user.setApiKey(apiKey);
            }
        } else {
            user.setApiKey(apiKey);
        }

        userRepository.save(user);
    }

    @Transactional
    public void clearApiKey() {
        setApiKey(null);
    }
}
