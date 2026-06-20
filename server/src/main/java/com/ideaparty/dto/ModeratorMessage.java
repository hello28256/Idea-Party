package com.ideaparty.dto;

/**
 * 主持人（Moderator Agent）的输出消息 DTO。
 * 用于把主持人对"下一步谁发言 / 总结 / 邀请发言"的决策以结构化形式下发给前端，
 * 配合 RoomService / ModeratorService 完成群聊编排逻辑，避免前端直接解析自然语言。
 */
public class ModeratorMessage {
    /** 主持人返回的文本内容；按 type 字段含义不同，可能是一句总结、邀请话术或选中说明。 */
    private String content;
    /**
     * 主持人消息语义类型，取值见字段后内联枚举：INVITE（点名邀请某角色发言）/ SUMMARY（轮次总结）/ SELECT（选出下一发言人）。
     * 当前以字符串承载是为了兼容 Moderator 大模型非严格 JSON 输出，待稳定后可替换为 enum。
     */
    private String type; // "INVITE", "SUMMARY", "SELECT"

    /** 给 Jackson 反序列化用的无参构造；WebSocket 入站消息会走它。 */
    public ModeratorMessage() {}

    /**
     * 业务代码内部使用的全量构造器，一次性传入 content 与 type，避免后续多次 setter。
     *
     * @param content 主持人输出文本，见字段说明
     * @param type    语义类型，取值见字段说明
     */
    public ModeratorMessage(String content, String type) {
        this.content = content;
        this.type = type;
    }

    // getters and setters
    /** 主持人文本内容读取入口；前端 store / 推送给客户端时调用。 */
    public String getContent() { return content; }
    /** 主持人文本内容写入入口；Moderator 大模型回填后调用。 */
    public void setContent(String content) { this.content = content; }
    /** 主持人消息类型读取入口；前端据此决定渲染邀请气泡 / 总结面板 / 选角指示。 */
    public String getType() { return type; }
    /** 主持人消息类型写入入口；Moderator 解析阶段或单元测试构造数据时调用。 */
    public void setType(String type) { this.type = type; }
}
