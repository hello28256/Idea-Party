import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { roomsApi, type RoomMemberResponse } from '@/api/rooms'
import type { Room, CreateRoomRequest, UpdateRoomModeRequest } from '@/types'

export const useRoomStore = defineStore('room', () => {
  // State
  const rooms = ref<Room[]>([])
  const currentRoom = ref<Room | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)
  const isDiscussing = ref(false)
  const myRooms = ref<Room[]>([])
  const myRoomsLoading = ref(false)
  const roomMembers = ref<RoomMemberResponse[]>([])
  const roomMembersLoading = ref(false)

  // Computed
  const sortedRooms = computed(() => {
    return [...rooms.value].sort((a, b) => {
      const dateA = new Date(a.updatedAt).getTime()
      const dateB = new Date(b.updatedAt).getTime()
      return dateB - dateA
    })
  })

  /**
   * LRU 排序（带冷却）：
   *  - 15 分钟内刚点过的房间：时间视为"未确定"，按原顺序排（避免频繁点导致列表乱跳）
   *  - 15 分钟没动过的：按 lastEnterTime 倒序（最新用过的在最上）
   *  - 从未进入过的：按 updatedAt 排（最近编辑/创建在最上）
   */
  const LRU_COOLDOWN_MS = 15 * 60 * 1000
  const sortedMyRooms = computed(() => {
    const now = Date.now()
    const rank = (r: Room): number => {
      const lastEnter = r.lastEnterTime ? new Date(r.lastEnterTime).getTime() : 0
      if (lastEnter && now - lastEnter < LRU_COOLDOWN_MS) {
        // 15 分钟内刚点过：时间视为 NULL，按 createdAt 兜底
        // 相同 createdAt 时保持原顺序（用 -createdAt 让创建晚的靠前）
        return new Date(r.createdAt).getTime() || 0
      }
      // 过了冷却期：按 lastEnterTime 排
      return lastEnter || new Date(r.updatedAt || r.createdAt).getTime() || 0
    }
    return [...myRooms.value].sort((a, b) => rank(b) - rank(a))
  })

  // Actions
  async function fetchRooms() {
    loading.value = true
    error.value = null
    try {
      rooms.value = await roomsApi.list()
    } catch (e) {
      console.error('[DEBUG] fetchRooms failed:', e)
      error.value = e instanceof Error ? e.message : '房间加载失败'
      rooms.value = []
    } finally {
      loading.value = false
    }
  }

  async function fetchMyRooms() {
    myRoomsLoading.value = true
    try {
      myRooms.value = await roomsApi.getMyRooms()
    } catch (e) {
      console.error('[DEBUG] fetchMyRooms failed:', e)
      myRooms.value = []
    } finally {
      myRoomsLoading.value = false
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

  async function fetchRoomMembers(roomId: string | null | undefined) {
    if (!roomId || roomId === 'null' || roomId === 'undefined') {
      console.warn('[Room] skip fetchRoomMembers: invalid roomId', roomId)
      roomMembers.value = []
      return
    }
    roomMembersLoading.value = true
    try {
      roomMembers.value = await roomsApi.getRoomMembers(roomId)
    } catch (e) {
      console.error('[DEBUG] fetchRoomMembers failed:', e)
      roomMembers.value = []
    } finally {
      roomMembersLoading.value = false
    }
  }

  async function inviteRoomMember(roomId: string | null | undefined, keyword: string) {
    if (!roomId || roomId === 'null' || roomId === 'undefined') {
      throw new Error('当前聊天室不存在，无法邀请成员')
    }
    if (!keyword || !keyword.trim()) {
      throw new Error('请输入用户名或邮箱')
    }
    try {
      const member = await roomsApi.inviteMember(roomId, keyword.trim())
      await fetchRoomMembers(roomId)
      return member
    } catch (e: any) {
      const msg =
        e?.response?.data?.message ||
        e?.response?.data?.error ||
        e?.message ||
        '邀请失败，请稍后重试'
      throw new Error(msg)
    }
  }

  async function createRoom(
    name: string,
    topic?: string,
    characterIds?: string[],
    mode?: 'single' | 'group'
  ): Promise<Room> {
    loading.value = true
    error.value = null
    try {
      const request: CreateRoomRequest = { name, topic, characterIds, mode }
      const room = await roomsApi.create(request)
      rooms.value.push(room)
      myRooms.value.unshift(room)
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
      myRooms.value = myRooms.value.filter(r => r.id !== id)
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
      const myIndex = myRooms.value.findIndex(r => r.id === roomId)
      if (myIndex !== -1) {
        myRooms.value[myIndex] = updatedRoom
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
    console.log('[DEBUG] updateRoomMode called:', { roomId, data })
    try {
      const updatedRoom = await roomsApi.updateMode(roomId, data)
      console.log('[DEBUG] API returned updatedRoom:', updatedRoom)
      console.log('[DEBUG] updatedRoom.chatMode:', updatedRoom.chatMode)
      const index = rooms.value.findIndex(r => r.id === roomId)
      if (index !== -1) {
        rooms.value[index] = updatedRoom
        console.log('[DEBUG] Updated rooms at index:', index)
      }
      const myIndex = myRooms.value.findIndex(r => r.id === roomId)
      if (myIndex !== -1) {
        myRooms.value[myIndex] = updatedRoom
        console.log('[DEBUG] Updated myRooms at index:', myIndex)
      }
      if (currentRoom.value?.id === roomId) {
        currentRoom.value = updatedRoom
        console.log('[DEBUG] Updated currentRoom')
      }
      return updatedRoom
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to update room mode'
      console.error('[DEBUG] updateRoomMode error:', e)
      throw e
    } finally {
      loading.value = false
    }
  }

  async function recordEnter(roomId: string) {
    try {
      await roomsApi.recordEnter(roomId)
      // Update the lastEnterTime locally for immediate UI feedback
      const room = myRooms.value.find(r => r.id === roomId)
      if (room) {
        room.lastEnterTime = new Date().toISOString()
      }
    } catch (e) {
      console.error('[DEBUG] recordEnter failed:', e)
    }
  }

  function setCurrentRoom(room: Room | null) {
    currentRoom.value = room
    isDiscussing.value = room?.chatMode === 'discussion'
  }

  return {
    rooms,
    currentRoom,
    loading,
    error,
    isDiscussing,
    myRooms,
    myRoomsLoading,
    roomMembers,
    roomMembersLoading,
    sortedRooms,
    sortedMyRooms,
    fetchRooms,
    fetchMyRooms,
    fetchRoomById,
    fetchRoomMembers,
    inviteRoomMember,
    createRoom,
    deleteRoom,
    addCharacterToRoom,
    updateRoomMode,
    recordEnter,
    setCurrentRoom
  }
})
