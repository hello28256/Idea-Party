package com.ideaparty.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

/**
 * Web MVC 配置类：注册本地静态资源处理器，把用户上传的头像与其他文件以 HTTP 形式暴露给前端。
 * 之所以单独存在而不是用 application.yml 的 spring.web.resources.static-locations，是因为头像路径
 * ({@code /api/upload/avatars/**}) 需要保持 URL 形态与上传接口语义对齐，而通用 {@code /uploads/**}
 * 需要基于进程工作目录动态解析绝对路径，避免开发/部署环境目录差异导致资源 404。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * 类级 SLF4J 日志器：用于在启动阶段打印静态资源映射所对应的实际物理路径，
     * 方便运维/调试时确认 uploads 目录指向正确（避免相对路径在不同启动方式下解析不一致）。
     */
    private static final Logger log = LoggerFactory.getLogger(WebConfig.class);

    /**
     * 注册两类静态资源映射：
     * 1) {@code /api/upload/avatars/**} → 相对路径 {@code uploads/avatars/}，缓存 1 小时
     *    原因：预设头像文件名是固定的（如 {@code wang-xing.jpg}），运营可能随时替换；
     *    之前设 1 年会导致浏览器缓存住旧的占位图，新头像不生效（2026-06-28 踩坑）。
     * 2) {@code /uploads/**} → 基于 {@code user.dir} 解析的绝对路径，缓存 1 小时
     *    （通用文件可能频繁覆盖，保守缓存避免脏读）。
     *
     * @param registry Spring MVC 提供的资源处理器注册表，启动时由框架注入；本方法不直接返回值，
     *                 所有配置通过调用 {@code registry.addResourceHandler(...)} 完成。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 头像专用映射：使用相对路径，由 Spring 以后台工作目录为基准解析；缓存期 1 小时。
        // 注意：之前注释里说"文件名带 hash/UUID 几乎不可变"是针对用户上传头像的场景，
        // 但预设头像 slug（如 wang-xing.jpg）是固定字符串，运营可能随时覆盖同名文件，
        // 必须缩短缓存才能让覆盖立即生效。setCachePeriod(3600) 配合 Spring 的 ResourceLastModified
        // 策略：浏览器 max-age 过期后会发 If-Modified-Since 重新验证，文件修改时间变了就返回 200 + 新内容。
        registry.addResourceHandler("/api/upload/avatars/**")
                .addResourceLocations("file:uploads/avatars/")
                .setCachePeriod(3600); // 1 hour, was 1 year (31556926)

        // 通用 uploads 映射：必须解析为绝对路径写入日志/资源定位，
        // 因为 IDE、Maven、java -jar 等不同启动方式下 user.dir 含义不同；
        // 缓存期 1 小时是通用文件上传场景的折中值，兼顾性能与覆盖更新及时性。
        String uploadsPath = Paths.get(System.getProperty("user.dir"), "uploads").toAbsolutePath().toString();
        log.info("[WebConfig] Registering uploads static handler: /uploads/** -> file:{}/", uploadsPath);
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + uploadsPath + "/")
                .setCachePeriod(3600);
    }
}
