package com.ideaparty.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

/**
 * Web MVC 配置类：注册本地静态资源处理器，把用户上传的头像与其他文件以 HTTP 形式暴露给前端。
 * 为什么独立于 application.yml 的 spring.web.resources.static-locations：/uploads/** 需要基于进程
 * 工作目录动态解析绝对路径，避免开发/部署环境目录差异导致资源 404。
 *
 * 头像/封面图访问统一走 /uploads/avatars/ 路径,/api/ 命名空间只服务 REST 端点。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * 类级 SLF4J 日志器：用于在启动阶段打印静态资源映射所对应的实际物理路径，
     * 方便运维/调试时确认 uploads 目录指向正确（避免相对路径在不同启动方式下解析不一致）。
     */
    private static final Logger log = LoggerFactory.getLogger(WebConfig.class);

    /**
     * 注册 {@code /uploads/**} 静态资源映射：基于 {@code user.dir} 解析的绝对路径，缓存 1 小时。
     * （通用文件可能频繁覆盖，保守缓存避免脏读。）
     *
     * @param registry Spring MVC 提供的资源处理器注册表，启动时由框架注入；本方法不直接返回值，
     *                 所有配置通过调用 {@code registry.addResourceHandler(...)} 完成。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
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
