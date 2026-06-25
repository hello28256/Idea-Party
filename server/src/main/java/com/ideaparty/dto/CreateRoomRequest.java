package com.ideaparty.dto;

import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * 创建聊天室的入参 DTO。前端在「我的聊天室」页新建房间时提交此结构，
 * 后端用 Bean Validation 在 Controller 层做入参校验，避免脏数据进入 Service / DB。
 * 通过 characterIds 引用已有角色而非嵌入角色数据，保证角色信息只存一份真源（Character 表）。
 */
public class CreateRoomRequest {

    // 名称为可选：前端在「单角色」场景下允许留空并自动用角色名作为房间名；
    // 后端不做强校验，避免阻断该快捷流程。仅保留 100 字上限以保护 UI 展示一致性。
    @Size(max = 100, message = "Room name must be at most 100 characters")
    private String name;

    // 话题为可选补充描述，500 字足以覆盖一段背景说明；过长通常意味着用户误把聊天内容粘进来。
    @Size(max = 500, message = "Topic must be at most 500 characters")
    private String topic;

    // 引用现有角色 ID 而非内嵌角色对象，避免角色信息在多处冗余导致不一致；50 个上限防止恶意/误用拖垮 Moderator 编排。
    @Size(max = 50, message = "At most 50 characters per room")
    private List<UUID> characterIds;

    /**
     * Room conversation shape: "single" (1-on-1 with one character) or
     * "group" (multi-character discussion). Optional — backend defaults to "group".
     */
    // 可选字段：保持前向兼容，老客户端不传也能工作；由 Service 层在缺失时回退到默认 group 行为。
    private String mode;

    // 暴露给 Service / Controller 反序列化回读，由 Jackson 在 HTTP 绑定阶段填充。
    public String getName() { return name; }
    // Jackson 反序列化入口；写入的值会被 Bean Validation 校验后落库。
    public void setName(String name) { this.name = name; }

    // 话题可空读取：UI 在房间卡片副标题展示 null 时回退占位文案。
    public String getTopic() { return topic; }
    // 允许 null：用户新建房间时可以只填名字，稍后再补话题。
    public void setTopic(String topic) { this.topic = topic; }

    // 返回当前请求要绑定的角色 ID 列表，可能为 null（Service 需处理"无角色"场景）。
    public List<UUID> getCharacterIds() { return characterIds; }
    // 由 Controller 在校验后直接传入，供 Service 做"按 ID 解析 Character 实体"的批量查询。
    public void setCharacterIds(List<UUID> characterIds) { this.characterIds = characterIds; }

    // 返回房间模式枚举字符串；null 表示未指定，由 Service 回退到 group 默认行为。
    public String getMode() { return mode; }
    // 写入对话形态；调用方负责传入合法值（"single" / "group"），DTO 层不做枚举转换以保持协议灵活。
    public void setMode(String mode) { this.mode = mode; }
}
