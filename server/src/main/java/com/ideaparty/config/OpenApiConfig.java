package com.ideaparty.config;

// Swagger/OpenAPI 模型对象：用于以编程方式构建 OpenAPI 3 规范文档。
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
// Spring 注解：@Configuration 声明配置类，@Bean 用于把自定义 OpenAPI 注入到 IoC 容器。
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot 配置类，集中提供 OpenAPI（Swagger 3）元数据。
 * 存在原因：springdoc-openapi 启动时会从容器中查找一个 {@link OpenAPI} Bean，
 * 没有它则生成的 /v3/api-docs 缺少标题/版本/安全方案等基础信息。
 * 与 {@code springdoc-openapi-starter-webmvc-ui} 自动配置配合，最终在 /swagger-ui.html 暴露可视化文档。
 */
@Configuration
public class OpenApiConfig {

    /**
     * 构建并暴露全局 OpenAPI 文档对象。
     * 入参约束：无（所有内容均在方法体内硬编码）。
     * 副作用：返回的 {@link OpenAPI} 单例由 Spring 容器缓存，整个应用共享同一份元数据。
     * 返回值：携带 API 标题/版本/联系人信息、JWT Bearer 安全方案以及默认安全要求的 OpenAPI 文档。
     * 调用方：springdoc-openapi 自动装配阶段读取，用于生成 /v3/api-docs 与 /swagger-ui。
     */
    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                // 文档标题：与项目品牌一致，供 Swagger UI 顶部展示。
                .title("IdeaParty API")
                // 1.0.0：项目首发版本号，随业务迭代需手动同步，避免与实际接口不一致。
                .version("1.0.0")
                // 简要描述后端定位，便于阅读文档时快速理解系统用途。
                .description("AI Multi-Character Chat Platform Backend API")
                .contact(new Contact()
                    // 团队署名：内部维护者对外的统一联系方式。
                    .name("IdeaParty Team")
                    // 联系邮箱：使用专用 dev 邮箱，避免泄漏个人邮箱。
                    .email("dev@ideaparty.com")))
            .components(new Components()
                // 注册名为 "bearer-key" 的安全方案，供下方 SecurityRequirement 引用。
                .addSecuritySchemes("bearer-key",
                    new SecurityScheme()
                        // 协议族：HTTP，便于 springdoc 渲染为 Bearer 输入框。
                        .type(SecurityScheme.Type.HTTP)
                        // "bearer" 表示使用 Authorization: Bearer <token> 头部传递凭证。
                        .scheme("bearer")
                        // 显式标注 JWT，让前端/调用方知道 token 编码格式。
                        .bearerFormat("JWT")
                        // 安全方案说明：Swagger UI 在 Authorize 弹窗中会展示给用户。
                        .description("JWT Bearer token authentication")))
            // 在全局范围添加安全要求：所有未显式声明 anonymous 的接口默认需要 bearer-key。
            .addSecurityItem(new SecurityRequirement().addList("bearer-key"));
    }
}
