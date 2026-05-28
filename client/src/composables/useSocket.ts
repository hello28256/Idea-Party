import { ref, onUnmounted } from 'vue'

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

const SERVER_PORT = import.meta.env.VITE_SERVER_PROXY_PORT || '8080'
const DEFAULT_SERVER_URL = `ws://localhost:${SERVER_PORT}`

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

  const ws = new WebSocket(`${DEFAULT_SERVER_URL}/ws`)
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
