package com.ideaparty.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebConfig.class);

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Avatar uploads
        registry.addResourceHandler("/api/upload/avatars/**")
                .addResourceLocations("file:uploads/avatars/")
                .setCachePeriod(31556926); // 1 year in seconds

        // General uploads (for any other upload types)
        String uploadsPath = Paths.get(System.getProperty("user.dir"), "uploads").toAbsolutePath().toString();
        log.info("[WebConfig] Registering uploads static handler: /uploads/** -> file:{}/", uploadsPath);
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + uploadsPath + "/")
                .setCachePeriod(3600);
    }
}
