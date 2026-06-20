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
     * Registers the global CORS mapping consumed by Spring MVC's {@code DispatcherServlet}.
     * <p>
     * Applies to every path ({@code /**}) so REST endpoints, error pages, and any future
     * static handlers share one policy. Credentials are enabled because the frontend
     * sends the JWT {@code Authorization} header; {@code maxAge=3600} lets the browser
     * cache the preflight for an hour to avoid an OPTIONS round-trip on every request.
     *
     * @param registry Spring MVC CORS registry; must not be null (caller is the framework).
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
     * Normalises the raw allow-list string into a trimmed, non-empty list of origins.
     * <p>
     * Centralised so blank entries (e.g. a trailing comma in {@code APP_CORS_ALLOWED_ORIGINS})
     * cannot poison the CORS registration, and so an entirely empty value degrades
     * gracefully to the dev defaults instead of denying every cross-origin request.
     *
     * @param raw comma-separated origins; may be null or blank.
     * @return non-empty, non-null list of trimmed origins; falls back to
     *         {@link #DEFAULT_DEV_ORIGINS} when the input is null, blank, or empty after splitting.
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
