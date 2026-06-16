package com.ideaparty.service;

import com.ideaparty.dto.AdminFeedbackDetail;
import com.ideaparty.dto.AdminFeedbackListItem;
import com.ideaparty.entity.FeedbackCategory;
import com.ideaparty.entity.FeedbackType;
import com.ideaparty.entity.Message;
import com.ideaparty.entity.MessageFeedback;
import com.ideaparty.repository.MessageFeedbackRepository;
import com.ideaparty.repository.MessageRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class AdminFeedbackService {

    private final MessageFeedbackRepository feedbackRepository;
    private final MessageRepository messageRepository;

    public Page<AdminFeedbackListItem> list(
            int page, int size,
            FeedbackType type, FeedbackCategory category,
            String userKeyword,
            Instant from, Instant to) {

        Specification<MessageFeedback> spec = (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            if (type != null) preds.add(cb.equal(root.get("type"), type));
            if (category != null) preds.add(cb.equal(root.get("category"), category));
            if (userKeyword != null && !userKeyword.isBlank()) {
                // Match username OR displayName containing the keyword (case-insensitive)
                String like = "%" + userKeyword.toLowerCase() + "%";
                preds.add(cb.or(
                        cb.like(cb.lower(root.get("user").get("username")), like),
                        cb.like(cb.lower(root.get("user").get("displayName")), like)
                ));
            }
            if (from != null) preds.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            if (to != null) preds.add(cb.lessThan(root.get("createdAt"), to));
            return cb.and(preds.toArray(new Predicate[0]));
        };

        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return feedbackRepository.findAll(spec, pageable).map(AdminFeedbackListItem::fromEntity);
    }

    public AdminFeedbackDetail detail(UUID id) {
        MessageFeedback fb = feedbackRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Feedback not found: " + id));
        AdminFeedbackDetail dto = AdminFeedbackDetail.fromEntity(fb);

        // Look up the USER message that prompted this AI reply so admins see full context.
        Message aiMessage = fb.getMessage();
        try {
            messageRepository
                    .findPriorUserMessages(
                            aiMessage.getRoom().getId(),
                            aiMessage.getCreatedAt(),
                            org.springframework.data.domain.PageRequest.of(0, 1))
                    .stream().findFirst()
                    .ifPresent(prior -> {
                        dto.setUserPrompt(prior.getContent());
                        dto.setUserPromptAt(prior.getCreatedAt());
                    });
        } catch (Exception e) {
            log.warn("[AdminFeedback] failed to load prior user message: {}", e.getMessage());
        }
        return dto;
    }
}
