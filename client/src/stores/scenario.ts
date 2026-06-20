import { defineStore } from 'pinia'
import { ref } from 'vue'

// 场景模板：用户进入「创建房间」弹窗时看到的预设剧本。
// 一个 Scenario 描述一次会话的"上下文 + 引导语 + 角色池"，与底层 Character 解耦——
// 同一组角色可以挂在不同 Scenario 下，扮演不同的角色关系。
export interface Scenario {
  // 路由/query 透传的主键：弹窗 deep-link（?scenario=xxx）直接据此定位
  id: string
  // 仅用于 UI 视觉锚点，不参与业务逻辑；前端可随意替换不影响后端契约
  emoji: string
  title: string
  description: string
  // 静态提示词模板；当 dynamicPrompt=true 时该字段被后端忽略，由运行时拼接
  promptTemplate: string
  // 弹窗里预勾选的角色；最终是否使用由用户在创建房间时的选择覆盖
  suggestedCharacterIds: string[]
  // 是否在弹窗里让用户补充输入（如岗位描述 / 产品 idea / 写作题材）
  requiresUserInput?: boolean
  userInputLabel?: string
  userInputPlaceholder?: string
  // 是否走"动态生成"流程：true = 不再使用 promptTemplate，而是后端根据用户输入生成
  dynamicPrompt?: boolean
  // single-房间使用单个 characterId；group-房间使用多 characterIds
  mode: 'single' | 'group'
}

// 初始的 4 个示例场景。先在前端写死；将来可改为后端 API
// 选型原因：场景数量少且变动不频繁，前端硬编码可以避免额外的网络往返和冷启动空白；
// 后续若运营需要热更新，再迁移到 GET /api/scenarios，调用方（store.getById）签名不变。
const SEED_SCENARIOS: Scenario[] = [
  {
    id: 'interview-coach',
    emoji: '🎤',
    title: '面试模拟',
    description: '挑选你心仪的面试官，模拟一场真实的技术/产品面试。',
    promptTemplate: '',
    suggestedCharacterIds: [],
    requiresUserInput: true,
    userInputLabel: '',
    userInputPlaceholder: '',
    // 走动态生成流程：不再使用固定 promptTemplate，由后端根据用户填写的岗位/JD 生成
    dynamicPrompt: true,
    mode: 'single'
  },
  {
    id: 'product-brainstorm',
    emoji: '💡',
    title: '产品头脑风暴',
    description: '和一位资深产品顾问，深度打磨你的产品 idea。',
    promptTemplate: `你是我邀请的产品顾问团。请用你最擅长的产品思维，帮我打磨一个产品 idea。

【讨论流程】
1. 让我先描述 idea（目标用户、核心场景、解决什么问题）
2. 你先做第一轮评估：市场空间、用户痛点、商业模式
3. 提出 3 个你最关心的尖锐问题
4. 等我回答后，再做第二轮评估：MVP 怎么设计、第一个 1000 用户怎么来
5. 最后给一个"做 / 不做 / 调整后再做"的明确建议

【风格要求】
- 像真的合伙人在讨论，不留情面但有建设性
- 多用具体案例佐证你的观点
- 如果 idea 太烂，直说，不要绕弯`,
    suggestedCharacterIds: [],
    requiresUserInput: true,
    userInputLabel: '你想打磨什么样的产品 idea？',
    userInputPlaceholder: '例如：面向 Z 世代的 AI 头像生成器，帮用户在 30 秒内生成风格化头像',
    mode: 'group'
  },
  {
    id: 'english-tutor',
    emoji: '🇬🇧',
    title: '英语陪练',
    description: '和一个 native speaker 角色进行情景对话练习。',
    promptTemplate: `You are my English conversation partner. Please conduct a 20-minute scenario-based speaking practice with me.

【Rules】
1. Speak English only. When I use Chinese, gently correct me with the English equivalent first, then continue.
2. Pick ONE scenario from: ordering coffee, job interview, hotel check-in, asking for directions, small talk with a stranger.
3. Stay in character throughout. If I break the scenario, redirect me back.
4. After every 5 of my responses, briefly evaluate my grammar/vocabulary (in English).
5. End with one piece of specific advice for next time.

【Style】
- Use natural conversational English (not textbook)
- Speak at B1-B2 level so I can follow
- Be patient but push me to use full sentences`,
    suggestedCharacterIds: [],
    requiresUserInput: true,
    userInputLabel: '想练什么场景？',
    userInputPlaceholder: '例如：coffee shop 点单 / hotel check-in / 求职面试 / 与陌生人闲聊',
    mode: 'single'
  },
  {
    id: 'writing-coach',
    emoji: '✍️',
    title: '写作助手',
    description: '把你的草稿交给一个资深编辑，得到结构化反馈。',
    promptTemplate: `你是一位资深写作编辑。请用你的编辑视角，帮我审一篇稿子。

【审稿流程】
1. 让我先把稿子贴给你（任何题材：技术博客、产品文案、论文、随笔都行）
2. 你先通读一遍，用 3 句话告诉我"这篇最打动你的地方"和"最让你看不下去的地方"
3. 按以下 5 个维度给出 1-10 分评分 + 具体例子：
   - 中心思想（立意是否清晰）
   - 逻辑结构（论证是否严密）
   - 语言表达（句子是否精准）
   - 读者视角（是否照顾到目标读者）
   - 行动力（读完后读者能做什么）
4. 给出 3 条"必改"和 3 条"锦上添花"的建议
5. 如果稿子很差，先别打击，告诉我"如果只能改一处，你会改什么"

【风格】
- 友善但直接
- 给修改示例，不只给批评
- 不要帮我重写，只指出方向`,
    suggestedCharacterIds: [],
    requiresUserInput: true,
    userInputLabel: '这次要审什么稿子？',
    userInputPlaceholder: '例如：一篇关于远程办公的技术博客 / 一份 SaaS 产品上线公告',
    mode: 'single'
  }
]

// 场景 Pinia store。
// 当前不持久化、不写后端：仅作为路由 / 弹窗之间的「共享只读数据源」，
// 避免在多个组件里各自 import SEED_SCENARIOS 造成耦合。
// 协作模块：CreateRoomDialog（场景卡片）、RoomDetailView（场景提示词渲染）。
export const useScenarioStore = defineStore('scenario', () => {
  // 用展开运算符拷贝一份：防御 SEED_SCENARIOS 被外部引用修改（例如单测里 mock 状态时）。
  const scenarios = ref<Scenario[]>([...SEED_SCENARIOS])

  // 单纯的 O(n) 查表；场景数量在个位数级别，无需上 Map / 索引。
  // 返回引用（而非深拷贝）：调用方应当只读消费，修改需要走明确的 mutation action。
  function getById(id: string): Scenario | undefined {
    return scenarios.value.find(s => s.id === id)
  }

  return { scenarios, getById }
})
