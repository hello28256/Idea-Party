package com.ideaparty.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.List;

/**
 * CORS configuration.
 *
 * Allowed origins are configured via the env var APP_CORS_ALLOWED_ORIGINS
 * (comma-separated). When unset or blank, dev defaults are used.
 */
@Configuration
@SuppressWarnings("null") // @NonNullApi null-safety check is over-strict for List/Stream chains here
public class CorsConfig implements WebMvcConfigurer {

    @Value("${app.cors.allowed-origins:${APP_CORS_ALLOWED_ORIGINS:http://localhost:5173,http://localhost:5174}}")
    private String allowedOrigins;

    private static final List<String> DEFAULT_DEV_ORIGINS = List.of(
            "http://localhost:5173", "http://localhost:5174"
    );

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
