package com.ideaparty.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ideaparty.dto.HotRoomResponse;
import com.ideaparty.dto.HotRoomResponse.LatestMessage;
import com.ideaparty.util.ImageUrlResolver;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import lombok.RequiredArgsConstructor;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 热门聊天室静态数据接口。
 *
 * <p>与 PresetCharacterCache 同样采取"classpath JSON + 启动期载入内存"模式：
 * hotRooms.json 是运营/产品更新的低频配置（季度级别），不应每次请求都重新解析文件；
 * 启动期一次性读到内存，所有 HTTP 请求共用同一份不可变快照，0 文件 IO。
 *
 * <p>路由 {@code GET /api/hot-rooms}。**故意独立前缀**，避免与 RoomController 的
 * {@code @RequestMapping("/api/rooms") + @GetMapping("/{id}")} 在 Spring 路径匹配时冲突：
 * 如果挂在 {@code /api/rooms/hot}，路径字面量优先匹配 {@code /api/rooms/{id}}，
 * "hot" 字符串会被后者按 UUID 解析失败抛 500。独立前缀避开此陷阱并让代码意图自洽。
 */
@RestController
@RequestMapping("/api/hot-rooms")
@RequiredArgsConstructor
public class HotRoomController {

    private static final Logger log = LoggerFactory.getLogger(HotRoomController.class);

    private final ObjectMapper mapper = new ObjectMapper();
    // 把 hotRooms.json 里的 cover 相对路径("uploads/avatars/hot-rooms/xxx.jpg")
    // 拼成完整 OSS URL,前端 <img src> 直连 OSS 不走 nginx。
    private final ImageUrlResolver imageUrlResolver;

    /** 全量热门房间（不可变快照）；指针替换保证 list() 不会看到半成品数据。 */
    private volatile List<HotRoomResponse> hotRooms = List.of();

    @PostConstruct
    public void loadFromClasspath() {
        try (InputStream in = new ClassPathResource("hotRooms.json").getInputStream()) {
            List<RawEntry> raw = mapper.readValue(in, new TypeReference<List<RawEntry>>() {});
            List<HotRoomResponse> parsed = new ArrayList<>(raw.size());
            for (RawEntry e : raw) {
                RawMessage msg = e.latestMessage;
                LatestMessage latest = msg == null
                        ? new LatestMessage("", "")
                        : new LatestMessage(
                                msg.sender == null ? "" : msg.sender,
                                msg.text == null ? "" : msg.text);
                parsed.add(new HotRoomResponse(
                        e.id,
                        e.title,
                        imageUrlResolver.resolve(e.cover),
                        e.participants == null ? List.of() : List.copyOf(e.participants),
                        latest,
                        e.onlineCount,
                        e.messageCount,
                        e.isHot));
            }
            // 不可变 + 原子替换：list() 永远拿到完整快照，避免 partial-write 期间读到半成品数组。
            this.hotRooms = Collections.unmodifiableList(parsed);
            log.info("[HotRoom] loaded {} hot rooms from classpath", parsed.size());
        } catch (Exception e) {
            // 启动期失败应当让进程退出：与 PresetCharacterCache 同口径——空热门口比"运行时静默 fallback"安全。
            throw new IllegalStateException(
                    "Failed to load hotRooms.json — /api/rooms/hot will be unavailable", e);
        }
    }

    /**
     * 方法级 mapping 留空字符串，让 Spring 用类级 @RequestMapping 拼成 "/api/hot-rooms"。
     */
    @GetMapping("")
    public ResponseEntity<List<HotRoomResponse>> list() {
        return ResponseEntity.ok(hotRooms);
    }

    /** hotRooms.json 的内层原始形态：latestMessage 是嵌套对象，用独立 inner class 承接。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class RawEntry {
        public String id;
        public String title;
        public String cover;
        public List<String> participants;
        public RawMessage latestMessage;
        public int onlineCount;
        public int messageCount;
        public boolean isHot;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class RawMessage {
        public String sender;
        public String text;
    }
}
