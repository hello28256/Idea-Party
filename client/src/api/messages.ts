import { api } from '@/services/api'
import type { ChatMessage } from '@/composables/useSocket'

// 消息域 API 适配层，对接后端 MessageController（/rooms/{id}/messages）。
// 后端 service 返回的 Message 与前端 socket 流式消息使用的 ChatMessage
// 结构兼容但分属不同类型，需要在这里做一次桥接，避免把类型耦合泄露到业务侧。
export const messagesApi = {
  /**
   * 拉取指定房间的历史消息（首屏一次性加载，增量由 socket 流式回推）。
   * HTTP GET /rooms/{roomId}/messages。
   * 调用方：ChatRoomView 挂载时初始化消息列表。
   */
  async getByRoom(roomId: string): Promise<ChatMessage[]> {
    const msgs = await api.getMessages(roomId)
    // 后端 Message 字段与前端 ChatMessage 一一对应，但类型由不同模块声明
    // （REST vs WebSocket），这里用双重断言收敛到前端统一类型供 UI 消费。
    return msgs as unknown as ChatMessage[]
  }
}
