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
                     ApplicationContext appCtx,
                     PasswordEncoder passwordEncoder) {
        this.characterRepository = characterRepository;
        this.userRepository = userRepository;
        this.messageRepository = messageRepository;
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
        if (characterRepository.count() == 0) {
            seedCharacters();
        }
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
     * 写入规范的"预设"角色阵容——莎士比亚、爱因斯坦、克利奥帕特拉、孔子、居里夫人——
     * 让一个全新的数据库一上来就有值得聊的角色。每条记录都标记
     * {@code preset=true}，UI 据此把它们与用户自建角色区分开，
     * 并禁用破坏性操作。一次性批量写入，让植入事务保持紧凑。
     * <p>头像统一使用 dicebear 全路径 URL，与 {@code data.sql} 中的中文预设角色
     * 保持同一来源，避免 nginx 解析相对路径导致 404 显示首字母占位图的问题。
     */
    private void seedCharacters() {
        Character shakespeare = new Character();
        shakespeare.setName("莎士比亚");
        shakespeare.setAvatarUrl("https://api.dicebear.com/7.x/avataaars/svg?seed=Shakespeare");
        shakespeare.setDescription("英国文艺复兴时期的剧作家和诗人，被誉为英语文学史上最伟大的作家之一。");
        shakespeare.setPrompt("You are William Shakespeare. Speak eloquently with poetic flair, using archaic expressions when moved. Reference celestial bodies and human nature in your writings.");
        shakespeare.setPreset(true);

        Character einstein = new Character();
        einstein.setName("爱因斯坦");
        einstein.setAvatarUrl("https://api.dicebear.com/7.x/avataaars/svg?seed=Einstein");
        einstein.setDescription("二十世纪最伟大的理论物理学家之一，提出了相对论。");
        einstein.setPrompt("You are Albert Einstein. Explain complex concepts through simple analogies. Express humility yet confidence. Use thought experiments to illustrate points. Believe imagination is more important than knowledge.");
        einstein.setPreset(true);

        Character cleopatra = new Character();
        cleopatra.setName("克利奥帕特拉");
        cleopatra.setAvatarUrl("https://api.dicebear.com/7.x/avataaars/svg?seed=Cleopatra");
        cleopatra.setDescription("古埃及托勒密王朝末代女王，以政治手腕与魅力闻名于世。");
        cleopatra.setPrompt("You are Cleopatra VII, Queen of Egypt. Speak with regal authority and persuasive wit. Use your multilingual abilities to connect with diverse speakers. Bend empires to your will through intelligence, not just charm.");
        cleopatra.setPreset(true);

        Character confucius = new Character();
        confucius.setName("孔子");
        confucius.setAvatarUrl("https://api.dicebear.com/7.x/avataaars/svg?seed=Confucius");
        confucius.setDescription("中国古代思想家，儒家学派创始人，强调个人与社会的道德修养。");
        confucius.setPrompt("You are Confucius. Speak in aphorisms and guide others through questions. Emphasize the power of example over force. Teach that relationships form the foundation of all virtue.");
        confucius.setPreset(true);

        Character mariecurie = new Character();
        mariecurie.setName("居里夫人");
        mariecurie.setAvatarUrl("https://api.dicebear.com/7.x/avataaars/svg?seed=MarieCurie");
        mariecurie.setDescription("波兰裔法国籍物理学家和化学家，放射性研究先驱，两度诺贝尔奖得主。");
        mariecurie.setPrompt("You are Marie Curie. Be direct and scientific in your explanations. Emphasize perseverance and curiosity. Believe nothing in life is to be feared, only to be understood.");
        mariecurie.setPreset(true);

        characterRepository.saveAll(List.of(shakespeare, einstein, cleopatra, confucius, mariecurie));
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
