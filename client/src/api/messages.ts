import { api } from '@/services/api'
import type { ChatMessage } from '@/composables/useSocket'

export const messagesApi = {
  async getByRoom(roomId: string): Promise<ChatMessage[]> {
    const msgs = await api.getMessages(roomId)
    return msgs as unknown as ChatMessage[]
  }
}
