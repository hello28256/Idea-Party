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

/**
 * 管理后台反馈查询服务。
 * 为 Admin Feedback Controller 提供分页检索与详情加载能力，避免控制器直接拼装 JPA Specification。
 * 与 {@link MessageFeedbackRepository}、{@link MessageRepository} 协作，把实体翻译为前端展示用的 DTO。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class AdminFeedbackService {

    // 反馈主表仓储：负责 MessageFeedback 的 Specification 查询与按 ID 查找。
    private final MessageFeedbackRepository feedbackRepository;
    // 消息仓储：详情页需要回溯用户提问原文，因此复用其自定义查询方法。
    private final MessageRepository messageRepository;

    /**
     * 分页查询反馈列表，支持按类型/分类/用户关键字/时间区间过滤。
     *
     * @param page         0-based 页码，由 Controller 从 query 参数解析
     * @param size         每页条数，调用方需自行做上限校验
     * @param type         可选的反馈类型过滤（LIKE / DISLIKE / REPORT 等）
     * @param category     可选的反馈分类过滤
     * @param userKeyword  可选的用户名/昵称模糊匹配关键字，大小写不敏感
     * @param from         可选的创建时间下界（包含）
     * @param to           可选的创建时间上界（不包含）
     * @return 翻译为 {@link AdminFeedbackListItem} 的分页结果，按 createdAt 倒序
     */
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
        // 固定按 createdAt 倒序：管理后台期望最新的反馈排在最前面。
        return feedbackRepository.findAll(spec, pageable).map(AdminFeedbackListItem::fromEntity);
    }

    /**
     * 加载单条反馈详情，并尽量补齐触发该 AI 回复的用户提问上下文。
     *
     * @param id 反馈主键 ID
     * @return 包含反馈主体与（若能查到）用户提问原文的详情 DTO
     * @throws IllegalArgumentException 当 ID 对应的反馈不存在时抛出，由 Controller 转为 404
     */
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
            // 即便上下文加载失败也要返回主反馈信息，避免 500 影响后台排查体验；只 warn 即可。
            log.warn("[AdminFeedback] failed to load prior user message: {}", e.getMessage());
        }
        return dto;
    }
}
