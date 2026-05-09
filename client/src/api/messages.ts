import { api } from '@/services/api'
import type { ChatMessage } from '@/composables/useSocket'

export const messagesApi = {
  async getByRoom(roomId: string): Promise<ChatMessage[]> {
    const response = await api.get<ChatMessage[]>(`/rooms/${roomId}/messages`)
    return response.data
  }
}
