import { io } from 'socket.io-client'
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

const DEFAULT_SERVER_URL = 'http://localhost:8080'

export function useSocket(roomId: string, options: UseSocketOptions = {}) {
  const {
    onMessage,
    onThinking,
    onStream,
    onError
  } = options

  const socket = io(DEFAULT_SERVER_URL, {
    transports: ['websocket', 'polling'],
    autoConnect: true,
    path: '/ws'
  })

  const isConnected = ref(false)

  socket.on('connect', () => {
    isConnected.value = true
    socket.emit('join room', { roomId })
  })

  socket.on('disconnect', () => {
    isConnected.value = false
  })

  // Handle incoming messages
  socket.on('message', (data: ChatMessage) => {
    onMessage?.(data)
  })

  // Handle character thinking indicator
  socket.on('character thinking', (data: { characterId: string }) => {
    onThinking?.(data.characterId)
  })

  // Handle streaming chunks
  socket.on('message stream', (data: { characterId: string; chunk: string }) => {
    onStream?.(data)
  })

  // Handle errors
  socket.on('error', (data: { message: string }) => {
    onError?.(data.message)
  })

  // Handle room joined confirmation
  socket.on('room-joined', (data: { roomId: string }) => {
    console.log('[DEBUG] Joined room:', data.roomId)
  })

  function sendMessage(content: string) {
    socket.emit('chat message', { roomId, content })
  }

  function leaveRoom() {
    socket.emit('leave room', { roomId })
    socket.disconnect()
  }

  onUnmounted(() => {
    leaveRoom()
  })

  return {
    socket,
    isConnected,
    sendMessage,
    leaveRoom
  }
}
