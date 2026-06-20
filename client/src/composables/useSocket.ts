import { ref, onUnmounted } from 'vue'

// 单聊天室 WebSocket 客户端 composable：封装 Socket.IO 协议解析、事件分发与生命周期管理。
// 调用方：ChatRoom 视图挂载时调用一次，isConnected 驱动 UI 状态条，sendMessage 等方法转给 ChatInput。

// 用户对单条 AI 回复的点赞/点踩反馈载荷。
// 与后端 MessageFeedback 实体对齐：category/comment 允许为空，避免前端必填校验阻塞快速反馈。
export interface MessageFeedbackPayload {
  type: 'LIKE' | 'DISLIKE'
  category: string | null
  comment: string | null
  createdAt: string
}

// 前端展示用的聊天消息统一模型。
// 同时承载用户消息与 AI 角色消息，通过 senderType 区分；characterId/characterName/userId 允许为 null
// 是因为另一方（USER vs CHARACTER）天然没有这些字段，避免在边界处做有歧义的空字符串。
export interface ChatMessage {
  id: string
  roomId: string
  characterId: string | null
  characterName: string | null
  senderType: 'USER' | 'CHARACTER'
  userId: string | null
  content: string
  avatarUrl: string | null
  createdAt: string
  /** 当前登录用户对该消息的反馈。未反馈 = undefined，老 localStorage 数据兼容。 */
  feedback?: MessageFeedbackPayload | null
}

// useSocket 的回调契约：每个回调对应一种服务端事件，调用方按需实现。
// 拆成多个独立回调而不是单一 onEvent，是为了让消费组件（如 ChatRoom）按职责直接绑定，
// 避免在组件里再做一层 switch 分发，事件类型将来扩展时也不会污染所有调用方。
export interface UseSocketOptions {
  onMessage?: (message: ChatMessage) => void
  onThinking?: (characterId: string | null) => void
  onStream?: (data: { characterId: string; chunk: string }) => void
  onError?: (error: { message: string; code?: string }) => void
  onPaused?: () => void
  onResumed?: () => void
  onDiscussionState?: (data: { phase: string; selectedCharacters?: string[]; message?: string }) => void
  onModeratorMessage?: (data: { content: string; type: string }) => void
}

// 解析 WebSocket 端点：
//   1. 优先用 VITE_WS_URL（构建期注入）—— 完整 base，例如 wss://api.example.com
//   2. 否则走同源 /ws —— 开发期靠 Vite proxy、生产期靠 nginx 反代，自动适配任何域名
// 之所以去掉「localhost 硬编码」：同一份 bundle 需要在 dev / preview / 生产环境间无缝切换，硬编码 host 会破坏部署灵活性
const WS_BASE_URL: string = (() => {
  const explicit = import.meta.env.VITE_WS_URL
  if (explicit && typeof explicit === 'string' && explicit.trim() !== '') {
    return explicit.replace(/\/+$/, '')
  }
  if (typeof window !== 'undefined' && window.location?.host) {
    const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    return `${proto}//${window.location.host}`
  }
  // SSR / tests fallback
  return `ws://localhost:${import.meta.env.VITE_SERVER_PROXY_PORT || '8080'}`
})()

// 单个聊天室的长连接 composable。
// 接管一条 WebSocket 的生命周期（建联/鉴权/事件分发/卸载断开），把 Socket.IO 协议细节屏蔽掉，
// 让上层组件只关心 onMessage/onStream 等业务事件；roomId 闭包到所有 send 方法，保证不会跨房间误发。
export function useSocket(roomId: string, options: UseSocketOptions = {}, token?: string | null) {
  const {
    onMessage,
    onThinking,
    onStream,
    onError,
    onPaused,
    onResumed,
    onDiscussionState,
    onModeratorMessage
  } = options

  const ws = new WebSocket(`${WS_BASE_URL}/ws`)
  // 连接状态：驱动 UI 顶部「连接中/已断线」提示条；与 ws.readyState 的区别是它是响应式 ref，组件可直接 watch。
  const isConnected = ref(false)

  ws.onopen = () => {
    isConnected.value = true
    // 握手成功后立即把 JWT 随 join room 一起发过去，让服务端在订阅时就能建立 userId ↔ session 映射
    sendSocketIO('join room', { roomId, token })
  }

  // onclose 不做自动重连：聊天室的可用性严重依赖 JWT 与房间状态，自动重连会引入"幽灵恢复"
  // 风险；上层若需要断线感知，由 isConnected 驱动 UI 提示即可，重连由用户手动触发。
  ws.onclose = () => {
    isConnected.value = false
  }

  ws.onerror = (error) => {
    console.error('[DEBUG] WebSocket error:', error)
    onError?.({ message: 'Connection error' })
  }

  // Socket.IO Engine.IO 报文以数字前缀分类：40=message, 42=event。
  // 服务端用的是 spring-boot-starter-engineioclient 的事件模式（`event` 帧），
  // 所以这里只关心 42 前缀，并按 [eventName, payload] 解包后做事件名分发。
  // 解析失败只打日志、不抛错，避免一条坏消息把整条连接拖死。
  ws.onmessage = (event) => {
    const data = event.data
    // 只解析 Socket.IO 的 MESSAGE 帧（42 前缀）：其它帧（connect=40、ack、pong=3）由原生 onopen 流程处理
    if (typeof data === 'string' && data.startsWith('42')) {
      try {
        const parsed = JSON.parse(data.substring(2))
        const eventName = parsed[0]
        const eventData = parsed[1]

        switch (eventName) {
          case 'chat message':
            onMessage?.(eventData)
            break
          case 'character thinking':
            onThinking?.(eventData.characterId)
            break
          case 'chat chunk':
          case 'message stream':
            console.log('[WS FRONTEND] chat chunk TS=' + Date.now() + ' charId=' + eventData.characterId + ' chunkLen=' + (eventData.content?.length ?? 0) + ' content=' + JSON.stringify(eventData.content))
            onStream?.({
              characterId: eventData.characterId,
              chunk: eventData.content ?? ''
            })
            break
          case 'error':
            onError?.({
              message: eventData.message ?? 'Unknown error',
              code: eventData.code
            })
            break
          case 'discussion-paused':
            onPaused?.()
            break
          case 'discussion-resumed':
            onResumed?.()
            break
          case 'room-joined':
            console.log('[DEBUG] Joined room:', eventData.roomId)
            break
          case 'discussion-state':
            onDiscussionState?.(eventData)
            break
          case 'moderator-message':
            onModeratorMessage?.(eventData)
            break
        }
      } catch (e) {
        console.error('[DEBUG] Failed to parse Socket.IO message:', e)
      }
    }
  }

  // 发送一个 Socket.IO event 帧：必须以 `42` 前缀 + `[eventName, payload]` 数组序列化，
  // 与 ws.onmessage 中的解析规则对称。CONNECTING/CLOSING 状态下直接丢弃，
  // 不做缓冲/重排队——聊天场景下宁可丢一条用户消息，也比堆积陈旧状态更易排查。
  function sendSocketIO(event: string, data: object) {
    const message = `42${JSON.stringify([event, data])}`
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(message)
    }
  }

  /**
   * 发送用户聊天消息。
   * 副作用：通过 WebSocket 发送 `chat message` 事件，roomId 自动带上（由闭包注入）。
   * 调用方：ChatInput 的 Enter 提交。
   */
  function sendMessage(content: string) {
    sendSocketIO('chat message', { roomId, content })
  }

  // stopDiscussion 与 pauseDiscussion 的区别：stop 终结本轮讨论（角色不再发言、Moderator 收尾），
  // pause 仅暂停流式输出，恢复后可继续。两者语义不同，因此分别暴露，不要合并。
  /**
   * 终止本轮讨论（不可恢复）。调用方：ChatRoom 顶部的「结束讨论」按钮。
   */
  function stopDiscussion() {
    sendSocketIO('stop-discussion', { roomId })
  }

  /**
   * 暂停流式输出（不结束讨论）。调用方：ChatRoom 的暂停按钮。
   */
  function pauseDiscussion() {
    sendSocketIO('pause-discussion', { roomId })
  }

  /**
   * 恢复被暂停的讨论。调用方：ChatRoom 的恢复按钮（暂停时显示）。
   */
  function resumeDiscussion() {
    sendSocketIO('resume-discussion', { roomId })
  }

  /**
   * 主动离开房间：通知服务端清理订阅并关闭连接。
   * 副作用：发送 `leave room` 事件 + 关闭 WebSocket。
   * 调用方：组件卸载（onUnmounted）自动调用，业务层一般无需手动触发。
   */
  function leaveRoom() {
    sendSocketIO('leave room', { roomId })
    ws.close()
  }

  // 组件卸载时主动 leave + close：避免 HMR / 路由切换后留下幽灵连接在服务端持续计费（AI 流式按 token 计费）。
  onUnmounted(() => {
    leaveRoom()
  })

  return {
    // 暴露原始 ws 仅供调试/测试用例观察 readyState；业务调用方不应直接 send，避免绕开封装。
    socket: ws,
    isConnected,
    sendMessage,
    stopDiscussion,
    pauseDiscussion,
    resumeDiscussion,
    leaveRoom
  }
}
