package com.ideaparty.dto;

import java.util.List;

/**
 * 热门聊天室（hotRooms.json）的对外响应结构。
 *
 * <p>字段集与前端原本在 RoomListView.vue 硬编码的 roomCardsData 字段保持完全一致，
 * 方便前端切换为 fetch 而无需修改模板渲染逻辑。
 *
 * <p>"热门"卡片点击走 clone 流程：前端用 {@code participants}（中文名）解析 preset，
 * 把每个 preset 角色 clone 到当前用户的角色库，再用 clone 后的 ID 创建群聊。
 * 因此本 DTO 的 participants 仅用作"展示 + clone 入口"，不在此步直接关联 preset 真 id。
 */
public record HotRoomResponse(
        String id,
        String title,
        String cover,
        List<String> participants,
        LatestMessage latestMessage,
        int onlineCount,
        int messageCount,
        boolean isHot
) {
    public record LatestMessage(String sender, String text) {}
}
