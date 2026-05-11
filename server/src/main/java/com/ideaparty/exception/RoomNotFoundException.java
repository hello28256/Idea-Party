package com.ideaparty.exception;

/**
 * Exception thrown when a requested room is not found.
 */
public class RoomNotFoundException extends RuntimeException {

    public RoomNotFoundException(String message) {
        super(message);
    }

    public RoomNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
