package com.ideaparty.dto;

/**
 * 聊天室发送消息的入参 DTO。
 * 由前端 ChatRoomView 提交，服务端根据 senderType 分流到「用户发言」或「触发 AI 角色回复」两条路径。
 * 使用普通 setter 而非 record，因为早期版本依赖 Jackson 反序列化到可变 POJO，后续切换前保持稳定。
 */
public class SendMessageRequest {

    // 消息正文：USER 时为用户输入的文本；CHARACTER 时为 AI 生成的回答文本。
    // 长度上限由 Service 层校验，DTO 层不做强制约束以兼容历史数据迁移。
    private String content;
    // 区分消息来源：USER 表示真人用户发言（直接广播），CHARACTER 表示 AI 角色回复（仅写库+广播）。
    // 用字符串而非枚举，避免前端/历史日志的耦合；后端在 Service 层做合法性校验。
    private String senderType; // 'USER' or 'CHARACTER'
    // 仅当 senderType=CHARACTER 时使用，指向 room_member 中挂载的角色 ID；USER 发言可为空。
    // 冗余传入而非仅依赖上下文，是因为 Moderator 编排阶段需要按角色 ID 检索角色 prompt/角色卡。
    private String characterId;

    /** 获取消息正文。 */
    public String getContent() { return content; }
    /** 设置消息正文。 */
    public void setContent(String content) { this.content = content; }

    /** 获取发送方类型（USER / CHARACTER）。 */
    public String getSenderType() { return senderType; }
    /** 设置发送方类型（USER / CHARACTER）。 */
    public void setSenderType(String senderType) { this.senderType = senderType; }

    /** 获取 AI 角色 ID（仅 CHARACTER 类型消息使用）。 */
    public String getCharacterId() { return characterId; }
    /** 设置 AI 角色 ID（仅 CHARACTER 类型消息使用）。 */
    public void setCharacterId(String characterId) { this.characterId = characterId; }
}
