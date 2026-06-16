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

    Optional<MessageFeedback> findByMessageIdAndUserId(String messageId, UUID userId);

    long countByMessageIdAndType(String messageId, FeedbackType type);

    Optional<MessageFeedback> findTopByMessageIdOrderByUpdatedAtDesc(String messageId);
}
