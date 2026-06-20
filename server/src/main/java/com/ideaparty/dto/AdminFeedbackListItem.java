package com.ideaparty.dto;

import com.ideaparty.entity.FeedbackCategory;
import com.ideaparty.entity.FeedbackType;
import com.ideaparty.entity.MessageFeedback;

import java.time.Instant;

/**
 * 管理员后台「用户反馈列表」专用 DTO。
 * 仅承载列表展示所需的扁平字段（关联的 user/message 信息已展平）,
 * 配合 {@link com.ideaparty.controller.AdminFeedbackController} 的列表查询接口使用,
 * 避免直接序列化 {@link MessageFeedback} 实体以保护内部结构与延迟加载字段。
 */
public class AdminFeedbackListItem {

    /** 消息内容在列表中预览的最大长度;超出则截断并追加 "..." 避免单元格换行/超宽。 */
    private static final int MESSAGE_PREVIEW_LEN = 80;

    /** 反馈主键,转字符串便于前端按字符串处理、避免 JS 数字精度问题。 */
    private String id;
    /** 关联的聊天消息 ID,管理员可点击跳转到具体消息上下文。 */
    private String messageId;
    /** 消息正文截断预览,长度受 {@link #MESSAGE_PREVIEW_LEN} 控制。 */
    private String messagePreview;
    /** 反馈类型(点赞/点踩 等),来自枚举 {@link FeedbackType}。 */
    private FeedbackType type;
    /** 反馈分类(如 内容/性能/其他),来自枚举 {@link FeedbackCategory}。 */
    private FeedbackCategory category;
    /** 用户提交的文本评论,可为空(仅点赞/点踩时无评论)。 */
    private String comment;
    /** 提交反馈的用户 ID,字符串化避免精度问题。 */
    private String userId;
    /** 提交反馈的用户名(登录名),用于列表中快速定位用户。 */
    private String username;
    /** 用户的展示名(昵称),展示优先级高于 username。 */
    private String displayName;
    /** 反馈提交时间,UTC Instant,前端按本地时区格式化。 */
    private Instant createdAt;

    /** MyBatis/Jackson 等反射框架反序列化时需要的无参构造器。 */
    public AdminFeedbackListItem() {}

    /**
     * 将持久化实体 {@link MessageFeedback} 转换为列表展示 DTO。
     * 在转换过程中主动触发懒加载(message/user)并展平字段,避免序列化阶段出现 LazyInitializationException。
     *
     * @param fb 持久化实体,关联的 message 与 user 必须可访问
     * @return 包含展示所需字段的 DTO 实例
     */
    public static AdminFeedbackListItem fromEntity(MessageFeedback fb) {
        AdminFeedbackListItem dto = new AdminFeedbackListItem();
        dto.setId(fb.getId().toString());
        dto.setMessageId(fb.getMessage().getId());
        String content = fb.getMessage().getContent();
        dto.setMessagePreview(content != null && content.length() > MESSAGE_PREVIEW_LEN
                ? content.substring(0, MESSAGE_PREVIEW_LEN) + "..."
                : content);
        dto.setType(fb.getType());
        dto.setCategory(fb.getCategory());
        dto.setComment(fb.getComment());
        dto.setUserId(fb.getUser().getId().toString());
        dto.setUsername(fb.getUser().getUsername());
        dto.setDisplayName(fb.getUser().getDisplayName());
        dto.setCreatedAt(fb.getCreatedAt());
        return dto;
    }

    /** 反馈主键 getter;供 Jackson 序列化输出到管理员前端。 */
    public String getId() { return id; }
    /** 反馈主键 setter;由 {@link #fromEntity(MessageFeedback)} 写入。 */
    public void setId(String id) { this.id = id; }
    /** 关联消息 ID getter;前端用其跳转/定位到原消息。 */
    public String getMessageId() { return messageId; }
    /** 关联消息 ID setter;由 {@link #fromEntity(MessageFeedback)} 写入。 */
    public void setMessageId(String messageId) { this.messageId = messageId; }
    /** 消息预览 getter;展示在列表单元格,长度已截断。 */
    public String getMessagePreview() { return messagePreview; }
    /** 消息预览 setter;由 {@link #fromEntity(MessageFeedback)} 在截断后写入。 */
    public void setMessagePreview(String messagePreview) { this.messagePreview = messagePreview; }
    /** 反馈类型 getter;返回 {@link FeedbackType} 枚举,前端可按枚举值渲染图标/颜色。 */
    public FeedbackType getType() { return type; }
    /** 反馈类型 setter;由 {@link #fromEntity(MessageFeedback)} 写入。 */
    public void setType(FeedbackType type) { this.type = type; }
    /** 反馈分类 getter;返回 {@link FeedbackCategory} 枚举,用于列表筛选/分组。 */
    public FeedbackCategory getCategory() { return category; }
    /** 反馈分类 setter;由 {@link #fromEntity(MessageFeedback)} 写入。 */
    public void setCategory(FeedbackCategory category) { this.category = category; }
    /** 用户评论 getter;可能为 null(纯点赞/点踩时无评论)。 */
    public String getComment() { return comment; }
    /** 用户评论 setter;由 {@link #fromEntity(MessageFeedback)} 写入,允许 null。 */
    public void setComment(String comment) { this.comment = comment; }
    /** 提交者用户 ID getter;字符串形式,前端可用于跳转用户详情。 */
    public String getUserId() { return userId; }
    /** 提交者用户 ID setter;由 {@link #fromEntity(MessageFeedback)} 写入。 */
    public void setUserId(String userId) { this.userId = userId; }
    /** 用户名 getter(登录名);用于列表中非歧义场景下的快速识别。 */
    public String getUsername() { return username; }
    /** 用户名 setter;由 {@link #fromEntity(MessageFeedback)} 写入。 */
    public void setUsername(String username) { this.username = username; }
    /** 用户展示名 getter(昵称);为空时前端回退到 username 展示。 */
    public String getDisplayName() { return displayName; }
    /** 用户展示名 setter;由 {@link #fromEntity(MessageFeedback)} 写入,允许 null。 */
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    /** 反馈创建时间 getter;UTC Instant,前端按本地时区渲染。 */
    public Instant getCreatedAt() { return createdAt; }
    /** 反馈创建时间 setter;由 {@link #fromEntity(MessageFeedback)} 写入。 */
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
