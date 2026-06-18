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

    // 上限 100：UI 列表/详情展示需要完整可读，过长会截断并破坏一致性。
    @jakarta.validation.constraints.NotBlank(message = "Room name is required")
    @Size(min = 1, max = 100, message = "Room name must be between 1 and 100 characters")
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

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }

    public List<UUID> getCharacterIds() { return characterIds; }
    public void setCharacterIds(List<UUID> characterIds) { this.characterIds = characterIds; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
}
