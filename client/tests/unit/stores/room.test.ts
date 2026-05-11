import { setActivePinia, createPinia } from 'pinia'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { useRoomStore } from '@/stores/room'

// Mock the API
vi.mock('@/api/rooms', () => ({
  roomsApi: {
    list: vi.fn(),
    getById: vi.fn(),
    create: vi.fn(),
    remove: vi.fn(),
    addCharacter: vi.fn(),
    updateMode: vi.fn()
  }
}))

import { roomsApi } from '@/api/rooms'

describe('useRoomStore', () => {
  const mockRoom = {
    id: 'room-123',
    name: 'Test Room',
    topic: 'Test Topic',
    ownerId: 'user-123',
    ownerName: 'Test Owner',
    characterCount: 0,
    characters: [],
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z'
  }

  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('should have empty rooms on init', () => {
    const store = useRoomStore()
    expect(store.rooms).toEqual([])
    expect(store.currentRoom).toBeNull()
    expect(store.loading).toBe(false)
    expect(store.error).toBeNull()
  })

  it('fetchRooms should populate rooms', async () => {
    const mockRooms = [mockRoom, { ...mockRoom, id: 'room-456', name: 'Another Room' }]
    vi.mocked(roomsApi.list).mockResolvedValue(mockRooms)

    const store = useRoomStore()
    await store.fetchRooms()

    expect(store.rooms).toEqual(mockRooms)
    expect(roomsApi.list).toHaveBeenCalled()
  })

  it('fetchRooms should handle errors', async () => {
    vi.mocked(roomsApi.list).mockRejectedValue(new Error('Network error'))

    const store = useRoomStore()

    await expect(store.fetchRooms()).rejects.toThrow('Network error')
    expect(store.error).toBe('Network error')
  })

  it('createRoom should add to rooms', async () => {
    const newRoom = { ...mockRoom, id: 'room-new', name: 'New Room' }
    vi.mocked(roomsApi.create).mockResolvedValue(newRoom)

    const store = useRoomStore()
    const result = await store.createRoom('New Room', 'New Topic')

    expect(store.rooms.length).toBe(1)
    expect(store.rooms[0].id).toBe('room-new')
    expect(roomsApi.create).toHaveBeenCalledWith({ name: 'New Room', topic: 'New Topic' })
  })

  it('setCurrentRoom should update currentRoom', () => {
    const store = useRoomStore()

    store.setCurrentRoom(mockRoom)

    expect(store.currentRoom).toEqual(mockRoom)
  })

  it('setCurrentRoom should set isDiscussing based on chatMode', () => {
    const store = useRoomStore()

    // dialogue mode
    store.setCurrentRoom({ ...mockRoom, chatMode: 'dialogue' })
    expect(store.isDiscussing).toBe(false)

    // discussion mode
    store.setCurrentRoom({ ...mockRoom, chatMode: 'discussion' })
    expect(store.isDiscussing).toBe(true)
  })

  it('deleteRoom should remove from rooms', async () => {
    const store = useRoomStore()
    store.rooms = [mockRoom, { ...mockRoom, id: 'room-to-delete' }]

    vi.mocked(roomsApi.remove).mockResolvedValue(undefined)

    await store.deleteRoom('room-to-delete')

    expect(store.rooms.length).toBe(1)
    expect(store.rooms[0].id).toBe('room-123')
    expect(roomsApi.remove).toHaveBeenCalledWith('room-to-delete')
  })

  it('deleteRoom should clear currentRoom if deleted', async () => {
    const store = useRoomStore()
    store.rooms = [mockRoom]
    store.currentRoom = mockRoom

    vi.mocked(roomsApi.remove).mockResolvedValue(undefined)

    await store.deleteRoom('room-123')

    expect(store.currentRoom).toBeNull()
  })

  it('addCharacterToRoom should update room in list', async () => {
    const updatedRoom = { ...mockRoom, characterCount: 1 }
    vi.mocked(roomsApi.addCharacter).mockResolvedValue(updatedRoom)

    const store = useRoomStore()
    store.rooms = [mockRoom]

    const result = await store.addCharacterToRoom('room-123', 'char-123')

    expect(store.rooms[0].characterCount).toBe(1)
    expect(result.characterCount).toBe(1)
  })

  it('updateRoomMode should update room settings', async () => {
    const updatedRoom = { ...mockRoom, chatMode: 'discussion', maxDiscussionRounds: 10 }
    vi.mocked(roomsApi.updateMode).mockResolvedValue(updatedRoom)

    const store = useRoomStore()
    store.rooms = [mockRoom]
    store.currentRoom = mockRoom

    const result = await store.updateRoomMode('room-123', { chatMode: 'discussion', maxDiscussionRounds: 10 })

    expect(store.rooms[0].chatMode).toBe('discussion')
    expect(store.currentRoom?.chatMode).toBe('discussion')
  })

  it('sortedRooms should return rooms sorted by updatedAt descending', () => {
    const store = useRoomStore()
    const olderRoom = { ...mockRoom, id: 'room-old', updatedAt: '2026-01-01T00:00:00Z' }
    const newerRoom = { ...mockRoom, id: 'room-new', updatedAt: '2026-01-02T00:00:00Z' }

    store.rooms = [olderRoom, newerRoom]

    const sorted = store.sortedRooms

    expect(sorted[0].id).toBe('room-new')
    expect(sorted[1].id).toBe('room-old')
  })
})
