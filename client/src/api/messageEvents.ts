import { api } from './auth'

// 消息交互信号埋点：记录用户对 AI 回复的隐式行为（重写/复制/读完整/编辑/聚焦），
// 用于后端聚合出"消息热度"指标，反哺 Moderator Agent 的发言编排与质量评估。
// 上报与聚合查询拆成两条独立接口，避免一个失败拖垮另一条调用链。
export type EventType = 'REWRITE' | 'COPY' | 'READ_COMPLETE' | 'EDIT' | 'FOCUS'

// dwellMs 仅 READ_COMPLETE/FOCUS 类事件有意义；metadata 预留 JSON 字符串透传
// 上下文（如触发改写的原消息 id），避免后续新增字段时再改接口签名。
export interface RecordEventBody {
  eventType: EventType
  dwellMs?: number
  metadata?: string
}

// 后端聚合视图：各事件计数 + 平均停留 + 独立用户数。
// averageDwellMs 用 null（而非 0）区分"无样本"与"停留 0ms"，防止误判冷启动消息。
export interface MessageSignals {
  messageId: string
  rewriteCount: number
  copyCount: number
  readCompleteCount: number
  editCount: number
  averageDwellMs: number | null
  uniqueUsers: number
}

// 消息交互埋点 REST 客户端，对接后端 MessageEventController（/messages/{id}/events）
// 与 AdminMessageSignalController（/admin/messages/{id}/signals）。
// 职责：上报用户对 AI 回复的隐式行为（重写/复制/读完整/编辑/聚焦），
// 并在管理端聚合查询曝光/停留等指标，反哺 Moderator Agent 的发言编排与质量评估。
// 上报与聚合查询拆成两条独立接口，避免一个失败拖垮另一条调用链。

// record 走用户态接口（任意登录用户可触发），adminSignals 走 /admin 前缀
// 由后端做角色校验，调用方需确保只在管理员页面调用后者。
export const messageEventsApi = {
  /**
   * 上报单条消息的一次用户行为事件。
   * HTTP POST /messages/{messageId}/events。
   * 调用方：MessageList 的「改写/复制/读完/聚焦」事件回调。
   */
  record: (messageId: string, body: RecordEventBody) =>
    api.post(`/messages/${messageId}/events`, body).then(res => res.data),

  /**
   * 管理端拉取单条消息的聚合信号（各事件计数 + 平均停留 + 独立用户数）。
   * HTTP GET /admin/messages/{messageId}/signals。
   * 调用方：管理端消息详情面板。
   */
  adminSignals: (messageId: string) =>
    api.get<MessageSignals>(`/admin/messages/${messageId}/signals`).then(res => res.data)
}
