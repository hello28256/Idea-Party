package com.ideaparty.service;

import com.ideaparty.dto.FeedbackResponse;
import com.ideaparty.dto.SubmitFeedbackRequest;
import com.ideaparty.entity.FeedbackCategory;
import com.ideaparty.entity.FeedbackType;
import com.ideaparty.entity.Message;
import com.ideaparty.entity.MessageFeedback;
import com.ideaparty.entity.Room;
import com.ideaparty.entity.User;
import com.ideaparty.repository.MessageFeedbackRepository;
import com.ideaparty.repository.MessageRepository;
import com.ideaparty.repository.RoomMemberRepository;
import com.ideaparty.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageFeedbackServiceTest {

    @Mock private MessageFeedbackRepository feedbackRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private UserRepository userRepository;
    @Mock private RoomMemberRepository roomMemberRepository;

    @InjectMocks private MessageFeedbackService service;

    private UUID userId;
    private UUID roomId;
    private User testUser;
    private Room testRoom;
    private Message testMessage;
    private String messageId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        roomId = UUID.randomUUID();
        messageId = UUID.randomUUID().toString();

        testUser = User.builder().id(userId).username("alice").displayName("Alice").build();
        testRoom = Room.builder().id(roomId).name("r").owner(testUser).build();
        testMessage = new Message();
        testMessage.setId(messageId);
        testMessage.setContent("hi");
        testMessage.setSenderType(Message.SenderType.CHARACTER);
        testMessage.setRoom(testRoom);
    }

    private SubmitFeedbackRequest req(FeedbackType t, FeedbackCategory c, String comment) {
        SubmitFeedbackRequest r = new SubmitFeedbackRequest();
        r.setType(t);
        r.setCategory(c);
        r.setComment(comment);
        return r;
    }

    @Test
    @DisplayName("submit: first time = INSERT, second time = UPDATE")
    void submit_upsertBehavior() {
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(testMessage));
        when(roomMemberRepository.isMember(roomId, userId)).thenReturn(true);
        when(userRepository.getReferenceById(userId)).thenReturn(testUser);
        when(feedbackRepository.findByMessageIdAndUserId(messageId, userId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(MessageFeedback.builder()
                        .id(UUID.randomUUID()).message(testMessage).user(testUser)
                        .type(FeedbackType.LIKE).createdAt(Instant.now()).updatedAt(Instant.now())
                        .build()));
        when(feedbackRepository.save(any(MessageFeedback.class))).thenAnswer(inv -> {
            MessageFeedback fb = inv.getArgument(0);
            if (fb.getId() == null) fb.setId(UUID.randomUUID());
            return fb;
        });

        // First call: insert path
        FeedbackResponse r1 = service.submit(userId, messageId, req(FeedbackType.LIKE, null, null));
        assertEquals(FeedbackType.LIKE, r1.getType());
        assertNull(r1.getCategory());

        // Second call: update path (existing record returned)
        FeedbackResponse r2 = service.submit(userId, messageId, req(FeedbackType.DISLIKE, FeedbackCategory.IRRELEVANT, "wrong"));
        assertEquals(FeedbackType.DISLIKE, r2.getType());
        assertEquals(FeedbackCategory.IRRELEVANT, r2.getCategory());
        assertEquals("wrong", r2.getComment());

        verify(feedbackRepository, times(2)).save(any(MessageFeedback.class));
    }

    @Test
    @DisplayName("submit: DISLIKE without category throws IllegalArgumentException")
    void submit_dislikeWithoutCategory() {
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(testMessage));
        when(roomMemberRepository.isMember(roomId, userId)).thenReturn(true);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.submit(userId, messageId, req(FeedbackType.DISLIKE, null, null))
        );
        assertTrue(ex.getMessage().contains("category"));
        verify(feedbackRepository, never()).save(any());
    }

    @Test
    @DisplayName("submit: USER message (not CHARACTER) throws IllegalArgumentException")
    void submit_rejectsUserMessage() {
        testMessage.setSenderType(Message.SenderType.USER);
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(testMessage));

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.submit(userId, messageId, req(FeedbackType.LIKE, null, null))
        );
        assertTrue(ex.getMessage().contains("AI messages"));
        verify(feedbackRepository, never()).save(any());
    }

    @Test
    @DisplayName("submit: non-member throws AccessDeniedException")
    void submit_rejectsNonMember() {
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(testMessage));
        when(roomMemberRepository.isMember(roomId, userId)).thenReturn(false);

        assertThrows(
                AccessDeniedException.class,
                () -> service.submit(userId, messageId, req(FeedbackType.LIKE, null, null))
        );
        verify(feedbackRepository, never()).save(any());
    }

    @Test
    @DisplayName("submit: message not found throws IllegalArgumentException")
    void submit_messageNotFound() {
        when(messageRepository.findById(messageId)).thenReturn(Optional.empty());

        assertThrows(
                IllegalArgumentException.class,
                () -> service.submit(userId, messageId, req(FeedbackType.LIKE, null, null))
        );
        verify(feedbackRepository, never()).save(any());
    }

    @Test
    @DisplayName("submit: comment over 1000 chars is truncated")
    void submit_truncatesLongComment() {
        String tooLong = "a".repeat(1500);

        when(messageRepository.findById(messageId)).thenReturn(Optional.of(testMessage));
        when(roomMemberRepository.isMember(roomId, userId)).thenReturn(true);
        when(userRepository.getReferenceById(userId)).thenReturn(testUser);
        when(feedbackRepository.findByMessageIdAndUserId(messageId, userId)).thenReturn(Optional.empty());
        when(feedbackRepository.save(any(MessageFeedback.class))).thenAnswer(inv -> {
            MessageFeedback fb = inv.getArgument(0);
            if (fb.getId() == null) fb.setId(UUID.randomUUID());
            return fb;
        });

        ArgumentCaptor<MessageFeedback> captor = ArgumentCaptor.forClass(MessageFeedback.class);

        FeedbackResponse r = service.submit(userId, messageId, req(FeedbackType.DISLIKE, FeedbackCategory.OTHER, tooLong));

        verify(feedbackRepository).save(captor.capture());
        assertEquals(1000, captor.getValue().getComment().length());
        assertEquals(1000, r.getComment().length());
    }

    @Test
    @DisplayName("delete: missing record throws IllegalArgumentException")
    void delete_missing() {
        when(feedbackRepository.findByMessageIdAndUserId(messageId, userId)).thenReturn(Optional.empty());

        assertThrows(
                IllegalArgumentException.class,
                () -> service.delete(userId, messageId)
        );
        verify(feedbackRepository, never()).delete(any(MessageFeedback.class));
    }

    @Test
    @DisplayName("delete: existing record is deleted")
    void delete_success() {
        MessageFeedback fb = MessageFeedback.builder()
                .id(UUID.randomUUID()).message(testMessage).user(testUser)
                .type(FeedbackType.LIKE).createdAt(Instant.now()).updatedAt(Instant.now())
                .build();
        when(feedbackRepository.findByMessageIdAndUserId(messageId, userId)).thenReturn(Optional.of(fb));

        service.delete(userId, messageId);
        verify(feedbackRepository).delete(fb);
    }

    @Test
    @DisplayName("get: returns Optional.empty when no feedback")
    void get_empty() {
        when(feedbackRepository.findByMessageIdAndUserId(messageId, userId)).thenReturn(Optional.empty());

        Optional<FeedbackResponse> result = service.get(userId, messageId);
        assertTrue(result.isEmpty());
    }
}
