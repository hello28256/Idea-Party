package com.ideaparty.dto;

import com.ideaparty.util.ImageUrlResolver;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 头像上传成功的统一响应载荷。
 * <p>
 * 该 DTO 由头像上传接口返回，封装上传后头像的可访问 URL，
 * 由前端在收到响应后直接用于头像预览与持久化绑定（如角色 / 用户头像更新）。
 * 使用 {@link Data} 与 {@link Builder} 是为了让上层（如 Controller 序列化层）能以不可变值对象风格构造结果，
 * 同时保留 Jackson 反序列化的能力。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AvatarUploadResponse {
    /**
     * 上传后头像的对外可访问 URL（通常是 CDN / 静态资源服务地址）。
     * <p>
     * 由后端在头像上传到对象存储后生成；前端拿到该字段后可直接用于头像展示或保存到角色档案。
     */
    private String avatarUrl;

    /**
     * 把 avatarUrl(相对 key 或外网)统一转成完整 OSS URL。
     * 由调用方在序列化前调一次。
     */
    public AvatarUploadResponse resolveImageUrls(ImageUrlResolver resolver) {
        this.avatarUrl = resolver.resolve(this.avatarUrl);
        return this;
    }
}
