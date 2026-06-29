package com.ideaparty.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 聊天室名称更新请求 DTO。
 * 用于 PATCH /api/rooms/{id}/name 端点：只增量修改聊天室名称，避免 PUT 全量更新时把其它字段（如主题、角色列表）也覆盖掉。
 * name 为必填：与创建时"name 可空 → 兜底为第一个角色名"的策略不同，更名场景下用户显式改名必须非空。
 */
public class UpdateRoomNameRequest {

    /**
     * 聊天室新名称。
     * 上限 100 字符，与 Room 实体的 name 列长度一致，避免 Bean Validation 通过但 DB 写入失败的边界情况。
     */
    @NotBlank(message = "name must not be blank")
    @Size(max = 100, message = "name must be at most 100 characters")
    private String name;

    public String getName() { return name; }

    public void setName(String name) { this.name = name; }
}