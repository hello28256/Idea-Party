package com.ideaparty.service;

import com.ideaparty.dto.AdminMessageObservationItem;
import com.ideaparty.entity.FeedbackCategory;
import com.ideaparty.entity.FeedbackType;
import com.ideaparty.entity.Message;
import com.ideaparty.entity.MessageFeedback;
import com.ideaparty.entity.MessageObservation;
import com.ideaparty.repository.MessageFeedbackRepository;
import com.ideaparty.repository.MessageObservationRepository;
import com.ideaparty.repository.MessageRepository;
import com.ideaparty.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Admin overview: list ALL AI messages (rated + unrated) with feedback rollup.
 * One row per observation, joined with message meta + caller's own feedback row
 * (if any) so the UI can distinguish rated / unrated / aggregated.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class AdminObservationService {

    // 观测快照（feedbackCount / likeCount / dislikeCount 等聚合列）的只读访问入口；列表/详情都从这里查
    private final MessageObservationRepository observationRepository;
    // 真实 message 行（content / streamStatus / room / character 等）的来源，用于在列表里展示消息原文与元数据
    private final MessageRepository messageRepository;
    // 单条反馈记录查询入口；详情里需要回填「当前查看者本人是否给过反馈」以及展示具体反馈内容
    private final MessageFeedbackRepository feedbackRepository;
    // 反查 prompt 发出者（用户）的 username / displayName 拼到行上，让管理员能直接定位到人
    private final UserRepository userRepository;

    /**
     * 分页拉取观测列表（带状态过滤），并把每条 observation 拼上 message 原文 + 上一条 USER 消息作为上下文。
     * 调用方：AdminObservationController#list。
     *
     * @param page         0-based 页码（Spring Data Pageable 语义）
     * @param size         每页大小
     * @param statusFilter null/blank=全部；UNRATED=feedbackCount=0；RATED/AGGREGATED/FEEDBACK_EXISTS=feedbackCount>0
     * @return 扁平化后的 DTO Page，totalElements 取自 observationRepository（不重复 count）
     */
    public Page<AdminMessageObservationItem> list(int page, int size, String statusFilter) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        Specification<MessageObservation> spec = (root, query, cb) -> {
            if (statusFilter == null || statusFilter.isBlank()) {
                return cb.conjunction();
            }
            return switch (statusFilter.toUpperCase()) {
                case "UNRATED" -> cb.equal(root.get("feedbackCount"), 0);
                case "RATED", "AGGREGATED", "FEEDBACK_EXISTS" ->
                    cb.greaterThan(root.get("feedbackCount"), 0);
                default -> cb.conjunction();
            };
        };

        Page<MessageObservation> observations = observationRepository.findAll(spec, pageable);
        if (observations.isEmpty()) return Page.empty(pageable);

        List<String> messageIds = observations.getContent().stream()
                .map(MessageObservation::getMessageId)
                .toList();
        Map<String, Message> messages = new HashMap<>();
        for (String id : messageIds) {
            messageRepository.findById(id).ifPresent(m -> messages.put(id, m));
        }

        List<AdminMessageObservationItem> items = observations.getContent().stream()
                .map(obs -> {
                    Message ai = messages.get(obs.getMessageId());
                    if (ai == null) return toItem(obs, null, null);
                    // Look up the most recent USER message before this AI one.
                    Message priorUser = messageRepository
                            .findPriorUserMessages(
                                    ai.getRoom().getId(),
                                    ai.getCreatedAt(),
                                    org.springframework.data.domain.PageRequest.of(0, 1))
                            .stream().findFirst().orElse(null);
                    return toItem(obs, ai, priorUser);
                })
                .toList();

        return new PageImpl<>(items, pageable, observations.getTotalElements());
    }

    /**
     * 单条观测详情：在列表结果之上再额外带回「当前查看者本人对这条消息的反馈」，
     * 这样管理员能直接看到自己是否已评/评了什么，而不必再调通用反馈接口。
     * 调用方：AdminObservationController#detail。
     *
     * @param messageId    observation / message 共用的主键
     * @param viewerUserId 当前查看者；为 null 时不查 viewerFb（status 退化为 AGGREGATED/UNRATED）
     * @return 完整 DTO，包含 viewerFb 信息
     * @throws IllegalArgumentException 当 observation 或 message 不存在时（数据应 1:1 缺失即视为脏数据）
     */
    @Transactional(readOnly = true)
    public AdminMessageObservationItem detail(String messageId, UUID viewerUserId) {
        MessageObservation obs = observationRepository.findById(messageId)
                .orElseThrow(() -> new IllegalArgumentException("Observation not found: " + messageId));
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new IllegalArgumentException("Message not found: " + messageId));
        MessageFeedback viewerFb = null;
        if (viewerUserId != null) {
            viewerFb = feedbackRepository
                    .findByMessageIdAndUserId(messageId, viewerUserId)
                    .orElse(null);
        }
        Message priorUser = messageRepository
                .findPriorUserMessages(
                        message.getRoom().getId(),
                        message.getCreatedAt(),
                        org.springframework.data.domain.PageRequest.of(0, 1))
                .stream().findFirst().orElse(null);
        return toItem(obs, message, priorUser, viewerFb);
    }

    /**
     * 列表用重载：viewerFb 传 null，status 字段会根据 obs.feedbackCount 推导为 UNRATED/AGGREGATED。
     * 仅在 #list 内部调用，避免在循环里多查一次 feedback 表。
     */
    private AdminMessageObservationItem toItem(MessageObservation obs, Message m, Message priorUser) {
        return toItem(obs, m, priorUser, null);
    }

    /**
     * 详情用重载：把 viewerFb 透传到 status 判断里，命中后 status="RATED"。
     * status 优先级：viewerFb（RATED） > feedbackCount>0（AGGREGATED） > 其它（UNRATED），
     * 这一顺序保证「查看者自己的视角」优先于全局聚合。
     */
    private AdminMessageObservationItem toItem(MessageObservation obs, Message m, Message priorUser, MessageFeedback viewerFb) {
        AdminMessageObservationItem.AdminMessageObservationItemBuilder b = AdminMessageObservationItem.builder()
                .messageId(obs.getMessageId())
                .roomId(obs.getRoomId())
                .characterId(obs.getCharacterId())
                .feedbackCount(obs.getFeedbackCount())
                .likeCount(obs.getLikeCount())
                .dislikeCount(obs.getDislikeCount())
                .lastFeedbackAt(obs.getLastFeedbackAt());

        if (m != null) {
            b.messagePreview(truncate(m.getContent(), 80))
                    .messageCreatedAt(m.getCreatedAt());
            b.streamStatus(m.getStreamStatus() != null ? m.getStreamStatus().name() : "COMPLETE");
            if (m.getRoom() != null) {
                b.roomId(m.getRoom().getId().toString());
                b.roomName(m.getRoom().getName());
            }
            if (m.getCharacter() != null) {
                b.characterId(m.getCharacter().getId().toString());
                b.characterName(m.getCharacter().getName());
            }
        }

        if (priorUser != null) {
            b.userPrompt(truncate(priorUser.getContent(), 80));
            b.userPromptAt(priorUser.getCreatedAt());
            if (priorUser.getUser() != null) {
                b.promptUserId(priorUser.getUser().getId().toString());
                b.promptUsername(priorUser.getUser().getUsername());
                b.promptDisplayName(priorUser.getUser().getDisplayName());
            }
        }

        if (viewerFb != null) {
            b.userId(viewerFb.getUser().getId().toString());
            b.username(viewerFb.getUser().getUsername());
            b.displayName(viewerFb.getUser().getDisplayName());
            b.feedbackType(viewerFb.getType());
            b.feedbackCategory(viewerFb.getCategory());
            b.feedbackComment(viewerFb.getComment());
            b.userFeedbackAt(viewerFb.getUpdatedAt());
            b.status("RATED");
        } else if (obs.getFeedbackCount() != null && obs.getFeedbackCount() > 0) {
            b.status("AGGREGATED");
        } else {
            b.status("UNRATED");
        }
        return b.build();
    }

    /**
     * 预览截断：列表/详情只要前 80 字 + "..."，避免把整段 AI 回复或用户长 prompt 塞进列表响应。
     * 输入 null 时原样返回 null（不抛 NPE，配合 m/priorUser 可能缺失的情况）。
     */
    private String truncate(String s, int n) {
        if (s == null) return null;
        return s.length() > n ? s.substring(0, n) + "..." : s;
    }
}
