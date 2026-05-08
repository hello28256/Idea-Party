package com.ideaparty.service;

import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class ModerationService {

    private static final List<String> BLOCKED_PATTERNS = Arrays.asList(
        "spam",
        "advertisement",
        "http://",
        "https://",
        "<script>",
        "javascript:",
        "@gmail.com",
        "@yahoo.com",
        "@hotmail.com"
    );

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
        "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"
    );

    private static final int MAX_MESSAGE_LENGTH = 2000;
    private static final int MIN_MESSAGE_LENGTH = 1;

    public ModerationResult moderate(String content) {
        if (content == null || content.trim().isEmpty()) {
            return new ModerationResult(false, "Message cannot be empty");
        }

        String trimmed = content.trim();

        if (trimmed.length() < MIN_MESSAGE_LENGTH) {
            return new ModerationResult(false, "Message is too short");
        }

        if (trimmed.length() > MAX_MESSAGE_LENGTH) {
            return new ModerationResult(false, "Message exceeds maximum length of " + MAX_MESSAGE_LENGTH + " characters");
        }

        String lowerContent = trimmed.toLowerCase();

        for (String pattern : BLOCKED_PATTERNS) {
            if (lowerContent.contains(pattern.toLowerCase())) {
                return new ModerationResult(false, "Message contains prohibited content");
            }
        }

        if (EMAIL_PATTERN.matcher(trimmed).find()) {
            return new ModerationResult(false, "Email addresses are not allowed");
        }

        return new ModerationResult(true, null);
    }

    public static class ModerationResult {
        private final boolean allowed;
        private final String reason;

        public ModerationResult(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason = reason;
        }

        public boolean isAllowed() {
            return allowed;
        }

        public String getReason() {
            return reason;
        }
    }
}
