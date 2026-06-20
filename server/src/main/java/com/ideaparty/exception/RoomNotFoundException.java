package com.ideaparty.exception;

/**
 * Domain exception signalling that a chat room lookup failed.
 *
 * <p>Raised by the room service / repository layer when a caller references a room id
 * (or other key) that does not exist in the persistent store. Surfaces as a 404-style
 * failure to the controller layer, which the global exception handler maps to a
 * structured API error response.
 *
 * <p>Extends {@link RuntimeException} (unchecked) so service code can throw it
 * without polluting every method signature with a checked-exception clause.
 */
public class RoomNotFoundException extends RuntimeException {

    /**
     * Build an exception carrying only a human-readable diagnostic message.
     *
     * <p>Use this overload when the failure is detected by the throwing code itself
     * (e.g. an {@code Optional.isEmpty()} branch) and no underlying cause needs to be
     * preserved for stack-trace forensics.
     *
     * @param message description of what was missing; surfaced to the API client
     */
    public RoomNotFoundException(String message) {
        super(message);
    }

    /**
     * Build an exception that wraps a lower-level cause (e.g. a JPA / JDBC failure
     * encountered while attempting to load the room).
     *
     * <p>Wrapping the cause keeps the original stack trace available for debugging
     * while still presenting a domain-level signal to upstream layers.
     *
     * @param message description surfaced to the API client
     * @param cause   the underlying throwable that triggered this domain failure
     */
    public RoomNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
