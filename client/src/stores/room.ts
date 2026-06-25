import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { roomsApi, type RoomMemberResponse } from '@/api/rooms'
import type { Room, CreateRoomRequest, UpdateRoomModeRequest } from '@/types'

/**
 * 聊天室全局状态中心（Pinia setup store）。
 * 把"全站可见"的房间列表、当前进入的房间、我的房间列表、成员列表收敛到这里，
 * 避免在多个页面（RoomListView / RoomDetailView / CreateRoomDialog）之间重复拉接口或互相传 ref。
 */
export const useRoomStore = defineStore('room', () => {
  // State
  // 全量房间列表（公共浏览用），与 myRooms 解耦：用户可能没加入但可以预览/加入
  const rooms = ref<Room[]>([])
  // 当前正在查看的房间；切房间时由 setCurrentRoom / fetchRoomById 写入
  const currentRoom = ref<Room | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)
  // 标记当前房间是否处于"多角色讨论"模式，用于 UI 决定是否展示讨论流相关控件
  const isDiscussing = ref(false)
  // 当前用户已加入的房间；侧栏"我的聊天室"专用
  const myRooms = ref<Room[]>([])
  const myRoomsLoading = ref(false)
  // 当前房间成员列表；详情页邀请/移除成员时使用
  const roomMembers = ref<RoomMemberResponse[]>([])
  const roomMembersLoading = ref(false)

  // Computed
  // 公共列表按 updatedAt 倒序：最近有改动的房间最相关，置顶展示
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
  // 冷却窗口：刚访问过的房间不立即按 lastEnterTime 重排，防止"点哪条哪条跳第一"造成的视觉抖动
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
  // 拉公共房间列表；失败时清空列表，避免展示旧数据误导用户
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

  // 拉"我加入的"列表；这里不设置顶层 error，因为侧栏降级为空态不影响主流程
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

  // 按 id 拉单个房间：同步更新 rooms 缓存（如果存在）并设为 currentRoom；调用方负责后续跳转
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

  // 入参容忍 "null"/"undefined" 字符串：路由 params 在某些跳转路径下会序列化成字符串，需要防御性短路
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

  /**
 * 邀请成员加入当前房间：根据用户名或邮箱匹配用户并加入。
 * 副作用：成功后自动重新拉取成员列表，让 UI 立即看到新增成员，无需调用方再次调用 fetchRoomMembers。
 * 抛错策略：参数无效抛业务错（房间不存在 / 关键字为空），API 失败抛后端 message，供 UI toast 展示。
 * 调用方：RoomDetailView 的「邀请成员」表单。
 */
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
      // 前端去重：本地缓存里找到"同 owner + 同角色集合"的房间，直接复用。
      // 这层在前端做可以让用户重复点击时立刻跳转（不发请求、不等后端返回），
      // 即便绕过前端 guard，后端 RoomService.create 也有同样查重兜底。
      const sortedIds = (characterIds ?? []).slice().sort()
      const existing = myRooms.value.find(r =>
        r.characters &&
        r.characters.map(c => c.id).sort().join('|') === sortedIds.join('|')
      )
      if (existing) {
        console.log('[DEBUG] createRoom dedup hit, reusing:', existing.id)
        return existing
      }
      const request: CreateRoomRequest = { name, topic, characterIds, mode }
      const room = await roomsApi.create(request)
      // 后端可能因为"同 owner + 同角色集合"去重返回已有房间，
      // 此时不能再 push/unshift，避免 store 里出现重复条目。
      const existingIdx = myRooms.value.findIndex(r => r.id === room.id)
      if (existingIdx === -1) {
        rooms.value.push(room)
        myRooms.value.unshift(room)
      }
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

  /**
 * 记录用户进入房间：写入后端 lastEnterTime 用于跨设备 LRU 排序，同时本地立刻更新 myRooms 缓存让侧栏重排。
 * 失败仅打印日志：进入记录是「统计/排序」类弱关键数据，不应阻塞用户进入房间的主流程。
 * 调用方：RoomDetailView 的 onMounted、router 进入聊天页守卫。
 */
  async function recordEnter(roomId: string) {
    try {
      await roomsApi.recordEnter(roomId)
      // 在本地更新 lastEnterTime 以立刻获得 UI 反馈
      const room = myRooms.value.find(r => r.id === roomId)
      if (room) {
        room.lastEnterTime = new Date().toISOString()
      }
    } catch (e) {
      console.error('[DEBUG] recordEnter failed:', e)
    }
  }

  /**
 * 显式设置 currentRoom：通常由 RoomDetailView 的 onMounted 或 router 守卫调用。
 * 副作用：顺手把 isDiscussing 也同步上（按 room.chatMode 判断），UI 不必再单独维护讨论模式开关。
 * 调用方：RoomDetailView 挂载、router.beforeEach。
 */
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
