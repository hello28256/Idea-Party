package com.ideaparty.repository;

import com.ideaparty.entity.FeedbackType;
import com.ideaparty.entity.MessageFeedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * 反馈 Repository。
 * 继承 JpaSpecificationExecutor 以支持管理后台的动态筛选（category/userId/type/时间范围）。
 */
@Repository
public interface MessageFeedbackRepository
        extends JpaRepository<MessageFeedback, UUID>, JpaSpecificationExecutor<MessageFeedback> {

    /**
     * 查找「指定用户对指定消息」的唯一一条反馈。
     * 依赖实体上 uk_msg_user 唯一约束 (message_id, user_id)，因此最多返回一条；
     * 反馈业务侧（toggle/upsert 反馈）会先调此方法判断是否已存在，再决定新增还是修改。
     *
     * @param messageId 被反馈的 AI 消息主键
     * @param userId    提交反馈的用户主键
     * @return 命中则返回该条反馈；不存在时返回 Optional.empty()
     */
    Optional<MessageFeedback> findByMessageIdAndUserId(String messageId, UUID userId);

    /**
     * 统计某条 AI 消息下指定反馈类型（LIKE/DISLIKE/REPORT 等）的累计数量。
     * 给前端展示「👍 N / 👎 N」等计数使用，命中后端聚合可避免前端逐条拉取再计数。
     *
     * @param messageId 被统计的 AI 消息主键
     * @param type      要统计的反馈类型枚举
     * @return 该消息下指定类型的反馈总数；无反馈时返回 0
     */
    long countByMessageIdAndType(String messageId, FeedbackType type);

    /**
     * 获取某条消息的「最近一次」反馈记录。
     * 后台审核或运营面板会取最近一条反馈用于快速查看最新用户评价，OrderByUpdatedAtDesc 保证拿到的是最近修改的那条（覆盖了分类/评论被改写的情况）。
     *
     * @param messageId 被查询的 AI 消息主键
     * @return 该消息最近的一条反馈；若该消息尚无任何反馈则返回 Optional.empty()
     */
    Optional<MessageFeedback> findTopByMessageIdOrderByUpdatedAtDesc(String messageId);
}
