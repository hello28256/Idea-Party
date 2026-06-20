// 消息 store：维护当前房间的聊天消息列表、流式缓冲、AI 思考指示、讨论模式阶段机与消息反馈乐观更新。
// 协作模块：ChatRoomPanel（订阅）、useSocket（事件入口）、MessageList（渲染）、MessageFeedback（反馈提交）。

import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { ChatMessage, MessageFeedbackPayload } from '@/composables/useSocket'
import { messagesApi } from '@/api/messages'
import { messageFeedbackApi } from '@/api/messageFeedback'

// localStorage 的命名空间前缀，避免和同源其他应用/旧版本 key 冲突
const LOCAL_STORAGE_KEY_PREFIX = 'idea-party-messages-'

/**
 * 全局聊天消息状态中心。
 * 负责：消息列表、流式缓冲、AI 思考指示、讨论模式阶段机、消息反馈乐观更新，
 * 以及按房间维度的 localStorage 缓存（提升进入房间首屏速度 + 离线容错）。
 * 数据来源：WebSocket（流式/完整消息）+ REST（历史拉取、反馈提交）。
 */
export const useMessageStore = defineStore('message', () => {
  // State
  const messages = ref<ChatMessage[]>([])
  // 当前正在「思考中」提示框绑定的角色 id：AI 发言前由 WebSocket 事件 setThinking 置入，addMessage 收到该角色完整消息后自动清除
  const thinkingCharacterId = ref<string | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)
  // 用户在 UI 暂停了 AI 继续发言的开关：true 时前端停止渲染流式/完整消息推送但保持订阅，恢复时不补发中间过程
  const paused = ref(false)
  // 记录当前所在房间：所有本地缓存写入都绑这个 id，避免 msg.roomId 为空/undefined 时把数据写到错误的 key
  const currentRoomId = ref<string | null>(null)

  // Discussion mode state
  // 阶段机由后端 Moderator Agent 驱动；前端只镜像状态用于 UI 展示
  const discussionPhase = ref<'IDLE' | 'MODERATING' | 'SPEAKING' | 'WAITING_FOR_USER' | 'PAUSED'>('IDLE')
  const selectedCharacterIds = ref<string[]>([])
  const moderatorMessage = ref<{ content: string; type: string } | null>(null)

  // Streaming message buffer (for in-progress messages being streamed)
  // Vue 3 对 Map 的深层响应式追踪不可靠；用普通对象保证 streamingMessages.value[characterId] = ... 触发更新
  const streamingMessages = ref<Record<string, string>>({})

  // Local storage helpers
  // 三个 localStorage helper 全部 try/catch：Safari 隐私模式 / 配额超限 / SSR 环境下 storage 可能不可用，失败不能让 UI 崩溃
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
  /**
   * 推入一条消息。承担三件事：
   * 1) CHARACTER 完整消息到达时清除对应角色的流式 bubble（流结束）。
   * 2) 临时消息（id 以 'temp-' 开头，是前端乐观占位）与后端返回的正式消息按 内容+senderType 去重合并。
   * 3) 写入后同步 localStorage 并清掉该角色的 thinking 指示。
   */
  function addMessage(msg: ChatMessage) {
    // 收到完整消息时，自动清除该角色的流式消息 bubble
    if (msg.senderType === 'CHARACTER' && msg.characterId) {
      delete streamingMessages.value[msg.characterId]
    }

    // 去重：若本消息带真实 id 且存在匹配的临时消息，
    // 则替换临时消息而不是新增
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

    // 当该角色的消息到达时，清除思考指示器
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
    // 不依赖 addMessage 的清理路径的兜底：当后端以「完整消息」事件直接结束流、不再走流式追加时，显式清掉流式 bubble
    delete streamingMessages.value[characterId]
  }

  function setThinking(characterId: string | null) {
    thinkingCharacterId.value = characterId
  }

  function clearThinking() {
    thinkingCharacterId.value = null
  }

  /**
   * 加载某个房间的历史消息。采用「本地优先 + 后台校准」策略：
   * 先用 localStorage 立刻渲染旧消息避免空屏，再异步请求服务端；
   * 服务端返回非空就以服务端为准并回写本地；服务端失败或返回空且本地有数据则保留本地缓存（容错）。
   * 入参允许 null/undefined/'null'/'undefined'：路由参数未解码时常见，视为「无房间」。
   */
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

  /**
   * 清空当前会话的全部运行时状态。
   * clearLocal 默认 false：切房间/重置视图时保留本地缓存以便快速回切；
   * 传 true 才真正删除 localStorage（用户主动退出房间场景）。
   */
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
    // 后端 Moderator Agent 推送的事件桥接：phase 强转 any 是因为后端枚举可能扩展（如新增 EVALUATING），前端不强校验避免阻塞 UI
    // type 用 phase 复用：moderatorMessage 不携带独立 type 字段，渲染时直接拿 phase 作为样式 key，减少协议字段
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

  /**
   * 设置或取消某条消息的反馈。
   * 乐观更新 + 失败回滚 + localStorage 同步。
   * payload = null 表示取消反馈（删除）。
   */
  async function setFeedback(messageId: string, payload: MessageFeedbackPayload | null) {
    const idx = messages.value.findIndex(m => m.id === messageId)
    if (idx === -1) return
    const prev = messages.value[idx].feedback ?? null

    // 乐观写
    messages.value[idx] = {
      ...messages.value[idx],
      feedback: payload
    }
    if (currentRoomId.value) {
      saveToLocal(currentRoomId.value, messages.value)
    }

    try {
      if (payload === null) {
        await messageFeedbackApi.remove(messageId)
      } else {
        await messageFeedbackApi.submit(messageId, {
          type: payload.type,
          category: payload.category,
          comment: payload.comment
        })
      }
    } catch (e) {
      // 回滚
      messages.value[idx] = {
        ...messages.value[idx],
        feedback: prev
      }
      if (currentRoomId.value) {
        saveToLocal(currentRoomId.value, messages.value)
      }
      throw e
    }
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
    clearModeratorMessage,
    setFeedback
  }
})
