package com.ideaparty.dto;

import java.util.List;

/**
 * 讨论阶段状态事件：通过 WebSocket 向前端广播当前讨论生命周期所处的阶段。
 *
 * 与 {@link DiscussionPhase} 配合使用：服务端在角色编排、生成、收尾等关键节点推送该事件，
 * 前端据此切换 UI 状态（如显示加载动画、禁用输入框、提示下一轮等）。
 * 之所以独立成 DTO 而非塞进通用消息体，是为了避免在 Socket.IO 通道里携带领域语义不明的混合 payload。
 */
public class DiscussionStateEvent {
    /** 当前讨论所处的阶段（编排中 / 生成中 / 已完成 等），前端据此驱动状态机 UI。 */
    private DiscussionPhase phase;
    /** 本轮参与发言的角色 ID 列表；前端用来高亮或静音特定角色，避免与全局角色集合混淆。 */
    private List<String> selectedCharacters;
    /** 面向用户的中文提示文案，例如"正在收集角色观点…"，失败时承载错误说明以便 Toast 展示。 */
    private String message;

    /** Jackson 反序列化所需的无参构造；Socket.IO 消息体到达时会走这条路径。 */
    public DiscussionStateEvent() {}

    /**
     * 业务构造：一次性写入三个字段，避免推送时多次 setter 产生中间不一致状态被前端观察到。
     *
     * @param phase 当前阶段枚举值
     * @param selectedCharacters 本轮参与角色 ID 列表，传入 null 表示不限定
     * @param message 展示给用户的提示或错误信息
     */
    public DiscussionStateEvent(DiscussionPhase phase, List<String> selectedCharacters, String message) {
        this.phase = phase;
        this.selectedCharacters = selectedCharacters;
        this.message = message;
    }

    // getters and setters
    /**
     * 读取当前讨论阶段；前端 Socket 监听器在每次事件到达时调用，用于判断是否需要切换 UI 状态机。
     * @return 当前阶段枚举，可能为 null（事件刚被反序列化尚未填充）
     */
    public DiscussionPhase getPhase() { return phase; }
    /**
     * 写入当前讨论阶段；由 Moderator Agent 的编排流水线在阶段切换时调用。
     * @param phase 新的阶段枚举，禁止传 null 以免前端状态机卡死
     */
    public void setPhase(DiscussionPhase phase) { this.phase = phase; }
    /**
     * 读取本轮角色 ID 列表；前端用此列表做"正在发言"高亮或投票面板筛选。
     * @return 角色 ID 列表，null 表示未指定（如 IDLE 阶段无需角色）
     */
    public List<String> getSelectedCharacters() { return selectedCharacters; }
    /**
     * 写入本轮角色 ID 列表；由编排服务在选角完成后调用，更新前端的角色可见性。
     * @param selectedCharacters 角色 ID 列表，传 null 视作"全部角色"
     */
    public void setSelectedCharacters(List<String> selectedCharacters) { this.selectedCharacters = selectedCharacters; }
    /**
     * 读取面向用户的提示文案；前端 Toast 组件直接显示该字段内容。
     * @return 提示或错误信息字符串，可能为 null（前端需降级为默认占位）
     */
    public String getMessage() { return message; }
    /**
     * 写入面向用户的提示文案；在状态机切换或异常分支被服务端调用。
     * @param message 中文提示或错误描述，传 null 时前端会展示通用占位
     */
    public void setMessage(String message) { this.message = message; }
}
