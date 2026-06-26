package com.ideaparty.entity;

/**
 * 预设角色的分类标签，用于"发现页"按分类筛选推荐角色。
 *
 * 设计动机：发现页的"分类标签条"在客户端只是一组 chip，server 必须能按 category 过滤。
 * 用枚举收敛可选值，避免前端传来奇怪字符串污染数据。
 *
 * label 字段：面向前端展示的中文文案 + emoji，渲染"分类标签条"时直接消费。
 *
 * 与谁配合：
 *   - Character.category 字段（数据库 VARCHAR(32) 存枚举 name）
 *   - CharacterController /recommended 接口 ?category= 参数解析
 *   - 前端 RoomListView.vue 的 categories 数组（id 与枚举 name 一一对应）
 */
public enum CharacterCategory {
    /** 科学家：物理/数学/化学/生物等自然科学奠基人 */
    SCIENTIST("科学家", "🔬"),
    /** 明星：演艺/音乐/体育娱乐领域的公众人物 */
    STAR("明星", "🌟"),
    /** 企业家：科技/商业领域的创始人/CEO/投资人 */
    ENTREPRENEUR("企业家", "🚀"),
    /** 哲学家：东西方思想家，关注伦理/存在/认知 */
    PHILOSOPHER("哲学家", "💭"),
    /** 运动员：足球/篮球/拳击等体育领域的传奇人物 */
    ATHLETE("运动员", "🏆"),
    /** 作家：文学家/小说家/诗人 */
    WRITER("作家", "📖"),
    /** 动漫：动画/漫画领域虚拟或相关创作者 */
    ANIME("动漫", "🎨"),
    /** 历史人物：政治/军事/宗教领域的历史人物 */
    HISTORICAL("历史人物", "🏛️"),
    /** 艺术家：画家/雕塑家/音乐家/作曲家 */
    ARTIST("艺术家", "🖼️");

    private final String label;
    private final String emoji;

    CharacterCategory(String label, String emoji) {
        this.label = label;
        this.emoji = emoji;
    }

    public String getLabel() { return label; }
    public String getEmoji() { return emoji; }

    /**
     * 按枚举 name 解析；找不到时返回 null（不抛异常，让调用方决定 fallback）。
     * 用于 Controller 把 ?category=STRING 转成枚举。
     */
    public static CharacterCategory fromName(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            return CharacterCategory.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
