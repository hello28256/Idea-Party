package com.ideaparty.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户偏好更新的请求体 DTO。
 * 存在原因：前端在「设置」面板中切换主题（亮/暗/跟随系统）时，需要一个轻量级载体把选择传回后端，
 * 配合 {@code com.ideaparty.controller.UserPreferencesController} 的 PATCH 接口完成用户主题偏好的持久化。
 * 后续若新增偏好项（如语言、字号），扩展此 DTO 即可，避免引入嵌套对象导致前端改动过大。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdatePreferencesRequest {
    /**
     * 主题模式取值，例如 "light" / "dark" / "system"。
     * 由前端主题切换控件提交，后端透传落库到用户偏好表；保持 String 而非枚举是为了
     * 未来扩展新主题时无需修改 DTO 与 Controller 的反序列化逻辑。
     */
    private String themeMode;
}
