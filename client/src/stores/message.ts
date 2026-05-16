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
  const streamingMessages = ref<Map<string, string>>(new Map())

  // Actions
  function addMessage(msg: ChatMessage) {
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
    const existing = streamingMessages.value.get(characterId) || ''
    streamingMessages.value.set(characterId, existing + chunk)
  }

  function completeStreamingMessage(characterId: string) {
    streamingMessages.value.delete(characterId)
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
    streamingMessages.value.clear()
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
