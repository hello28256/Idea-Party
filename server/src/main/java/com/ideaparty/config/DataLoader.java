package com.ideaparty.config;

import com.ideaparty.entity.Character;
import com.ideaparty.entity.Message;
import com.ideaparty.entity.User;
import com.ideaparty.repository.CharacterRepository;
import com.ideaparty.repository.MessageRepository;
import com.ideaparty.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Spring Boot 启动钩子：在服务器开始对外提供服务之前准备好数据库。
 * 在全新安装时植入一组默认的预设 AI 角色，把配置的管理员白名单同步到
 * 用户表，并为历史消息回填观测行，以便旧消息能在管理后台反馈总览中展示。
 * 通过 {@link CommandLineRunner} 在每次 JVM 启动时执行一次，
 * 整体包裹在一个事务里，使部分失败能够干净回滚，避免留下半成品的数据库。
 */
@Component
public class DataLoader implements CommandLineRunner {

    /**
     * 标准 SLF4J 日志器；日志行统一加上
     * [Backfill] / [AdminSync] 等前缀，便于运维人员
     * 在不知道来源类的情况下也能直接 grep 启动日志。
     */
    private static final Logger log = LoggerFactory.getLogger(DataLoader.class);

    /** JPA 仓库：用于插入预设角色，以及通过 count 判断是否已经植入过。 */
    private final CharacterRepository characterRepository;
    /** JPA 仓库：用于把白名单用户的 is_admin 字段置为 true。 */
    private final UserRepository userRepository;
    /** 回填阶段扫描该仓库，枚举所有已持久化的消息并判断哪些需要补一条 observation 行。 */
    private final MessageRepository messageRepository;
    /** 启动期给 prompt=null 的 preset 用 LLM 生成角色卡（走 character-prompt-generator.txt 模板）。 */
    private final com.ideaparty.service.CharacterService characterService;
    /**
     * 拥有"每条 AI 消息对应一条 observation"不变量的领域服务。
     * 通过注入方式引入，让本类保持精简，回填逻辑与线上代码路径放在一起，
     * 避免重复实现。
     */
    private final com.ideaparty.service.MessageObservationService observationService;
    /**
     * 仅用于在 {@link #recomputeForExisting(Message)} 中惰性查找
     * {@link com.ideaparty.repository.MessageFeedbackRepository} 而持有的
     * Spring {@link ApplicationContext}，避免本类与 feedback repository
     * 自身依赖之间出现循环 Bean 风险。
     */
    private final ApplicationContext appCtx;
    /**
     * BCrypt 密码编码器：在种子管理员写入密码哈希时复用 SecurityConfig 注册的单例，
     * 避免与登录/改密路径走两套哈希算法产生校验不一致。
     */
    private final PasswordEncoder passwordEncoder;

    /**
     * 从 {@code application.yml: app.admin.user-ids} 读取的逗号分隔用户 UUID 列表。
     * 默认值留空以保证开发环境安全；生产环境应配置为创始人/运维人员的 ID。
     * 每次启动都会同步到 {@code is_admin} 列，因此当白名单被缩减时，
     * 权限会自动回收。
     */
    @Value("${app.admin.user-ids:}")
    private String adminUserIdsConfig;

    /**
     * 默认管理员用户名，从环境变量 {@code APP_ADMIN_USERNAME} 读取；缺省值 {@code admin123}
     * 与产品文档"开箱即用管理员账号"约定保持一致。设为空字符串表示禁用种子逻辑。
     */
    @Value("${app.admin.default-username:admin123}")
    private String defaultAdminUsername;

    /**
     * 默认管理员明文密码，从环境变量 {@code APP_ADMIN_PASSWORD} 读取；缺省值 {@code admin123}。
     * 仅在 {@link #seedDefaultAdminIfNeeded()} 创建新用户时使用一次，写入前经 BCrypt 哈希，
     * 不会以明文形式落库或回显到日志。
     */
    @Value("${app.admin.default-password:admin123}")
    private String defaultAdminPassword;

    /**
     * 启动期生成 preset prompt 时使用的 LLM API key，从环境变量 {@code LANGCHAIN4J_OPEN_AI_API_KEY}
     * 读取（即 docker-compose 注入的 DEEPSEEK_API_KEY）。null/空/dummy 时 fallback 为通用模板。
     * 之所以走 LangChain4j 的 key 而不是 application.yml 里独立的 deepseek.api-key 配置：
     *   - LangChain4j 的 key 已经在 application.yml 里配置好且 docker-compose 已注入，复用避免双源；
     *   - 单独再配一个 key 会导致运维必须同步更新两处配置，违反 DRY。
     */
    @Value("${langchain4j.open-ai.api-key:}")
    private String deepseekApiKeyForSeed;

    /**
     * 通过构造器注入依赖；Spring 按类型解析每一个。
     * 标记为 {@code final}，使本 Bean 不可变且可在多线程间安全共享
     * （CommandLineRunner 实例是单例）。
     *
     * @param characterRepository {@code characters} 表的 JPA 仓库。
     * @param userRepository      {@code users} 表的 JPA 仓库。
     * @param messageRepository   {@code messages} 表的 JPA 仓库。
     * @param observationService  维护 message_observations 表的服务。
     * @param appCtx              Spring 上下文，仅在回填阶段用于惰性查找 Bean。
     * @param passwordEncoder     BCrypt 哈希器，种子管理员密码写入时复用。
     */
    public DataLoader(CharacterRepository characterRepository, UserRepository userRepository,
                     MessageRepository messageRepository,
                     com.ideaparty.service.MessageObservationService observationService,
                     com.ideaparty.service.CharacterService characterService,
                     ApplicationContext appCtx,
                     PasswordEncoder passwordEncoder) {
        this.characterRepository = characterRepository;
        this.userRepository = userRepository;
        this.messageRepository = messageRepository;
        this.characterService = characterService;
        this.observationService = observationService;
        this.appCtx = appCtx;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Spring Boot 回调，在上下文完全初始化完成后、内嵌服务器开始接受请求前执行。
     * 具有幂等性：植入操作以 {@code characterRepository.count() == 0} 为前置条件，
     * 管理员同步只把当前为 false 的用户翻转为 true，回填观测行在每条消息
     * 都已有对应行时则为空操作。
     *
     * @param args 传给 JVM 的原始命令行参数；此处未使用。
     */
    @Override
    @Transactional
    public void run(String... args) {
        // V10 之后：预设角色不再持久化到 DB（classpath:presets.json + PresetCharacterCache
        // 启动时加载到 JVM 内存），所以 seedCharactersIfMissing 整段调用已废弃。
        // 保留方法签名/类以便老 jar 升级兼容；新版本直接不调。
        seedDefaultAdminIfNeeded();
        syncAdminWhitelist();
        backfillObservations();
    }

    /**
     * 一次性回填：确保每条已有的 CHARACTER 消息都有一条 observation 行。
     * 具有幂等性（onAiMessagePersisted 会在已存在时跳过）。
     * 同时根据已有的 message_feedbacks 重新计算计数，让历史反馈行
     * 能够在管理后台总览中显示出来。
     *
     * <p>副作用：为每条 AI 消息写入一条 observation 行（或更新已有行）；
     * 单条消息失败会以 WARN 级别记录并吞掉，避免因为某一行损坏而中断整个启动。
     */
    private void backfillObservations() {
        int created = 0;
        for (Message m : messageRepository.findAll()) {
            if (m.getSenderType() != Message.SenderType.CHARACTER) continue;
            try {
                observationService.onAiMessagePersisted(m);
                recomputeForExisting(m);
                created++;
            } catch (Exception e) {
                log.warn("[Backfill] observation failed for {}: {}", m.getId(), e.getMessage());
            }
        }
        if (created > 0) log.info("[Backfill] touched {} AI messages for observation", created);
    }

    /**
     * 从权威表 {@code message_feedbacks} 中重新推导出单条 AI 消息的
     * 点赞/点踩计数，并写回对应的 observation 行。当该消息从未收到任何
     * 反馈时直接跳过——新创建的 observation 行本身已经全部为 0。
     *
     * @param m 需要刷新 observation 的 AI 消息。
     */
    private void recomputeForExisting(Message m) {
        var feedbackRepo = appCtx.getBean(com.ideaparty.repository.MessageFeedbackRepository.class);
        long likes = feedbackRepo.countByMessageIdAndType(m.getId(), com.ideaparty.entity.FeedbackType.LIKE);
        long dislikes = feedbackRepo.countByMessageIdAndType(m.getId(), com.ideaparty.entity.FeedbackType.DISLIKE);
        if (likes == 0 && dislikes == 0) return;
        var last = feedbackRepo.findTopByMessageIdOrderByUpdatedAtDesc(m.getId()).orElse(null);
        java.time.Instant lastAt = last != null ? last.getUpdatedAt() : null;
        observationService.recompute(m.getId(), likes, dislikes, lastAt);
    }

    /**
     * 把 app.admin.user-ids 中列出的用户提升为 is_admin=true，使前端
     * （前端只检查来自数据库的 isAdmin 字段）能够显示管理员菜单。
     * AdminFeedbackController 仍然把这份白名单作为运行时兜底，但同步到列里
     * 之后，应用的其它部分（UI、未来的中间件）也能直接生效。
     *
     * <p>副作用：修改匹配行的 {@code users.is_admin}，并为每次提升输出一条 INFO 日志。
     * 未知的 UUID 会以 WARN 级别记录并跳过，不会让启动失败。
     */
    private void syncAdminWhitelist() {
        if (adminUserIdsConfig == null || adminUserIdsConfig.isBlank()) {
            return;
        }
        List<UUID> ids = Arrays.stream(adminUserIdsConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(UUID::fromString)
                .toList();
        int promoted = 0;
        for (UUID id : ids) {
            User u = userRepository.findById(id).orElse(null);
            if (u == null) {
                log.warn("[AdminSync] user id {} not found, skipping", id);
                continue;
            }
            if (!Boolean.TRUE.equals(u.getIsAdmin())) {
                u.setIsAdmin(true);
                userRepository.save(u);
                promoted++;
                log.info("[AdminSync] promoted user {} ({}) to admin", u.getUsername(), id);
            }
        }
        if (promoted > 0) {
            log.info("[AdminSync] promoted {} user(s) to admin", promoted);
        }
    }

    /**
     * 按名字幂等写入规范的"预设"角色阵容——36 位跨领域最具影响力的历史人物——
     * 让一个全新的数据库一上来就有值得聊的角色。每条记录都标记
     * {@code preset=true}，UI 据此把它们与用户自建角色区分开，
     * 并禁用破坏性操作。一次性批量写入，让植入事务保持紧凑。
     * <p>顺序与 {@code data.sql} 完全一致（思想宗教 6 + 科学 6 + 文化政治 6 + 哲学 6 + 物理 6 + 艺术 6），
     * 配合 findRecommended() 按预设顺序返回，"推荐角色"区呈现 6 排 × 6 网格。
     * 头像统一使用英文维基百科 220px 缩略图，与 data.sql 同源，辨识度远高于抽象 DiceBear 头像。
     * <p>按名字幂等：已存在同名的预设角色跳过，便于老库平滑升级到新的 36 人
     * 阵容；同时不依赖 characters.count() == 0 的空库判定，老库里也能补齐缺失的预设。
     */
    /**
     * V10 之后已废弃。
     *
     * <p>原行为：按名字幂等写入 120 个 preset 角色到 characters 表，prompt 留空
     * 由 backfillPresetPrompts 在启动时调 DeepSeek 生成。
     *
     * <p>新行为：preset 数据从 classpath:presets.json 启动时加载到
     * {@link com.ideaparty.cache.PresetCharacterCache}，不再写 DB。V10 migration
     * 已经把现存 120 条 preset 从 DB 物理删除。
     *
     * <p>保留这个方法（+ backfillPresetAvatarUrls + backfillPresetPrompts + buildPreset）
     * 是为了不影响 DataLoader 类自身的依赖注入与事务边界；后续清理版本可以一起删。
     */
    @SuppressWarnings("unused")
    private void seedCharactersIfMissing() {
        // no-op: 见上方 Javadoc
    }


    /**
     * 老库回填：把现存 preset 角色里仍指向外网（http/https）的 avatarUrl 改为本地路径
     * ({@code /api/upload/avatars/presets/<english-name>.<ext>})。
     *
     * <p>用途：buildPreset 把头像 URL 写死成维基百科地址，老库升级时 seedCharactersIfMissing
     * 按名字去重已跳过这些角色，导致 avatarUrl 不会跟着源码自动更新。本方法在每次启动跑一次，
     * 把所有"已存在 preset 但 avatarUrl 仍指向外网"的记录就地改成本地路径，幂等可重复执行。
     */
    private void backfillPresetAvatarUrls(List<Character> freshPresets) {
        java.util.Map<String, String> nameToLocal = freshPresets.stream()
                .filter(c -> c.getAvatarUrl() != null && c.getAvatarUrl().startsWith("/api/"))
                .collect(java.util.stream.Collectors.toMap(
                        Character::getName, Character::getAvatarUrl, (a, b) -> a));
        if (nameToLocal.isEmpty()) {
            return;
        }
        List<Character> existing = characterRepository.findByIsPresetTrue();
        int updated = 0;
        for (Character c : existing) {
            String local = nameToLocal.get(c.getName());
            String current = c.getAvatarUrl();
            if (local != null && current != null
                    && (current.startsWith("http://") || current.startsWith("https://"))) {
                log.info("[PresetSeed] backfill avatar for '{}': {} -> {}", c.getName(), current, local);
                c.setAvatarUrl(local);
                updated++;
            }
        }
        if (updated > 0) {
            characterRepository.saveAll(existing);
            log.info("[PresetSeed] backfilled {} preset avatar URLs to local paths", updated);
        }
    }

    /**
     * 老库回填：把现存 preset 角色里 prompt 缺失或仅含 fallback 模板的记录用
     * CharacterService.generatePromptByName 重新生成。
     *
     * <p>触发条件：
     *   - prompt 为 null/空白 → 直接生成
     *   - prompt 长度 < 100 字符且含 fallback 关键词"以深度和真实性表达"→ 重生成
     *     （典型场景：之前用 dummy key 启动，LLM 调用 401 失败，写入了 fallback 模板；
     *     后来注入真实 key 后这些角色需要被替换）
     *
     * <p>关键：每次循环迭代用 {@link #regenerateAndSavePresetPrompt(UUID, String)}
     * 独立事务（REQUIRES_NEW），避免一个 LLM 慢调用把整个 18 个 preset 的 saveAll
     * 拖入 5+ 小时超长事务，最终因超时/网络异常被 JPA 整体回滚——之前 5+ 小时
     * 生成的 prompt 内容全部丢失就是这个原因。
     */
    private void backfillPresetPrompts(List<Character> freshPresets) {
        java.util.Map<String, String> nameToDesc = freshPresets.stream()
                .filter(c -> c.getDescription() != null && !c.getDescription().isBlank())
                .collect(java.util.stream.Collectors.toMap(
                        Character::getName, Character::getDescription, (a, b) -> a));
        List<Character> existing = characterRepository.findByIsPresetTrue();
        int generated = 0;
        for (Character c : existing) {
            String prompt = c.getPrompt();
            boolean needsRegen = prompt == null || prompt.isBlank()
                    || (prompt.length() < 100 && prompt.contains("以深度和真实性表达"));
            if (!needsRegen) {
                continue;
            }
            String desc = nameToDesc.get(c.getName());
            if (desc == null) {
                desc = c.getDescription();
            }
            log.info("[PresetSeed] regenerating prompt for existing preset '{}' (current len={})",
                    c.getName(), prompt == null ? 0 : prompt.length());
            // 每个 preset 独立事务：成功立即落库，失败不影响其他 preset
            String savedPrompt = regenerateAndSavePresetPrompt(c.getId(), desc);
            if (savedPrompt != null) {
                generated++;
            }
        }
        log.info("[PresetSeed] backfilled {} preset prompts", generated);
    }

    /**
     * 单个 preset 的 prompt 回填：调用 LLM 生成 + 立即写库。
     * 用 REQUIRES_NEW 事务隔离，避免 backfillPresetPrompts 的循环因单个
     * preset 生成失败被一并回滚。返回值是写入的 prompt（便于上层做日志/统计）。
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public String regenerateAndSavePresetPrompt(java.util.UUID characterId, String description) {
        java.util.Optional<Character> opt = characterRepository.findById(characterId);
        if (opt.isEmpty()) {
            return null;
        }
        Character c = opt.get();
        String generated = characterService.generatePromptByName(c.getName(), description, deepseekApiKeyForSeed);
        c.setPrompt(generated);
        characterRepository.save(c);
        return generated;
    }

    // 构造预设角色：消除 seedCharacters 内大量重复字段样板代码，集中参数。
    // 不显式 setId：交给 JPA 的 @GeneratedValue(strategy = UUID) 生成，
    // 避免 Hibernate "unsaved-value" 误判导致 saveAll 走 UPDATE 分支。
    // 推荐区顺序由 findByIsPresetTrueOrderByNameAsc 决定（按 name Unicode 序），
    // 所以 List 内的写入顺序仅作为人类阅读时的"分组参考"，不依赖于此方法。
    // avatarUrl 用历史人物的真实肖像（英文维基百科 220px 缩略图，已下载到
    // server/uploads/avatars/presets/，通过 /api/upload/avatars/presets/** 静态映射对外提供），
    // 离线可用、辨识度高于 DiceBear 抽象头像。
    //
    // prompt 字段：传 null 时启动期调用 CharacterService.generatePromptByName 用 LLM 生成
    // （走 character-prompt-generator.txt 模板，150~250 字中文角色卡），保证新 preset 第一次
    // 启动后就有完整 prompt，不需要用户首次点选时再联网生成（懒生成）——避免冷启动对话空白。
    private Character buildPreset(int index, String name, String description, String avatarUrl, String prompt) {
        return buildPreset(index, name, description, avatarUrl, prompt, null);
    }

    /**
     * 接受 category 参数的重载，给发现页"分类标签条"用。
     * 36 个老角色在 seedCharactersIfMissing 里通过 PRESET_CATEGORY_MAP 查表传入；
     * data.sql 已直接为 120 个角色写好 category，所以 DataLoader 仅对 1-36 老路径生效。
     */
    private Character buildPreset(int index, String name, String description, String avatarUrl, String prompt,
                                  com.ideaparty.entity.CharacterCategory category) {
        Character c = new Character();
        c.setName(name);
        c.setDescription(description);
        c.setAvatarUrl(avatarUrl);
        c.setPreset(true);
        c.addCategory(category);
        if (prompt == null) {
            // 启动期生成：依赖 CharacterService 已注入（@Service 是单例，DataLoader @Component
            // 晚于 Service 装配，调用安全）。失败时 CharacterService 内部已 fallback 到通用 prompt。
            prompt = characterService.generatePromptByName(name, description, deepseekApiKeyForSeed);
        }
        c.setPrompt(prompt);
        return c;
    }

    /**
     * 36 个老角色名 → category 映射表（与 data.sql 里的写入保持一致）。
     * 用 Map 而不是 switch/if 链：HashMap O(1)，可读性比 36 个 if 好。
     * 未在表里的名字 → null（保持"未分类"状态）。
     */
    private static final java.util.Map<String, com.ideaparty.entity.CharacterCategory> PRESET_CATEGORY_MAP =
            buildPresetCategoryMap();

    private static java.util.Map<String, com.ideaparty.entity.CharacterCategory> buildPresetCategoryMap() {
        java.util.Map<String, com.ideaparty.entity.CharacterCategory> m = new java.util.HashMap<>();
        com.ideaparty.entity.CharacterCategory S = com.ideaparty.entity.CharacterCategory.SCIENTIST;
        com.ideaparty.entity.CharacterCategory T = com.ideaparty.entity.CharacterCategory.STAR;
        com.ideaparty.entity.CharacterCategory E = com.ideaparty.entity.CharacterCategory.ENTREPRENEUR;
        com.ideaparty.entity.CharacterCategory P = com.ideaparty.entity.CharacterCategory.PHILOSOPHER;
        com.ideaparty.entity.CharacterCategory A = com.ideaparty.entity.CharacterCategory.ATHLETE;
        com.ideaparty.entity.CharacterCategory W = com.ideaparty.entity.CharacterCategory.WRITER;
        com.ideaparty.entity.CharacterCategory H = com.ideaparty.entity.CharacterCategory.HISTORICAL;
        com.ideaparty.entity.CharacterCategory R = com.ideaparty.entity.CharacterCategory.ARTIST;
        for (String n : java.util.Arrays.asList("伽利略", "牛顿", "达尔文", "爱因斯坦", "居里夫人", "特斯拉",
                "伽罗瓦", "高斯", "麦克斯韦", "玻尔", "海森堡")) m.put(n, S);
        for (String n : java.util.Arrays.asList("卓别林", "宫崎骏")) m.put(n, T);
        for (String n : java.util.Arrays.asList("孔子", "苏格拉底", "老子", "释迦牟尼", "耶稣", "穆罕默德",
                "亚里士多德", "马克思", "卢梭", "伏尔泰", "康德", "黑格尔", "尼采", "弗洛伊德")) m.put(n, P);
        m.put("莎士比亚", W);
        for (String n : java.util.Arrays.asList("巴赫", "莫扎特", "贝多芬")) m.put(n, R);
        m.put("柏拉图", P);
        m.put("达·芬奇", R);
        for (String n : java.util.Arrays.asList("成吉思汗", "拿破仑", "毛泽东", "列宁")) m.put(n, H);
        return java.util.Collections.unmodifiableMap(m);
    }

    /**
     * 在全新部署上写入一个"开箱即用"的管理员账号，使运维人员无需先注册再手动升
     * admin 即可登录管理后台。用户名与密码来自环境变量
     * {@code APP_ADMIN_USERNAME} / {@code APP_ADMIN_PASSWORD}，缺省值分别为
     * {@code admin123} / {@code admin123}。
     *
     * <p>语义：按用户名 {@link UserRepository#findByUsername(String)} 查询；
     *   - 不存在 → 创建新账号，密码经 BCrypt 哈希后写入，{@code is_admin=true}；
     *   - 存在但 {@code is_admin=false} → 提升为 admin，密码按 env 同步刷新（保证
     *     "不管在哪部署都有这个管理员账号 + 默认密码能登入"的产品约定始终成立）；
     *   - 存在且已是 admin → 跳过（管理员拥有改密权限，留给业务侧处理）。
     * 设为空字符串则禁用种子逻辑。
     *
     * <p>与 {@link #syncAdminWhitelist()} 互为补充：后者负责把白名单 UUID 提升为 admin，
     * 本方法负责"按用户名幂等"地从零创建或修复默认管理员。
     */
    private void seedDefaultAdminIfNeeded() {
        if (defaultAdminUsername == null || defaultAdminUsername.isBlank()) {
            log.info("[AdminSeed] APP_ADMIN_USERNAME is empty, skip default admin seeding");
            return;
        }
        if (defaultAdminPassword == null || defaultAdminPassword.isBlank()) {
            log.warn("[AdminSeed] APP_ADMIN_PASSWORD is empty, skip default admin seeding");
            return;
        }
        userRepository.findByUsername(defaultAdminUsername).ifPresentOrElse(
            existing -> {
                if (Boolean.TRUE.equals(existing.getIsAdmin())) {
                    log.info("[AdminSeed] admin '{}' (id={}) already in place, skip",
                            existing.getUsername(), existing.getId());
                    return;
                }
                existing.setIsAdmin(true);
                existing.setPassword(passwordEncoder.encode(defaultAdminPassword));
                userRepository.save(existing);
                log.warn("[AdminSeed] promoted existing user '{}' (id={}) to admin and reset password",
                        existing.getUsername(), existing.getId());
            },
            () -> {
                User admin = new User();
                admin.setUsername(defaultAdminUsername);
                admin.setDisplayName(defaultAdminUsername);
                admin.setPassword(passwordEncoder.encode(defaultAdminPassword));
                admin.setIsAdmin(true);
                User saved = userRepository.save(admin);
                log.info("[AdminSeed] created default admin '{}' (id={})",
                        saved.getUsername(), saved.getId());
            }
        );
    }
}
