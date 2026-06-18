package com.ideaparty.dto;

/**
 * 聊天室发送消息的入参 DTO。
 * 由前端 ChatRoomView 提交，服务端根据 senderType 分流到「用户发言」或「触发 AI 角色回复」两条路径。
 * 使用普通 setter 而非 record，因为早期版本依赖 Jackson 反序列化到可变 POJO，后续切换前保持稳定。
 */
public class SendMessageRequest {

    private String content;
    // 区分消息来源：USER 表示真人用户发言（直接广播），CHARACTER 表示 AI 角色回复（仅写库+广播）。
    // 用字符串而非枚举，避免前端/历史日志的耦合；后端在 Service 层做合法性校验。
    private String senderType; // 'USER' or 'CHARACTER'
    // 仅当 senderType=CHARACTER 时使用，指向 room_member 中挂载的角色 ID；USER 发言可为空。
    // 冗余传入而非仅依赖上下文，是因为 Moderator 编排阶段需要按角色 ID 检索角色 prompt/角色卡。
    private String characterId;

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getSenderType() { return senderType; }
    public void setSenderType(String senderType) { this.senderType = senderType; }

    public String getCharacterId() { return characterId; }
    public void setCharacterId(String characterId) { this.characterId = characterId; }
}
