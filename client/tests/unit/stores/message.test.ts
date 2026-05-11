import { setActivePinia, createPinia } from 'pinia'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { useMessageStore } from '@/stores/message'

// Mock the API
vi.mock('@/api/messages', () => ({
  messagesApi: {
    getByRoom: vi.fn()
  }
}))

// Mock useSocket types
interface ChatMessage {
  id: string
  roomId: string
  characterId?: string
  characterName?: string
  senderType: 'USER' | 'CHARACTER'
  content: string
  avatarUrl?: string
  createdAt: string
}

describe('useMessageStore', () => {
  const mockMessage: ChatMessage = {
    id: 'msg-123',
    roomId: 'room-123',
    characterId: 'char-123',
    characterName: 'Test Character',
    senderType: 'CHARACTER',
    content: 'Hello, world!',
    avatarUrl: 'https://example.com/avatar.png',
    createdAt: '2024-01-01T00:00:00Z'
  }

  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('should have empty messages on init', () => {
    const store = useMessageStore()
    expect(store.messages).toEqual([])
    expect(store.thinkingCharacterId).toBeNull()
    expect(store.loading).toBe(false)
    expect(store.error).toBeNull()
  })

  it('addMessage should append to messages', () => {
    const store = useMessageStore()

    store.addMessage(mockMessage)

    expect(store.messages.length).toBe(1)
    expect(store.messages[0]).toEqual(mockMessage)
  })

  it('addMessage should deduplicate temp messages', () => {
    const store = useMessageStore()
    const tempMessage: ChatMessage = {
      id: 'temp-123',
      roomId: 'room-123',
      senderType: 'USER',
      content: 'Hello',
      createdAt: '2024-01-01T00:00:00Z'
    }
    const realMessage: ChatMessage = {
      id: 'msg-real-123',
      roomId: 'room-123',
      senderType: 'USER',
      content: 'Hello',
      createdAt: '2024-01-01T00:01:00Z'
    }

    store.addMessage(tempMessage)
    expect(store.messages.length).toBe(1)

    // Adding a real message with same content should replace temp
    store.addMessage(realMessage)
    expect(store.messages.length).toBe(1)
    expect(store.messages[0].id).toBe('msg-real-123')
  })

  it('addMessage should clear thinking indicator when message arrives from that character', () => {
    const store = useMessageStore()
    store.thinkingCharacterId = 'char-123'

    store.addMessage(mockMessage)

    expect(store.thinkingCharacterId).toBeNull()
  })

  it('loadMessages should set messages', async () => {
    const mockMessages = [mockMessage, { ...mockMessage, id: 'msg-456', content: 'Another message' }]
    const { messagesApi } = await import('@/api/messages')
    vi.mocked(messagesApi.getByRoom).mockResolvedValue(mockMessages)

    const store = useMessageStore()
    await store.loadMessages('room-123')

    expect(store.messages).toEqual(mockMessages)
    expect(messagesApi.getByRoom).toHaveBeenCalledWith('room-123')
  })

  it('loadMessages should handle errors', async () => {
    const { messagesApi } = await import('@/api/messages')
    vi.mocked(messagesApi.getByRoom).mockRejectedValue(new Error('Failed to load'))

    const store = useMessageStore()

    await expect(store.loadMessages('room-123')).rejects.toThrow('Failed to load')
    expect(store.error).toBe('Failed to load')
  })

  it('clearMessages should empty array', () => {
    const store = useMessageStore()
    store.messages = [mockMessage, { ...mockMessage, id: 'msg-456' }]
    store.thinkingCharacterId = 'char-123'

    store.clearMessages()

    expect(store.messages).toEqual([])
    expect(store.thinkingCharacterId).toBeNull()
  })

  it('setThinking should update thinkingCharacterId', () => {
    const store = useMessageStore()

    store.setThinking('char-123')

    expect(store.thinkingCharacterId).toBe('char-123')
  })

  it('clearThinking should set thinkingCharacterId to null', () => {
    const store = useMessageStore()
    store.thinkingCharacterId = 'char-123'

    store.clearThinking()

    expect(store.thinkingCharacterId).toBeNull()
  })

  it('updateStreamingMessage should accumulate chunks', () => {
    const store = useMessageStore()

    store.updateStreamingMessage('char-123', 'Hello')
    store.updateStreamingMessage('char-123', ' world')

    expect(store.streamingMessages.get('char-123')).toBe('Hello world')
  })

  it('completeStreamingMessage should remove from buffer', () => {
    const store = useMessageStore()
    store.streamingMessages.set('char-123', 'Hello world')

    store.completeStreamingMessage('char-123')

    expect(store.streamingMessages.has('char-123')).toBe(false)
  })

  it('multiple characters can have streaming messages simultaneously', () => {
    const store = useMessageStore()

    store.updateStreamingMessage('char-1', 'Response 1')
    store.updateStreamingMessage('char-2', 'Response 2')

    expect(store.streamingMessages.get('char-1')).toBe('Response 1')
    expect(store.streamingMessages.get('char-2')).toBe('Response 2')
  })
})
