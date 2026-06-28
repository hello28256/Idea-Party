package com.ideaparty.dto;

import java.util.List;
import java.util.UUID;

/**
 * 角色被引用情况查询结果：列出引用了指定角色的全部聊天室，供前端在删除前展示给用户做"是否级联删除"决策。
 *
 * <p>字段设计取舍：
 * <ul>
 *   <li>rooms 已自带 length，但额外冗余 roomCount 让前端模板里 <N> 个聊天室写法更直观，省一个 .length 引用。</li>
 *   <li>只暴露 {id, name}，不暴露 ownerId：上游已鉴权确认是角色 owner，
 *       而按业务约定角色 owner 与引用房间 owner 通常一致，避免向客户端泄露他人信息。</li>
 *   <li>暂不返回 messageCount：消息随房间走 JPA cascade 一并清理，用户无需单独决策。</li>
 * </ul>
 *
 * <p>使用 Java record 让不可变 DTO 更紧凑；Jackson 默认支持 record 序列化。
 */
public record CharacterReferencesResponse(
        UUID characterId,
        int roomCount,
        List<ReferencedRoom> rooms
) {
    public record ReferencedRoom(UUID id, String name) {}
}