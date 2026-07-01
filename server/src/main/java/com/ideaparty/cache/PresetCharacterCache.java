package com.ideaparty.cache;

import com.ideaparty.dto.CharacterResponse;
import com.ideaparty.entity.Character;
import com.ideaparty.entity.CharacterCategory;
import com.ideaparty.util.ImageUrlResolver;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 预设角色静态数据缓存：JVM 启动时一次性把 120 个 preset 从
 * {@code classpath:presets.json} 加载到内存，后续 GET /api/characters/recommended
 * 全部从内存返回，0 DB 查询。
 *
 * <p>设计动机：preset 是只读系统数据，运营改动频率极低（每季度可能更新 1 次），
 * 每次用户访问发现页都从 MySQL SELECT 一次纯属浪费。132KB 一次加载到堆里，
 * HTTP 响应体也是 132KB，可由 nginx/浏览器进一步缓存（max-age=300）。
 *
 * <p>修改预设：直接改 {@code presets.json}，重启 server 生效；或后续
 * 接入管理后台（{@code AdminPresetService}）时通过 {@link #reload()}
 * 热刷新。
 *
 * <p>为什么不用 Caffeine/Guava Cache：JSON 文件本身就是"权威数据源"，
 * 没有失效语义需要维护。冷启动 + 单例 map 已经是这个场景的最简实现。
 */
@Component
@RequiredArgsConstructor
public class PresetCharacterCache {

    private static final Logger log = LoggerFactory.getLogger(PresetCharacterCache.class);

    /** preset UUID -> CharacterResponse，O(1) 查找 */
    private final Map<UUID, CharacterResponse> byId = new ConcurrentHashMap<>();

    /** 按 name 排序的全量列表（不可变），getAll() 零拷贝返回 */
    private volatile List<CharacterResponse> sortedByName = List.of();

    private final ObjectMapper mapper = new ObjectMapper();
    // 把 preset 头像(相对 key 或外网)统一转成完整 OSS URL,装载时一次性 resolve。
    private final ImageUrlResolver imageUrlResolver;

    @PostConstruct
    public void init() {
        try {
            reload();
        } catch (Exception e) {
            // 启动期任何 IO/解析错误都应当让进程退出——
            // 空缓存比"运行时偷偷 fallback 到 DB"更安全（容易引起数据不一致）
            throw new IllegalStateException(
                "Failed to load presets.json — preset cache is critical for /api/characters/recommended", e);
        }
    }

    /**
     * 重新从 classpath 读取 presets.json 加载到内存。
     * 主要给未来管理后台"刷新预设"按钮留口子，初始版本只在 {@link #init()} 调用。
     */
    public synchronized void reload() throws Exception {
        List<PresetEntry> raw;
        try (InputStream in = new ClassPathResource("presets.json").getInputStream()) {
            raw = mapper.readValue(in, new TypeReference<List<PresetEntry>>() {});
        }

        Map<UUID, CharacterResponse> newById = new ConcurrentHashMap<>(raw.size());
        List<CharacterResponse> temp = new ArrayList<>(raw.size());
        for (PresetEntry e : raw) {
            UUID id = UUID.fromString(e.id);
            Character c = new Character();
            c.setId(id);
            c.setName(e.name);
            c.setDescription(e.description);
            c.setPrompt(e.prompt);
            c.setAvatarUrl(e.avatarUrl);
            c.setEra(e.era);
            c.setSpeakingStyle(e.speakingStyle);
            c.setPersona(e.persona);
            c.setPreset(true);
            // categories 多分类解析：每条记录一个枚举名，非法值 warn + 跳过该条不影响其它
            if (e.categories != null) {
                Set<CharacterCategory> parsed = new HashSet<>(e.categories.size());
                for (String name : e.categories) {
                    if (name == null || name.isBlank()) continue;
                    try {
                        parsed.add(CharacterCategory.valueOf(name.trim()));
                    } catch (IllegalArgumentException ignored) {
                        // presets.json 里某条 category 值无效时，warn 但不影响其它有效值
                        log.warn("[PresetCache] invalid category '{}' for preset id={}, skipping",
                                 name, id);
                    }
                }
                c.setCategories(parsed);
            }
            CharacterResponse resp = CharacterResponse.fromEntity(c).resolveImageUrls(imageUrlResolver);
            newById.put(id, resp);
            temp.add(resp);
        }
        // 排序 + 冻结成不可变列表
        List<CharacterResponse> sorted = temp.stream()
                .sorted((a, b) -> a.getName().compareTo(b.getName()))
                .collect(Collectors.toUnmodifiableList());

        // 原子替换：保证外部读 getAll() 永远拿到一份"完整的"快照
        this.byId.clear();
        this.byId.putAll(newById);
        this.sortedByName = Collections.unmodifiableList(sorted);
    }

    /** 全量列表（按 name 升序，不可变） */
    public List<CharacterResponse> getAll() {
        return sortedByName;
    }

    /** O(1) UUID 查找，未命中返回 null（让调用方决定 fallback） */
    public CharacterResponse getById(UUID id) {
        return byId.get(id);
    }

    public int size() {
        return byId.size();
    }

    /**
     * presets.json 字段映射。{@code category} 字段从 DB 读出来要单独处理
     * （因为 JPA enum 与 JSON 字符串不直接转换），所以这里不持久化。
     */
    public static class PresetEntry {
        public String id;
        public String name;
        public String description;
        public String prompt;
        public String avatarUrl;
        public String era;
        public String speakingStyle;
        public String persona;
        /** 多分类集合：支持一个角色同时属于多个分类（如毛泽东 = 历史人物 + 政治家 + 军事家）。 */
        public List<String> categories;
    }
}
