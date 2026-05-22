package com.ideaparty.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModeratorAgentTest {

    @Test
    void testDiscussionPhase_enumValues() {
        // DiscussionPhase enum should have all required values
        assertNotNull(com.ideaparty.dto.DiscussionPhase.IDLE);
        assertNotNull(com.ideaparty.dto.DiscussionPhase.MODERATING);
        assertNotNull(com.ideaparty.dto.DiscussionPhase.SPEAKING);
        assertNotNull(com.ideaparty.dto.DiscussionPhase.WAITING_FOR_USER);
        assertNotNull(com.ideaparty.dto.DiscussionPhase.PAUSED);
    }

    @Test
    void testModeratorMessage_creation() {
        com.ideaparty.dto.ModeratorMessage msg =
            new com.ideaparty.dto.ModeratorMessage("Test content", "INVITE");
        assertEquals("Test content", msg.getContent());
        assertEquals("INVITE", msg.getType());
    }

    @Test
    void testDiscussionStateEvent_creation() {
        com.ideaparty.dto.DiscussionStateEvent event =
            new com.ideaparty.dto.DiscussionStateEvent(
                com.ideaparty.dto.DiscussionPhase.MODERATING,
                java.util.Arrays.asList("char1", "char2"),
                "Test message"
            );
        assertEquals(com.ideaparty.dto.DiscussionPhase.MODERATING, event.getPhase());
        assertEquals(2, event.getSelectedCharacters().size());
        assertEquals("Test message", event.getMessage());
    }
}