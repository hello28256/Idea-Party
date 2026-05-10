import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { roomsApi } from '@/api/rooms'
import type { Room, CreateRoomRequest, UpdateRoomModeRequest } from '@/types'

export const useRoomStore = defineStore('room', () => {
  // State
  const rooms = ref<Room[]>([])
  const currentRoom = ref<Room | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)
  const isDiscussing = ref(false) // 讨论模式下的持续讨论状态

  // Computed
  const sortedRooms = computed(() => {
    return [...rooms.value].sort((a, b) => {
      const dateA = new Date(a.updatedAt).getTime()
      const dateB = new Date(b.updatedAt).getTime()
      return dateB - dateA
    })
  })

  // Actions
  async function fetchRooms() {
    loading.value = true
    error.value = null
    try {
      rooms.value = await roomsApi.list()
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to fetch rooms'
      throw e
    } finally {
      loading.value = false
    }
  }

  async function fetchRoomById(id: string) {
    loading.value = true
    error.value = null
    try {
      const room = await roomsApi.getById(id)
      const index = rooms.value.findIndex(r => r.id === id)
      if (index !== -1) {
        rooms.value[index] = room
      }
      currentRoom.value = room
      return room
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to fetch room'
      throw e
    } finally {
      loading.value = false
    }
  }

  async function createRoom(name: string, topic?: string): Promise<Room> {
    loading.value = true
    error.value = null
    try {
      const request: CreateRoomRequest = { name, topic }
      const room = await roomsApi.create(request)
      rooms.value.push(room)
      return room
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to create room'
      throw e
    } finally {
      loading.value = false
    }
  }

  async function deleteRoom(id: string) {
    loading.value = true
    error.value = null
    try {
      await roomsApi.remove(id)
      rooms.value = rooms.value.filter(r => r.id !== id)
      if (currentRoom.value?.id === id) {
        currentRoom.value = null
      }
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to delete room'
      throw e
    } finally {
      loading.value = false
    }
  }

  async function addCharacterToRoom(roomId: string, characterId: string) {
    loading.value = true
    error.value = null
    try {
      const updatedRoom = await roomsApi.addCharacter(roomId, characterId)
      const index = rooms.value.findIndex(r => r.id === roomId)
      if (index !== -1) {
        rooms.value[index] = updatedRoom
      }
      if (currentRoom.value?.id === roomId) {
        currentRoom.value = updatedRoom
      }
      return updatedRoom
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to add character'
      throw e
    } finally {
      loading.value = false
    }
  }

  async function updateRoomMode(roomId: string, data: UpdateRoomModeRequest) {
    loading.value = true
    error.value = null
    try {
      const updatedRoom = await roomsApi.updateMode(roomId, data)
      const index = rooms.value.findIndex(r => r.id === roomId)
      if (index !== -1) {
        rooms.value[index] = updatedRoom
      }
      if (currentRoom.value?.id === roomId) {
        currentRoom.value = updatedRoom
      }
      return updatedRoom
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to update room mode'
      throw e
    } finally {
      loading.value = false
    }
  }

  function setCurrentRoom(room: Room | null) {
    currentRoom.value = room
    isDiscussing.value = room?.chatMode === 'discussion'
  }

  return {
    // State
    rooms,
    currentRoom,
    loading,
    error,
    isDiscussing,
    // Computed
    sortedRooms,
    // Actions
    fetchRooms,
    fetchRoomById,
    createRoom,
    deleteRoom,
    addCharacterToRoom,
    updateRoomMode,
    setCurrentRoom
  }
})
