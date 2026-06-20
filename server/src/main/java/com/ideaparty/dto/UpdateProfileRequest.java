package com.ideaparty.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户资料更新请求载体。
 * 用于用户在「个人资料」页面提交可编辑字段时的入参封装，
 * 配合 AuthController 的 /api/auth/profile PATCH 接口使用，由 Service 层做字段级校验与持久化。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateProfileRequest {
    /**
     * 登录账号名（不可重复的标识）。
     * 修改走独立的「改账号名」流程以避免越权，常规资料更新接口一般忽略此字段，保留是为了兼容旧版前端 payload。
     */
    private String username;
    /**
     * 用户对外展示的昵称，可重复、可随时修改。
     * 聊天室成员列表、消息气泡等 UI 区域优先展示此字段。
     */
    private String displayName;
    /**
     * 用户邮箱，用于找回密码与系统通知。
     * 修改时会下发确认邮件，故单独抽出以让前端做格式校验。
     */
    private String email;
}
