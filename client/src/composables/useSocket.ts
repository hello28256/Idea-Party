import { ref, onUnmounted } from 'vue'

export interface ChatMessage {
  id: string
  roomId: string
  characterId: string | null
  characterName: string | null
  senderType: 'USER' | 'CHARACTER'
  content: string
  avatarUrl: string | null
  createdAt: string
}

export interface UseSocketOptions {
  onMessage?: (message: ChatMessage) => void
  onThinking?: (characterId: string | null) => void
  onStream?: (data: { characterId: string; chunk: string }) => void
  onError?: (error: string) => void
}

const SERVER_PORT = import.meta.env.VITE_SERVER_PROXY_PORT || '8080'
const DEFAULT_SERVER_URL = `ws://localhost:${SERVER_PORT}`

export function useSocket(roomId: string, options: UseSocketOptions = {}) {
  const {
    onMessage,
    onThinking,
    onStream,
    onError
  } = options

  const ws = new WebSocket(`${DEFAULT_SERVER_URL}/ws`)
  const isConnected = ref(false)

  ws.onopen = () => {
    isConnected.value = true
    // Send join room message using Socket.IO protocol format
    sendSocketIO('join room', { roomId })
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
            onThinking?.(eventData.characterName)
            break
          case 'message stream':
            onStream?.(eventData)
            break
          case 'error':
            onError?.(eventData.message)
            break
          case 'room-joined':
            console.log('[DEBUG] Joined room:', eventData.roomId)
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
    leaveRoom
  }
}
