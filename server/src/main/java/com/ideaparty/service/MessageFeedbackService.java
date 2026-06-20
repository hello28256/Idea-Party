package com.ideaparty.service;

import com.ideaparty.dto.FeedbackResponse;
import com.ideaparty.dto.SubmitFeedbackRequest;
import com.ideaparty.entity.FeedbackType;
import com.ideaparty.entity.Message;
import com.ideaparty.entity.MessageFeedback;
import com.ideaparty.entity.User;
import com.ideaparty.repository.MessageFeedbackRepository;
import com.ideaparty.repository.MessageRepository;
import com.ideaparty.repository.RoomMemberRepository;
import com.ideaparty.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Optional;
import java.util.UUID;

/**
 * 消息反馈业务服务。
 * 负责处理用户对 AI 角色消息的点赞/点踩/评论等反馈，并与 MessageObservationService 协作
 * 维护每条消息的聚合统计（likes/dislikes/最近反馈时间），供排序和质量评估使用。
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class MessageFeedbackService {

    /** 单条评论允许的最大长度；超过则截断，避免脏数据撑大数据库行与前端展示。 */
    private static final int MAX_COMMENT_LENGTH = 1000;

    /** 反馈表持久化入口；负责按主键/复合条件读写 MessageFeedback。 */
    private final MessageFeedbackRepository feedbackRepository;
    /** 消息表访问入口；提交反馈前需校验消息存在，且消息主体必须是 AI 角色。 */
    private final MessageRepository messageRepository;
    /** 用户表引用入口；通过 getReferenceById 拿到懒代理避免不必要查询，仅用于外键关联。 */
    private final UserRepository userRepository;
    /** 房间成员关系入口；用于鉴权：仅聊天室成员可对其中消息提交反馈。 */
    private final RoomMemberRepository roomMemberRepository;
    /** 消息聚合观察服务；每次反馈变更后触发其 recompute 更新统计与最近活跃时间。 */
    private final MessageObservationService observationService;

    /**
     * 提交或更新反馈。
     * 同一 (messageId, userId) 已有记录则更新，否则插入。
     * 合约：userId 必须为消息所属房间成员；messageId 必须存在且发送方为 AI 角色；
     * DISLIKE 类型必须携带 category；副作用：写反馈表并触发消息聚合重算。
     * 调用方：MessageFeedbackController#submit。
     */
    public FeedbackResponse submit(UUID userId, String messageId, SubmitFeedbackRequest req) {
        log.info("[DEBUG] submit feedback user={} message={} type={}", userId, messageId, req.getType());

        // Message.id is String (project quirk), not UUID — pass through directly.
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new IllegalArgumentException("Message not found: " + messageId));

        if (message.getSenderType() != Message.SenderType.CHARACTER) {
            log.warn("[DEBUG] User {} tried to feedback non-character message {}", userId, messageId);
            throw new IllegalArgumentException("Can only give feedback to AI messages");
        }

        UUID roomId = message.getRoom().getId();
        if (!roomMemberRepository.isMember(roomId, userId)) {
            log.warn("[DEBUG] User {} is not a member of room {}", userId, roomId);
            throw new AccessDeniedException("You are not a member of this room");
        }

        if (req.getType() == FeedbackType.DISLIKE && req.getCategory() == null) {
            throw new IllegalArgumentException("DISLIKE feedback requires a category");
        }

        User userRef = userRepository.getReferenceById(userId);

        Optional<MessageFeedback> existing = feedbackRepository.findByMessageIdAndUserId(messageId, userId);
        MessageFeedback fb = existing.orElseGet(() -> MessageFeedback.builder()
                .message(message)
                .user(userRef)
                .build());

        fb.setType(req.getType());
        fb.setCategory(req.getType() == FeedbackType.DISLIKE ? req.getCategory() : null);
        fb.setComment(normalizeComment(req.getComment()));

        MessageFeedback saved = feedbackRepository.save(fb);
        log.info("[DEBUG] feedback saved id={}", saved.getId());
        recomputeObservation(message);
        return FeedbackResponse.fromEntity(saved);
    }

    /**
     * 重新计算并刷新单条消息的聚合观察值（likes/dislikes/最近反馈时间）。
     * 用 try/catch 包裹是因为观察重算是“最佳努力”的副作用——失败不应回滚用户的反馈操作，
     * 仅记录告警，后续可通过后台任务补齐。
     */
    private void recomputeObservation(Message message) {
        try {
            long likes = feedbackRepository.countByMessageIdAndType(message.getId(), FeedbackType.LIKE);
            long dislikes = feedbackRepository.countByMessageIdAndType(message.getId(), FeedbackType.DISLIKE);
            java.time.Instant last = feedbackRepository.findTopByMessageIdOrderByUpdatedAtDesc(message.getId())
                    .map(MessageFeedback::getUpdatedAt)
                    .orElse(null);
            observationService.recompute(message.getId(), likes, dislikes, last);
        } catch (Exception e) {
            log.warn("[Feedback] observation recompute failed: {}", e.getMessage());
        }
    }

    /**
     * 查询当前用户对某条消息已提交的反馈（用于前端“已点赞/已点踩”高亮回显）。
     * 只读事务；无记录返回 Optional.empty()，由 Controller 决定 200 还是 204。
     */
    @Transactional(readOnly = true)
    public Optional<FeedbackResponse> get(UUID userId, String messageId) {
        return feedbackRepository.findByMessageIdAndUserId(messageId, userId)
                .map(FeedbackResponse::fromEntity);
    }

    /**
     * 删除当前用户对某条消息的反馈（即“取消点赞/点踩”）。
     * 副作用：先删反馈记录，再触发该消息聚合重算以同步统计。
     * 合约：若该用户从未提交过反馈，抛 IllegalArgumentException。
     */
    public void delete(UUID userId, String messageId) {
        log.info("[DEBUG] delete feedback user={} message={}", userId, messageId);
        MessageFeedback fb = feedbackRepository.findByMessageIdAndUserId(messageId, userId)
                .orElseThrow(() -> new IllegalArgumentException("No feedback to delete"));
        feedbackRepository.delete(fb);
        messageRepository.findById(messageId).ifPresent(this::recomputeObservation);
    }

    /**
     * 归一化评论文本：trim 后若超过上限则截断；空白字符串视作未填写返回 null。
     * 为何放在 Service 层而非前端校验：API 是公开接口，必须服务端兜底防止异常长度入库。
     */
    private String normalizeComment(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.length() > MAX_COMMENT_LENGTH
                ? trimmed.substring(0, MAX_COMMENT_LENGTH)
                : trimmed;
    }
}
