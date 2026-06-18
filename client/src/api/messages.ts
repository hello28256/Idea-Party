import { api } from '@/services/api'
import type { ChatMessage } from '@/composables/useSocket'

// 消息相关的 API 适配层。
// 后端 service 返回的 Message 与前端 socket 流式消息使用的 ChatMessage
// 结构兼容但分属不同类型，需要在这里做一次桥接，避免把类型耦合泄露到业务侧。
export const messagesApi = {
  async getByRoom(roomId: string): Promise<ChatMessage[]> {
    const msgs = await api.getMessages(roomId)
    // 后端 Message 字段与前端 ChatMessage 一一对应，但类型由不同模块声明
    // （REST vs WebSocket），这里用双重断言收敛到前端统一类型供 UI 消费。
    return msgs as unknown as ChatMessage[]
  }
}
