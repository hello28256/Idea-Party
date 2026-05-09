package com.ideaparty.service;

import com.ideaparty.entity.User;
import com.ideaparty.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SettingsService {

    private final UserRepository userRepository;

    public String getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() != null) {
            return authentication.getPrincipal().toString();
        }
        throw new RuntimeException("User not authenticated");
    }

    public String getApiKey() {
        UUID userId = UUID.fromString(getCurrentUserId());
        return userRepository.findById(userId)
                .map(User::getApiKey)
                .orElse(null);
    }

    @Transactional
    public void setApiKey(String apiKey) {
        UUID userId = UUID.fromString(getCurrentUserId());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        user.setApiKey(apiKey);
        userRepository.save(user);
    }

    @Transactional
    public void clearApiKey() {
        setApiKey(null);
    }
}
