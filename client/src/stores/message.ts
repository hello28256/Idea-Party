import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { ChatMessage } from '@/composables/useSocket'
import { messagesApi } from '@/api/messages'

const LOCAL_STORAGE_KEY_PREFIX = 'idea-party-messages-'

export const useMessageStore = defineStore('message', () => {
  // State
  const messages = ref<ChatMessage[]>([])
  const thinkingCharacterId = ref<string | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)
  const paused = ref(false)
  const currentRoomId = ref<string | null>(null) // Track current room for localStorage

  // Discussion mode state
  const discussionPhase = ref<'IDLE' | 'MODERATING' | 'SPEAKING' | 'WAITING_FOR_USER' | 'PAUSED'>('IDLE')
  const selectedCharacterIds = ref<string[]>([])
  const moderatorMessage = ref<{ content: string; type: string } | null>(null)

  // Streaming message buffer (for in-progress messages being streamed)
  // Using Record instead of Map for reliable Vue 3 reactivity
  const streamingMessages = ref<Record<string, string>>({})

  // Local storage helpers
  function getStorageKey(roomId: string): string {
    return LOCAL_STORAGE_KEY_PREFIX + roomId
  }

  function loadFromLocal(roomId: string): ChatMessage[] {
    try {
      const stored = localStorage.getItem(getStorageKey(roomId))
      if (stored) {
        console.log('[MessageStore] Loaded from localStorage:', roomId, 'count:', JSON.parse(stored).length)
        return JSON.parse(stored)
      }
    } catch (e) {
      console.warn('[MessageStore] Failed to load from localStorage:', e)
    }
    return []
  }

  function saveToLocal(roomId: string, msgs: ChatMessage[]) {
    try {
      localStorage.setItem(getStorageKey(roomId), JSON.stringify(msgs))
      console.log('[MessageStore] Saved to localStorage:', roomId, 'count:', msgs.length)
    } catch (e) {
      console.warn('[MessageStore] Failed to save to localStorage:', e)
    }
  }

  function clearLocal(roomId: string) {
    try {
      localStorage.removeItem(getStorageKey(roomId))
    } catch (e) {
      console.warn('[MessageStore] Failed to clear localStorage:', e)
    }
  }

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
        // 保存到本地（使用 currentRoomId 而非 msg.roomId）
        if (currentRoomId.value) {
          saveToLocal(currentRoomId.value, messages.value)
        }
        return
      }
    }
    messages.value.push(msg)

    // 保存到本地（使用 currentRoomId）
    if (currentRoomId.value) {
      saveToLocal(currentRoomId.value, messages.value)
    }

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
      currentRoomId.value = null
      return
    }

    // 设置当前房间 ID
    currentRoomId.value = roomId
    loading.value = true
    error.value = null

    // 1. 先从本地缓存加载（快速显示）
    const localMsgs = loadFromLocal(roomId)
    if (localMsgs.length > 0) {
      messages.value = localMsgs
    }

    // 2. 再从服务器获取最新数据（保证数据一致性）
    try {
      const data = await messagesApi.getByRoom(roomId)
      // 如果服务器返回空数据且本地有缓存，保留本地缓存
      if (data.length === 0 && localMsgs.length > 0) {
        console.log('[MessageStore] Server empty, using local cache for', roomId)
      } else {
        messages.value = data
        // 保存到本地
        saveToLocal(roomId, data)
      }
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load messages'
      // 如果服务器请求失败但本地有缓存，保留本地缓存，不抛出错误
      if (localMsgs.length > 0) {
        console.warn('[MessageStore] Server load failed, using local cache')
      } else {
        console.error('[MessageStore] Server load failed, no local cache')
      }
    } finally {
      loading.value = false
    }
  }

  function clearMessages(roomId?: string, clearLocal = false) {
    messages.value = []
    thinkingCharacterId.value = null
    streamingMessages.value = {}
    // 只有明确要求时才清除本地缓存（用于退出房间时）
    if (clearLocal && roomId) {
      clearLocal(roomId)
    }
    // 清除当前房间 ID
    currentRoomId.value = null
  }

  function setCurrentRoom(roomId: string | null) {
    currentRoomId.value = roomId
  }

  function setPaused() {
    paused.value = true
  }

  function setResumed() {
    paused.value = false
  }

  function setDiscussionPhase(phase: string, characters?: string[], message?: string) {
    discussionPhase.value = phase as any
    if (characters) {
      selectedCharacterIds.value = characters
    }
    if (message) {
      moderatorMessage.value = { content: message, type: phase }
    }
  }

  function clearModeratorMessage() {
    moderatorMessage.value = null
  }

  return {
    // State
    messages,
    thinkingCharacterId,
    loading,
    error,
    streamingMessages,
    paused,
    discussionPhase,
    selectedCharacterIds,
    moderatorMessage,
    currentRoomId,
    // Actions
    addMessage,
    updateStreamingMessage,
    completeStreamingMessage,
    setThinking,
    clearThinking,
    setPaused,
    setResumed,
    loadMessages,
    clearMessages,
    setCurrentRoom,
    setDiscussionPhase,
    clearModeratorMessage
  }
})
