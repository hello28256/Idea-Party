package com.ideaparty.dto;

import java.util.List;

public class DiscussionStateEvent {
    private DiscussionPhase phase;
    private List<String> selectedCharacters;
    private String message;

    public DiscussionStateEvent() {}

    public DiscussionStateEvent(DiscussionPhase phase, List<String> selectedCharacters, String message) {
        this.phase = phase;
        this.selectedCharacters = selectedCharacters;
        this.message = message;
    }

    // getters and setters
    public DiscussionPhase getPhase() { return phase; }
    public void setPhase(DiscussionPhase phase) { this.phase = phase; }
    public List<String> getSelectedCharacters() { return selectedCharacters; }
    public void setSelectedCharacters(List<String> selectedCharacters) { this.selectedCharacters = selectedCharacters; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
