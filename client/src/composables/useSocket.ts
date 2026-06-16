import { ref, onUnmounted } from 'vue'

export interface MessageFeedbackPayload {
  type: 'LIKE' | 'DISLIKE'
  category: string | null
  comment: string | null
  createdAt: string
}

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

export interface UseSocketOptions {
  onMessage?: (message: ChatMessage) => void
  onThinking?: (characterId: string | null) => void
  onStream?: (data: { characterId: string; chunk: string }) => void
  onError?: (error: string) => void
  onPaused?: () => void
  onResumed?: () => void
  onDiscussionState?: (data: { phase: string; selectedCharacters?: string[]; message?: string }) => void
  onModeratorMessage?: (data: { content: string; type: string }) => void
}

// Resolve the WebSocket endpoint:
//   1. VITE_WS_URL (build-time) — use it as a full base (e.g. wss://api.example.com)
//   2. Otherwise — same-origin /ws (works in dev via vite proxy and in prod via nginx)
// The 'localhost' hard-coding is removed so the same bundle works in any domain.
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
  const isConnected = ref(false)

  ws.onopen = () => {
    isConnected.value = true
    // Send join room message with JWT for authentication
    sendSocketIO('join room', { roomId, token })
  }

  ws.onclose = () => {
    isConnected.value = false
  }

  ws.onerror = (error) => {
    console.error('[DEBUG] WebSocket error:', error)
    onError?.('Connection error')
  }

  ws.onmessage = (event) => {
    const data = event.data
    // Handle Socket.IO protocol messages (42 prefix)
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
            onError?.(eventData.message)
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

  function sendSocketIO(event: string, data: object) {
    const message = `42${JSON.stringify([event, data])}`
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(message)
    }
  }

  function sendMessage(content: string) {
    sendSocketIO('chat message', { roomId, content })
  }

  function stopDiscussion() {
    sendSocketIO('stop-discussion', { roomId })
  }

  function pauseDiscussion() {
    sendSocketIO('pause-discussion', { roomId })
  }

  function resumeDiscussion() {
    sendSocketIO('resume-discussion', { roomId })
  }

  function leaveRoom() {
    sendSocketIO('leave room', { roomId })
    ws.close()
  }

  onUnmounted(() => {
    leaveRoom()
  })

  return {
    socket: ws,
    isConnected,
    sendMessage,
    stopDiscussion,
    pauseDiscussion,
    resumeDiscussion,
    leaveRoom
  }
}
