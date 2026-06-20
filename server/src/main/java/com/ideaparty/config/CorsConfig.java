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

    /**
     * Raw comma-separated CORS allow-list injected from configuration.
     * <p>
     * Resolution order: Spring property {@code app.cors.allowed-origins} → env var
     * {@code APP_CORS_ALLOWED_ORIGINS} → dev fallback {@code http://localhost:5173,http://localhost:5174}.
     * Kept as a single string so the same source can drive both Spring property files and
     * container env vars without splitting config across two keys.
     */
    @Value("${app.cors.allowed-origins:${APP_CORS_ALLOWED_ORIGINS:http://localhost:5173,http://localhost:5174}}")
    private String allowedOrigins;

    /**
     * Fallback allow-list used when the env var is missing/blank or only contains
     * commas/whitespace. Matches the Vite dev server ports the frontend team uses,
     * so local {@code npm run dev} works out-of-the-box without extra env setup.
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
