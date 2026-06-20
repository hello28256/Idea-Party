package com.ideaparty.service;

import com.ideaparty.entity.FeedbackType;
import com.ideaparty.entity.Message;
import com.ideaparty.entity.MessageObservation;
import com.ideaparty.repository.MessageObservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * 维护管理后台概览所使用的"按消息聚合"观测记录。
 * 每条 AI 消息对应一行观测记录。各计数器由 feedback 服务与 message_feedbacks 表保持同步。
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class MessageObservationService {

    // Spring 注入的 JPA 仓储；final + @RequiredArgsConstructor 让其可测试且不可变。
    private final MessageObservationRepository observationRepository;

    /**
     * 在 AI 消息首次落库时创建对应的观测记录。
     * 当消息尚未分配 id（调用方忘记 flush）或记录已存在时为 no-op，
     * 因此在可能触发两次的消息保存监听器中调用也是安全的。
     *
     * @param message 刚持久化的 AI {@link Message}；要求 id 非空，且已加载 room/character
     */
    public void onAiMessagePersisted(Message message) {
        if (message == null || message.getId() == null) return;
        if (observationRepository.existsById(message.getId())) return;
        MessageObservation obs = MessageObservation.builder()
                .messageId(message.getId())
                .roomId(message.getRoom().getId().toString())
                .characterId(message.getCharacter() != null ? message.getCharacter().getId().toString() : null)
                .feedbackCount(0)
                .likeCount(0)
                .dislikeCount(0)
                .build();
        observationRepository.save(obs);
    }

    /**
     * 为该聚合表出现之前已写入的消息做幂等补种。
     * 用于一次性回填路径，使历史消息无需重跑 AI 流水线即可获得观测记录。允许记录已存在。
     *
     * @param messageId   历史 {@link Message} 的主键
     * @param roomId      所属房间 id；以 String 存储，便于在 schema 层与实体的 FK 类型解耦
     * @param characterId 发言者 id；系统/旁白类消息允许为 null
     */
    public void ensureExists(String messageId, UUID roomId, UUID characterId) {
        if (observationRepository.existsById(messageId)) return;
        MessageObservation obs = MessageObservation.builder()
                .messageId(messageId)
                .roomId(roomId.toString())
                .characterId(characterId != null ? characterId.toString() : null)
                .build();
        observationRepository.save(obs);
    }

    /**
     * 根据当前 message_feedbacks 重新计算指定消息的计数器。
     * 在提交/删除反馈后调用，以保持聚合表准确。当对应行不存在时抛错，
     * 提示调用方该消息处于不一致状态（必须先通过 {@link #onAiMessagePersisted}
     * 或 {@link #ensureExists} 完成初始化）。
     *
     * @param messageId      要更新的消息观测 id
     * @param likeCount      上游统计得到的点赞数
     * @param dislikeCount   上游统计得到的点踩数
     * @param lastFeedbackAt 最近一次反馈的时间戳，用于排序/筛选
     */
    public void recompute(String messageId, long likeCount, long dislikeCount, java.time.Instant lastFeedbackAt) {
        MessageObservation obs = observationRepository.findById(messageId)
                .orElseThrow(() -> new IllegalStateException("Observation missing for " + messageId));
        int total = (int) (likeCount + dislikeCount);
        obs.setLikeCount((int) likeCount);
        obs.setDislikeCount((int) dislikeCount);
        obs.setFeedbackCount(total);
        obs.setLastFeedbackAt(lastFeedbackAt);
        observationRepository.save(obs);
    }

    /**
     * 读取单条观测记录，供管理端/概览页查询。
     * 只读事务：让 Hibernate 跳过脏检查，并在未来接入读副本时能良好兼容。
     *
     * @param messageId 要查询的消息 id
     * @return 对应的观测记录；若尚未初始化聚合行则返回 empty
     */
    @Transactional(readOnly = true)
    public Optional<MessageObservation> find(String messageId) {
        return observationRepository.findById(messageId);
    }
}
