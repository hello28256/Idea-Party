import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { ChatMessage } from '@/composables/useSocket'
import { messagesApi } from '@/api/messages'

export const useMessageStore = defineStore('message', () => {
  // State
  const messages = ref<ChatMessage[]>([])
  const thinkingCharacterId = ref<string | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)

  // Streaming message buffer (for in-progress messages being streamed)
  // Using Record instead of Map for reliable Vue 3 reactivity
  const streamingMessages = ref<Record<string, string>>({})

  // Actions
  function addMessage(msg: ChatMessage) {
    // 收到完整消息时，自动清除该角色的流式消息 bubble
    if (msg.senderType === 'CHARACTER' && msg.characterId) {
      delete streamingMessages.value[msg.characterId]
    }

    // Deduplication: if this message has a real id and there's a matching temp message,
    // replace the temp message instead of adding a new one
    if (msg.id && !msg.id.startsWith('temp-')) {
      const tempIndex = messages.value.findIndex(
        m => m.id.startsWith('temp-') &&
             m.content === msg.content &&
             m.senderType === msg.senderType
      )
      if (tempIndex !== -1) {
        messages.value[tempIndex] = msg
        return
      }
    }
    messages.value.push(msg)
    // Clear thinking indicator when a message arrives from that character
    if (thinkingCharacterId.value === msg.characterId) {
      thinkingCharacterId.value = null
    }
  }

  function updateStreamingMessage(characterId: string, chunk: string) {
    // 使用 Record 而不是 Map，Vue 对普通对象的属性赋值有完善的响应式追踪
    const existing = streamingMessages.value[characterId] || ''
    const safeChunk = chunk ?? ''
    streamingMessages.value[characterId] = existing + safeChunk
  }

  function completeStreamingMessage(characterId: string) {
    delete streamingMessages.value[characterId]
  }

  function setThinking(characterId: string | null) {
    thinkingCharacterId.value = characterId
  }

  function clearThinking() {
    thinkingCharacterId.value = null
  }

  async function loadMessages(roomId: string | null | undefined) {
    if (!roomId || roomId === 'null' || roomId === 'undefined') {
      console.warn('[MessageStore] skip loadMessages: invalid roomId', roomId)
      messages.value = []
      return
    }
    loading.value = true
    error.value = null
    try {
      const data = await messagesApi.getByRoom(roomId)
      messages.value = data
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load messages'
      throw e
    } finally {
      loading.value = false
    }
  }

  function clearMessages() {
    messages.value = []
    thinkingCharacterId.value = null
    streamingMessages.value = {}
  }

  return {
    // State
    messages,
    thinkingCharacterId,
    loading,
    error,
    streamingMessages,
    // Actions
    addMessage,
    updateStreamingMessage,
    completeStreamingMessage,
    setThinking,
    clearThinking,
    loadMessages,
    clearMessages
  }
})
