package com.ideaparty.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.List;

/**
 * CORS 配置类。
 *
 * 允许的来源通过环境变量 APP_CORS_ALLOWED_ORIGINS（以逗号分隔）配置。
 * 当该变量未设置或为空时，使用开发环境的默认值。
 */
@Configuration
@SuppressWarnings("null") // @NonNullApi null-safety check is over-strict for List/Stream chains here
public class CorsConfig implements WebMvcConfigurer {

    /**
     * 从配置注入的原始逗号分隔 CORS 白名单字符串。
     * <p>
     * 解析顺序：Spring 属性 {@code app.cors.allowed-origins} → 环境变量
     * {@code APP_CORS_ALLOWED_ORIGINS} → 开发环境兜底值 {@code http://localhost:5173,http://localhost:5174}。
     * 保持为单一字符串，这样同一份配置源既能驱动 Spring 配置文件，
     * 也能驱动容器环境变量，避免把配置拆成两个 key。
     */
    @Value("${app.cors.allowed-origins:${APP_CORS_ALLOWED_ORIGINS:http://localhost:5173,http://localhost:5174}}")
    private String allowedOrigins;

    /**
     * 当环境变量缺失、为空、或只包含逗号/空白时使用的兜底白名单。
     * 与前端团队使用的 Vite 开发服务器端口保持一致，
     * 这样本地执行 {@code npm run dev} 即可开箱即用，无需额外的环境配置。
     */
    private static final List<String> DEFAULT_DEV_ORIGINS = List.of(
            "http://localhost:5173", "http://localhost:5174"
    );

    /**
     * 注册由 Spring MVC 的 {@code DispatcherServlet} 使用的全局 CORS 映射。
     * <p>
     * 应用于所有路径（{@code /**}），因此 REST 接口、错误页面以及未来可能加入的
     * 静态资源处理器共享同一套策略。开启凭据支持是因为前端会发送 JWT
     * {@code Authorization} 头；{@code maxAge=3600} 让浏览器可以缓存
     * 预检请求一小时，避免每次请求都多一次 OPTIONS 往返。
     *
     * @param registry Spring MVC 的 CORS 注册表；不能为 null（调用方是框架本身）。
     */
    @Override
    public void addCorsMappings(@NonNull CorsRegistry registry) {
        List<String> origins = parseOrigins(allowedOrigins);

        registry.addMapping("/**")
                .allowedOrigins(origins.toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type", "X-Requested-With")
                .allowCredentials(true)
                .maxAge(3600);
    }

    /**
     * 将原始白名单字符串规整成一份去空白、非空的来源列表。
     * <p>
     * 集中在这里处理，是为了防止空白条目（例如 {@code APP_CORS_ALLOWED_ORIGINS}
     * 末尾多余的逗号）污染 CORS 注册结果，同时也让完全为空的值能够平滑降级到
     * 开发环境默认值，而不是直接拒绝所有跨域请求。
     *
     * @param raw 逗号分隔的来源；允许为 null 或空白。
     * @return 非空、非 null 的去空白后来源列表；当入参为 null、空白或
     *         拆分后为空时，回退到 {@link #DEFAULT_DEV_ORIGINS}。
     */
    @NonNull
    private List<String> parseOrigins(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_DEV_ORIGINS;
        }
        List<String> list = Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        return list.isEmpty() ? DEFAULT_DEV_ORIGINS : list;
    }
}
