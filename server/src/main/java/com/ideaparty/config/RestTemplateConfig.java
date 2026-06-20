package com.ideaparty.config;

import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.TimeValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.TimeUnit;

/**
 * RestTemplate 客户端配置类。
 * <p>
 * 之所以需要自定义：Spring Boot 默认的 {@code RestTemplate} 底层使用 JDK 的 {@code HttpURLConnection}，
 * 不支持连接池，且在高并发调用外部 AI/搜索服务时容易出现连接耗尽或超时不可控。
 * 本配置基于 Apache HttpClient 5 提供连接池与可调超时，供其他需要调用外部 HTTP 接口的服务复用。
 * <p>
 * 配合 {@link com.ideaparty.service.AiService}、Firecrawl 抓取客户端等出站调用方使用。
 */
@Configuration
public class RestTemplateConfig {

    /**
     * 提供一个全局可注入的 {@link RestTemplate} Bean。
     * <p>
     * 契约：调用方应通过构造器或字段注入此 Bean，避免自行 {@code new RestTemplate()}，
     * 以确保整个应用共享同一套连接池与超时配置，便于统一调优。
     * <p>
     * 副作用：将一个持有 {@code CloseableHttpClient} 连接池的单例注册到 Spring 容器，
     * 容器关闭时由 Spring 负责释放底层资源。
     *
     * @return 配置好连接池与超时的 {@link RestTemplate} 实例
     */
    @Bean
    public RestTemplate restTemplate() {
        // 使用 Apache HttpClient 5 的连接池管理器，替代默认的无池化 HttpURLConnection，
        // 避免每次请求都新建 TCP 连接，提升对 DeepSeek / Firecrawl 等外部服务的并发能力。
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        // 整个连接池上限 50：按当前外部调用并发量评估，预留余量；超过则需要排队或新建连接。
        connectionManager.setMaxTotal(50);
        // 单一路由（同一 host:port）上限 25：防止对单一外部服务占用过多连接，引发对方限流。
        connectionManager.setDefaultMaxPerRoute(25);
        // 空闲 30 秒后再使用前验证一次：避免使用已被服务端关闭的半死连接（stale connection）。
        connectionManager.setValidateAfterInactivity(TimeValue.ofSeconds(30));

        // 显式使用全限定类名，避开与 Spring 自身 Closeable 类的命名混淆；shared=false 表示
        // 连接池由本 Bean 独占，Spring 容器销毁时连同 HttpClient 一起关闭，防止连接泄漏。
        org.apache.hc.client5.http.impl.classic.CloseableHttpClient httpClient = HttpClients.custom()
            .setConnectionManager(connectionManager)
            .setConnectionManagerShared(false)
            .build();

        // 把 Apache HttpClient 适配为 Spring 的 ClientHttpRequestFactory，让 RestTemplate 复用上面的连接池。
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        // 连接建立超时 10s：外部 AI/抓取服务通常响应快，但跨网络/跨区域时偶尔慢于 1-2s，10s 留足容错。
        factory.setConnectTimeout(10000);
        // 读取响应超时 30s：DeepSeek 生成与 Firecrawl 抓取可能耗时较长，30s 兼顾长尾与失败快速暴露。
        factory.setReadTimeout(30000);

        // 返回的 RestTemplate 单例由 Spring 管理生命周期；其他 Service 注入即可复用此配置。
        return new RestTemplate(factory);
    }
}
