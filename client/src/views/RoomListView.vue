<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed, watch } from 'vue'
import { charactersApi } from '@/api/characters'
import { useRouter, useRoute } from 'vue-router'
import { useRoomStore } from '@/stores/room'
import { useCharacterStore } from '@/stores/character'
import { useAuthStore } from '@/stores/auth'
import CreateRoomModal from '@/components/room/CreateRoomModal.vue'
import CreateCharacterModal from '@/components/character/CreateCharacterModal.vue'
import UserDropdown from '@/components/ui/UserDropdown.vue'
import ChatRoomPanel from '@/components/chat/ChatRoomPanel.vue'

const router = useRouter()
const route = useRoute()
const roomStore = useRoomStore()
const characterStore = useCharacterStore()
const authStore = useAuthStore()

const showCreateModal = ref(false)
const showCreateCharacterModal = ref(false)
const showAddExistingCharacterModal = ref(false)
const showCreateDropdown = ref(false)
const showEditCharacterModal = ref(false)
const editingCharacter = ref<any>(null)

// Create character in room context state
const createCharacterRoomId = ref<string | null>(null)
const dropdownRef = ref<HTMLElement | null>(null)
const selectedCategory = ref('all')
const searchQuery = ref('')
const mounted = ref(false)

// Selected room for chat panel (my-rooms tab two-column layout)
const selectedRoomId = ref<string | null>(null)

// Three-way collapsible layout state
const SIDEBAR_STATE_KEY = 'idea-party-chat-layout-state'
const isGlobalSidebarCollapsed = ref(false)
const isRoomListCollapsed = ref(true)
const isRolePanelCollapsed = ref(true)

// Members panel state
const showMembersTab = ref(false)
const showInviteModal = ref(false)
const inviteKeyword = ref('')
const inviteLoading = ref(false)
const inviteError = ref<string | null>(null)

// Resolve avatar URL for member avatars
function resolveAvatarUrl(url: string | null | undefined): string {
  if (!url) return ''
  if (url.startsWith('http://') || url.startsWith('https://')) return url
  if (url.startsWith('/api/')) return url
  if (url.startsWith('/uploads/')) return url
  if (url.startsWith('/')) return url
  return url
}

// Load saved collapse state from localStorage
function loadLayoutState() {
  try {
    const saved = localStorage.getItem(SIDEBAR_STATE_KEY)
    if (saved) {
      const state = JSON.parse(saved)
      isGlobalSidebarCollapsed.value = !!state.isGlobalSidebarCollapsed
      isRoomListCollapsed.value = state.isRoomListCollapsed ?? true
      isRolePanelCollapsed.value = state.isRolePanelCollapsed ?? true
    }
  } catch (e) {
    console.error('[DEBUG] Failed to load layout state:', e)
  }
}

// Save collapse state to localStorage
function saveLayoutState() {
  try {
    localStorage.setItem(
      SIDEBAR_STATE_KEY,
      JSON.stringify({
        isGlobalSidebarCollapsed: isGlobalSidebarCollapsed.value,
        isRoomListCollapsed: isRoomListCollapsed.value,
        isRolePanelCollapsed: isRolePanelCollapsed.value
      })
    )
  } catch (e) {
    console.error('[DEBUG] Failed to save layout state:', e)
  }
}

// Watch for collapse state changes
watch(
  [isGlobalSidebarCollapsed, isRoomListCollapsed, isRolePanelCollapsed],
  () => {
    saveLayoutState()
  }
)

// Current room characters - read from currentRoom which is updated by addCharacterToRoom
const currentRoomCharacters = computed(() => {
  if (!selectedRoomId.value) return []
  if (roomStore.currentRoom?.id === selectedRoomId.value) {
    return roomStore.currentRoom.characters || []
  }
  const room = roomStore.myRooms.find(r => r.id === selectedRoomId.value)
  return room?.characters || []
})

// Current room conversation mode (single 1-on-1 vs group multi-character).
// Driven by the backend room.mode field; legacy rooms default to 'group' on the server.
const currentRoomMode = computed<'single' | 'group'>(() => {
  if (!selectedRoomId.value) return 'group'
  if (roomStore.currentRoom?.id === selectedRoomId.value) {
    return (roomStore.currentRoom as any).mode || 'group'
  }
  const room = roomStore.myRooms.find(r => r.id === selectedRoomId.value)
  return ((room as any)?.mode as 'single' | 'group') || 'group'
})

// Characters available to add to the current room (all known - already-in-room)
const availableCharactersForRoom = computed(() => {
  const inRoom = new Set(currentRoomCharacters.value.map((c: any) => c.id))
  return characterStore.characters.filter((c: any) => !inRoom.has(c.id))
})

// Current room chat mode
const currentChatMode = computed(() => {
  if (!selectedRoomId.value) return 'dialogue'
  if (roomStore.currentRoom?.id === selectedRoomId.value) {
    return roomStore.currentRoom.chatMode || 'dialogue'
  }
  const room = roomStore.myRooms.find(r => r.id === selectedRoomId.value)
  return room?.chatMode || 'dialogue'
})

// Switch between dialogue and discussion mode
async function switchMode(mode: 'dialogue' | 'discussion') {
  console.log('[DEBUG] switchMode called with mode:', mode)
  console.log('[DEBUG] selectedRoomId:', selectedRoomId.value)
  console.log('[DEBUG] currentChatMode:', currentChatMode.value)

  if (!selectedRoomId.value) {
    console.log('[DEBUG] No room selected, returning')
    return
  }
  if (mode === currentChatMode.value) {
    console.log('[DEBUG] Mode already set to:', mode, ', skipping')
    return
  }

  try {
    console.log('[DEBUG] Calling roomStore.updateRoomMode with mode:', mode)
    const result = await roomStore.updateRoomMode(selectedRoomId.value, { chatMode: mode })
    console.log('[DEBUG] updateRoomMode succeeded, result:', result)
  } catch (e) {
    console.error('[DEBUG] Failed to switch mode:', e)
    // 显示错误给用户
    const errorMsg = e instanceof Error ? e.message : '切换模式失败，请重试'
    console.error('[DEBUG] Error details:', errorMsg)
    alert('切换模式失败: ' + errorMsg)
  }
}

// Sync selectedRoomId with URL query
watch(
  () => route.query.roomId as string | undefined,
  (roomId) => {
    if (roomId && roomId !== 'null' && roomId !== 'undefined' && roomId.trim() !== '') {
      selectedRoomId.value = roomId
    } else {
      selectedRoomId.value = null
    }
  },
  { immediate: true }
)

// Auto-expand room list when entering my-rooms tab without a selected room
watch(
  [() => route.query.tab, () => route.query.roomId],
  ([tab, roomId]) => {
    if ((tab === 'my-rooms' || tab === 'recent') && !roomId) {
      isRoomListCollapsed.value = false
    }
  },
  { immediate: true }
)

// Navigation items
const navItems = [
  { id: 'discover', label: '发现', emoji: '🔍' },
  { id: 'characters', label: '角色库', emoji: '📚' },
  { id: 'my-rooms', label: '我的聊天', emoji: '💬' },
]

// Active nav item based on current route
const activeNavId = computed(() => {
  const path = route.path
  const tab = route.query.tab as string
  if (tab === 'my-rooms') return 'my-rooms'
  if (tab === 'recent') return 'recent'
  if (path === '/rooms' || path === '/') return 'discover'
  if (path.startsWith('/characters')) return 'characters'
  if (path.startsWith('/chat')) return 'discover'
  return 'discover'
})

// Whether to show characters library content
const isCharactersView = computed(() => {
  return route.path.startsWith('/characters') && !route.path.includes('/create')
})

// Whether to show my rooms content
const isMyRoomsView = computed(() => {
  return activeNavId.value === 'my-rooms' || activeNavId.value === 'recent'
})

// Watch for tab changes to fetch my rooms
watch(
  () => route.query.tab as string | undefined,
  (tab) => {
    if (tab === 'my-rooms' || tab === 'recent') {
      roomStore.fetchMyRooms()
    }
  },
  { immediate: true }
)

// Get current user's characters
const myCharacters = computed(() => {
  if (!authStore.user) return []
  return characterStore.characters.filter(
    c => c.ownerId === authStore.user!.id && !c.isPreset
  )
})

// Format date
function formatDate(dateStr: string): string {
  const date = new Date(dateStr)
  const now = new Date()
  const diff = now.getTime() - date.getTime()
  const days = Math.floor(diff / (1000 * 60 * 60 * 24))

  if (days === 0) return '今天'
  if (days === 1) return '昨天'
  if (days < 7) return `${days}天前`
  if (days < 30) return `${Math.floor(days / 7)}周前`
  return date.toLocaleDateString('zh-CN')
}

// Edit character modal functions
function openEditCharacterModal(character: any) {
  editingCharacter.value = { ...character }
  showEditCharacterModal.value = true
}

function closeEditCharacterModal() {
  showEditCharacterModal.value = false
  editingCharacter.value = null
}

function handleCharacterUpdated(updatedCharacter: any) {
  // Update the character in the store
  const index = characterStore.characters.findIndex((c: any) => c.id === updatedCharacter.id)
  if (index !== -1) {
    characterStore.characters[index] = updatedCharacter
  }
  // Refresh room data if in a chat room
  if (selectedRoomId.value) {
    roomStore.fetchRoomById(selectedRoomId.value)
  }
  closeEditCharacterModal()
}

// Start chat with a character - creates/joins a single-person chat room
async function startChat(character: any) {
  try {
    console.log('[DEBUG] startChat called with character:', character)

    // Refresh myRooms to get the latest data
    await roomStore.fetchMyRooms()

    console.log('[DEBUG] myRooms after fetch:', JSON.stringify(roomStore.myRooms.map(r => ({
      id: r.id,
      name: r.name,
      characters: r.characters
    }))))

    // Check if this character already has a chat room in my-rooms
    const existingRoom = roomStore.myRooms.find(room =>
      room.characters?.some(c => c.id === character.id)
    )

    console.log('[DEBUG] looking for character.id:', character.id)
    console.log('[DEBUG] existingRoom:', existingRoom)

    if (existingRoom) {
      // Navigate to existing room
      router.replace({
        path: '/rooms',
        query: {
          ...route.query,
          tab: 'my-rooms',
          roomId: existingRoom.id
        }
      })
    } else {
      // Create new room for this character
      const room = await roomStore.createRoom(character.name)
      await roomStore.addCharacterToRoom(room.id, character.id)
      // Navigate to my-rooms tab with the room selected
      router.replace({
        path: '/rooms',
        query: {
          ...route.query,
          tab: 'my-rooms',
          roomId: room.id
        }
      })
    }
  } catch (e) {
    console.error('[DEBUG] Failed to start chat:', e)
    alert('创建对话失败，请重试')
  }
}

// Navigation handler
function handleNavClick(itemId: string) {
  if (itemId === 'discover') {
    router.push('/rooms')
  } else if (itemId === 'characters') {
    router.push('/characters')
  } else if (itemId === 'trending') {
    router.push('/rooms?tab=trending')
  } else if (itemId === 'categories') {
    router.push('/rooms?tab=categories')
  } else if (itemId === 'my-rooms') {
    router.push('/rooms?tab=my-rooms')
  } else if (itemId === 'recent') {
    router.push('/rooms?tab=recent')
  }
}

// Categories for tabs
const categories = [
  { id: 'all', label: '全部', emoji: '✨' },
  { id: 'scientist', label: '科学家', emoji: '🔬', color: '#4F7DF3' },
  { id: 'star', label: '明星', emoji: '🌟', color: '#F472B6' },
  { id: 'entrepreneur', label: '企业家', emoji: '🚀', color: '#FB923C' },
  { id: 'philosopher', label: '哲学家', emoji: '💭', color: '#8B5CF6' },
  { id: 'athlete', label: '运动员', emoji: '🏆', color: '#10B981' },
  { id: 'writer', label: '作家', emoji: '📖', color: '#34D399' },
  { id: 'anime', label: '动漫', emoji: '🎨', color: '#EC4899' },
  { id: 'historical', label: '历史人物', emoji: '🏛️', color: '#D4AF6A' },
]

// Featured characters - loaded from API
const featuredCharacters = ref<any[]>([])
const featuredCharactersLoading = ref(false)

async function fetchFeaturedCharacters() {
  featuredCharactersLoading.value = true
  try {
    const characters = await charactersApi.getRecommended()
    featuredCharacters.value = characters.data.map((char: any) => ({
      id: char.id,
      name: char.name,
      role: char.description || 'AI 角色',
      avatar: char.avatarUrl || `https://api.dicebear.com/7.x/personas/svg?seed=${encodeURIComponent(char.name)}&backgroundColor=c0aede`,
      online: false
    }))
  } catch (e) {
    console.error('[DEBUG] Failed to fetch featured characters:', e)
    featuredCharacters.value = []
  } finally {
    featuredCharactersLoading.value = false
  }
}

// Room cards data
const roomCardsData = [
  {
    id: '1',
    title: 'AI 会取代人类创造力吗？',
    cover: 'https://images.unsplash.com/photo-1620712943543-bcc4688e7485?w=400&h=225&fit=crop',
    participants: ['爱因斯坦', '马斯克', '宫崎骏'],
    participantAvatars: [
      'https://api.dicebear.com/7.x/personas/svg?seed=Einstein&backgroundColor=b6e3f4',
      'https://api.dicebear.com/7.x/personas/svg?seed=Musk&backgroundColor=d1d4f9',
      'https://api.dicebear.com/7.x/personas/svg?seed=Miyazaki&backgroundColor=ffdfbf'
    ],
    latestMessage: { sender: '爱因斯坦', text: '时间并不是线性的...' },
    onlineCount: 128,
    messageCount: 892,
    category: 'scientist',
    isHot: true
  },
  {
    id: '2',
    title: '天赋与努力，哪个更重要？',
    cover: 'https://images.unsplash.com/photo-1579952363873-27f3bade9f55?w=400&h=225&fit=crop',
    participants: ['梅西', '乔丹', '泰勒'],
    participantAvatars: [
      'https://api.dicebear.com/7.x/personas/svg?seed=Messi&backgroundColor=c0aede',
      'https://api.dicebear.com/7.x/personas/svg?seed=Jordan&backgroundColor=ffd5dc',
      'https://api.dicebear.com/7.x/personas/svg?seed=Taylor&backgroundColor=c0aede'
    ],
    latestMessage: { sender: '梅西', text: '每天训练8小时...' },
    onlineCount: 256,
    messageCount: 1543,
    category: 'athlete',
    isHot: true
  },
  {
    id: '3',
    title: '时间是否真实存在？',
    cover: 'https://images.unsplash.com/photo-1531297484001-80022131f5a1?w=400&h=225&fit=crop',
    participants: ['苏格拉底', '爱因斯坦', '牛顿'],
    participantAvatars: [
      'https://api.dicebear.com/7.x/personas/svg?seed=Socrates&backgroundColor=d1f4d1',
      'https://api.dicebear.com/7.x/personas/svg?seed=Einstein&backgroundColor=b6e3f4',
      'https://api.dicebear.com/7.x/personas/svg?seed=Newton&backgroundColor=c4b5fd'
    ],
    latestMessage: { sender: '苏格拉底', text: '我知道我一无所知...' },
    onlineCount: 89,
    messageCount: 567,
    category: 'philosopher',
    isHot: false
  },
  {
    id: '4',
    title: '创作的本质是什么？',
    cover: 'https://images.unsplash.com/photo-1513364776144-60967b0f800f?w=400&h=225&fit=crop',
    participants: ['宫崎骏', '莎士比亚', '泰勒'],
    participantAvatars: [
      'https://api.dicebear.com/7.x/personas/svg?seed=Miyazaki&backgroundColor=ffdfbf',
      'https://api.dicebear.com/7.x/personas/svg?seed=Shakespeare&backgroundColor=e0c3fc',
      'https://api.dicebear.com/7.x/personas/svg?seed=Taylor&backgroundColor=c0aede'
    ],
    latestMessage: { sender: '宫崎骏', text: '创造让世界更温暖...' },
    onlineCount: 167,
    messageCount: 723,
    category: 'anime',
    isHot: true
  },
  {
    id: '5',
    title: '星际旅行能实现吗？',
    cover: 'https://images.unsplash.com/photo-1446776811953-b23d57bd21aa?w=400&h=225&fit=crop',
    participants: ['马斯克', '爱因斯坦'],
    participantAvatars: [
      'https://api.dicebear.com/7.x/personas/svg?seed=Musk&backgroundColor=d1d4f9',
      'https://api.dicebear.com/7.x/personas/svg?seed=Einstein&backgroundColor=b6e3f4'
    ],
    latestMessage: { sender: '马斯克', text: '2050年火星城市...' },
    onlineCount: 312,
    messageCount: 2104,
    category: 'entrepreneur',
    isHot: true
  },
  {
    id: '6',
    title: '音乐能改变世界吗？',
    cover: 'https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=400&h=225&fit=crop',
    participants: ['泰勒', '贝多芬'],
    participantAvatars: [
      'https://api.dicebear.com/7.x/personas/svg?seed=Taylor&backgroundColor=c0aede',
      'https://api.dicebear.com/7.x/personas/svg?seed=Beethoven&backgroundColor=ffd5dc'
    ],
    latestMessage: { sender: '泰勒', text: '每一首歌都是一个故事...' },
    onlineCount: 198,
    messageCount: 945,
    category: 'star',
    isHot: false
  },
]

// Recent chats - computed from sortedMyRooms (max 4)
const recentChats = computed(() => {
  return roomStore.sortedMyRooms.slice(0, 4).map(room => ({
    id: room.id,
    name: room.name,
    lastMessage: room.topic || '开始聊天吧',
    avatar: room.characters && room.characters.length > 0
      ? (room.characters[0].avatarUrl || null)
      : null
  }))
})

onMounted(() => {
  roomStore.fetchRooms()
  roomStore.fetchMyRooms()
  characterStore.fetchCharacters()
  fetchFeaturedCharacters()
  setTimeout(() => { mounted.value = true }, 50)

  // Close dropdown when clicking outside
  document.addEventListener('click', handleClickOutside)

  // Load saved collapse state
  loadLayoutState()
})

onUnmounted(() => {
  document.removeEventListener('click', handleClickOutside)
})

function handleClickOutside(e: MouseEvent) {
  if (dropdownRef.value && !dropdownRef.value.contains(e.target as Node)) {
    closeCreateDropdown()
  }
}

function handleRoomCreated(roomId: string) {
  // Navigate to my-rooms tab with the new room selected
  router.replace({
    path: '/rooms',
    query: {
      tab: 'my-rooms',
      roomId: roomId
    }
  })
}

function selectRoom(roomId: string) {
  roomStore.recordEnter(roomId)
  selectedRoomId.value = roomId
  router.replace({
    path: '/rooms',
    query: {
      ...route.query,
      tab: 'my-rooms',
      roomId: roomId
    }
  })
}

// For demo/placeholder rooms in Discover view - still uses old navigation
async function enterRoom(roomId: string) {
  await roomStore.recordEnter(roomId)
  selectedRoomId.value = roomId
  router.replace({
    path: '/rooms',
    query: {
      tab: 'my-rooms',
      roomId: roomId
    }
  })
}

function toggleCreateDropdown(e: Event) {
  e.stopPropagation()
  showCreateDropdown.value = !showCreateDropdown.value
}

function closeCreateDropdown() {
  showCreateDropdown.value = false
}

function handleCreateCharacter() {
  closeCreateDropdown()
  showCreateCharacterModal.value = true
}

async function handleCharacterCreated(character: any) {
  showCreateCharacterModal.value = false
  // First fetch to sync with server
  await characterStore.fetchCharacters()
  // Ensure the newly created character is in the store (in case API response doesn't include it yet)
  if (character && !characterStore.characters.find(c => c.id === character.id)) {
    characterStore.characters.unshift(character)
  }
  // If created from room context, add to room
  if (createCharacterRoomId.value && character) {
    await roomStore.addCharacterToRoom(createCharacterRoomId.value, character.id)
    await roomStore.fetchRoomById(createCharacterRoomId.value)
    createCharacterRoomId.value = null
  }
}

function handleCreateRoom() {
  closeCreateDropdown()
  showCreateModal.value = true
}

async function handleAddedToRoom(character: any) {
  showCreateCharacterModal.value = false
  // Refresh characters list from API
  await characterStore.fetchCharacters()
  if (selectedRoomId.value && character) {
    await roomStore.addCharacterToRoom(selectedRoomId.value, character.id)
    await roomStore.fetchRoomById(selectedRoomId.value)
  }
}

function openAddCharacterModal() {
  createCharacterRoomId.value = selectedRoomId.value
  showCreateCharacterModal.value = true
}

async function handleInviteMember() {
  if (!inviteKeyword.value.trim() || !selectedRoomId.value) return
  inviteLoading.value = true
  inviteError.value = null
  try {
    await roomStore.inviteRoomMember(selectedRoomId.value, inviteKeyword.value.trim())
    showInviteModal.value = false
    inviteKeyword.value = ''
  } catch (e: any) {
    inviteError.value = e.message
  } finally {
    inviteLoading.value = false
  }
}
</script>

<template>
  <div
    class="page-layout"
    :class="{
      mounted,
      'global-collapsed': isGlobalSidebarCollapsed,
      'room-list-collapsed': isRoomListCollapsed,
      'role-panel-collapsed': isRolePanelCollapsed
    }"
    :style="{
      '--global-sidebar-width': isGlobalSidebarCollapsed ? '72px' : '260px',
      '--room-list-width': isRoomListCollapsed ? '0px' : '320px',
      '--role-panel-width': isRolePanelCollapsed ? '0px' : '280px'
    }"
  >
    <!-- Left Sidebar -->
    <aside class="sidebar">
      <!-- Collapse Button -->
      <button
        class="sidebar-collapse-btn"
        @click="isGlobalSidebarCollapsed = !isGlobalSidebarCollapsed"
        :aria-label="isGlobalSidebarCollapsed ? '展开侧边栏' : '折叠侧边栏'"
      >
        <svg v-if="isGlobalSidebarCollapsed" class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 5l7 7-7 7M5 5l7 7-7 7" />
        </svg>
        <svg v-else class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 19l-7-7 7-7m8 14l-7-7 7-7" />
        </svg>
      </button>

      <!-- Logo -->
      <div class="sidebar-brand">
        <img src="/image.png" alt="logo" class="sidebar-brand-logo" />
        <span class="logo-text">Idea Party</span>
      </div>

      <!-- Create Button with Dropdown -->
      <div
        class="create-dropdown-wrapper"
        ref="dropdownRef"
      >
        <button class="create-btn" @click.stop="toggleCreateDropdown">
          <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
          </svg>
          <span>创建</span>
        </button>

        <!-- Dropdown Menu -->
        <Transition name="dropdown">
          <div
            v-if="showCreateDropdown"
            class="create-dropdown-menu"
            @click.stop
          >
            <button class="dropdown-item" @click.stop="handleCreateCharacter">
              <span class="dropdown-icon">👤</span>
              <span class="dropdown-label">创建角色</span>
            </button>
            <button class="dropdown-item" @click="handleCreateRoom">
              <span class="dropdown-icon">💬</span>
              <span class="dropdown-label">创建聊天室</span>
            </button>
          </div>
        </Transition>
      </div>

      <!-- Navigation -->
      <nav class="nav-menu">
        <a
          v-for="item in navItems"
          :key="item.id"
          href="#"
          class="nav-item"
          :class="{ active: item.id === activeNavId }"
          @click.prevent="handleNavClick(item.id)"
        >
          <span class="nav-emoji">{{ item.emoji }}</span>
          <span class="nav-label">{{ item.label }}</span>
        </a>
      </nav>

      <!-- Recent Chats -->
      <div class="recent-chats">
        <div class="section-header">
          <span class="section-title">最近聊天</span>
        </div>
        <div class="chat-list">
          <a
            v-for="chat in recentChats"
            :key="chat.id"
            href="#"
            class="chat-item"
            @click.prevent="enterRoom(chat.id)"
          >
            <div class="chat-avatar">
              <img v-if="chat.avatar" :src="chat.avatar" :alt="chat.name" />
              <span v-else class="chat-avatar-placeholder">{{ chat.name.charAt(0) }}</span>
            </div>
            <div class="chat-info">
              <span class="chat-name">{{ chat.name }}</span>
              <span class="chat-preview">{{ chat.lastMessage }}</span>
            </div>
          </a>
        </div>
      </div>

      <!-- User Profile -->
      <UserDropdown />
    </aside>

    <!-- Main Content -->
    <main class="main-content">
      <!-- Characters Library View -->
      <template v-if="isCharactersView">
        <header class="content-header">
          <h1 class="page-title">角色库</h1>
          <button class="create-btn-large" @click="showCreateCharacterModal = true">
            <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
            </svg>
            创建角色
          </button>
        </header>

        <!-- Empty State -->
        <div v-if="myCharacters.length === 0" class="empty-state">
          <div class="empty-icon">📚</div>
          <h2 class="empty-title">还没有创建角色</h2>
          <p class="empty-desc">创建你的第一个 AI 角色，开始对话吧！</p>
          <button class="empty-btn" @click="showCreateCharacterModal = true">
            <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
            </svg>
            创建角色
          </button>
        </div>

        <!-- Character Grid -->
        <div v-else class="character-grid">
          <div
            v-for="character in myCharacters"
            :key="character.id"
            class="character-card-item"
          >
            <div class="character-avatar">
              <img
                v-if="character.avatarUrl"
                :src="character.avatarUrl"
                :alt="character.name"
              />
              <span v-else class="avatar-placeholder">{{ character.name.charAt(0) }}</span>
            </div>
            <div class="character-info">
              <h3 class="character-name">{{ character.name }}</h3>
              <p class="character-tagline">{{ character.description || '暂无描述' }}</p>
              <p class="character-date">创建于 {{ formatDate(character.createdAt) }}</p>
            </div>
            <div class="card-footer">
              <button class="chat-btn" @click.stop="startChat(character)">
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
                </svg>
                对话
              </button>
              <button class="edit-btn" @click.stop="openEditCharacterModal(character)">
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" />
                </svg>
                编辑
              </button>
            </div>
          </div>
        </div>
      </template>

      <!-- My Rooms View - Three Column Layout -->
      <template v-else-if="isMyRoomsView">
        <div class="rooms-chat-shell">
          <!-- Left: Room List Panel -->
          <aside class="rooms-list-panel">
            <!-- Collapse handle when room list is collapsed AND a room is selected -->
            <button
              v-if="isRoomListCollapsed && selectedRoomId"
              class="room-list-toggle-btn"
              @click="isRoomListCollapsed = false"
              aria-label="展开聊天室列表"
            >
              <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 5l7 7-7 7M5 5l7 7-7 7" />
              </svg>
            </button>

            <div class="rooms-list-header">
              <div>
                <h2>我的聊天</h2>
              </div>
              <div class="flex items-center gap-2">
                <button class="icon-create-room-button" @click="showCreateModal = true" aria-label="创建聊天室">
                  <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M12 4v16m8-8H4" />
                  </svg>
                </button>
                <button
                  v-if="!isRoomListCollapsed && selectedRoomId"
                  class="icon-close-room-list-button"
                  @click="isRoomListCollapsed = true"
                  aria-label="收起聊天室列表"
                >
                  <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 19l-7-7 7-7m8 14l-7-7 7-7" />
                  </svg>
                </button>
              </div>
            </div>

            <!-- Search -->
            <div class="rooms-search">
              <input v-model="searchQuery" type="text" placeholder="搜索聊天室..." />
            </div>

            <!-- Loading -->
            <div v-if="roomStore.myRoomsLoading" class="rooms-loading">
              <div class="loading-spinner"></div>
              <span>加载中...</span>
            </div>

            <!-- Empty State -->
            <div v-else-if="roomStore.sortedMyRooms.length === 0" class="rooms-empty">
              <div class="empty-icon">💬</div>
              <h3>还没有聊天室</h3>
              <p>创建一个聊天室，邀请多个 AI 角色一起讨论。</p>
              <button class="empty-btn" @click="showCreateModal = true">
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
                </svg>
                创建聊天室
              </button>
            </div>

            <!-- Room List -->
            <div v-else class="rooms-list-scroll">
              <button
                v-for="room in roomStore.sortedMyRooms"
                :key="room.id"
                class="room-list-item"
                :class="{ active: selectedRoomId === room.id }"
                @click="selectRoom(room.id)"
              >
                <div class="room-list-icon">💬</div>
                <div class="room-list-content">
                  <div class="room-list-title-row">
                    <strong>{{ room.name }}</strong>
                    <span>{{ formatDate(room.updatedAt) }}</span>
                  </div>
                  <p>{{ room.topic || (room.characters?.[0]?.description) || '暂无主题' }}</p>
                  <small>{{ room.characterCount }} 个角色</small>
                </div>
              </button>
            </div>
          </aside>

          <!-- Center: Chat Panel -->
          <main class="chat-main-panel">
            <template v-if="selectedRoomId">
              <ChatRoomPanel
                :room-id="selectedRoomId"
                :key="selectedRoomId"
                :embedded="true"
                :show-room-list-toggle="true"
                :show-role-panel-toggle="true"
                :on-toggle-room-list="() => isRoomListCollapsed = !isRoomListCollapsed"
                :on-toggle-role-panel="() => isRolePanelCollapsed = !isRolePanelCollapsed"
              />
            </template>

            <div v-else class="chat-empty-state">
              <div class="chat-empty-icon">💬</div>
              <h2>选择一个聊天室</h2>
              <p>从左侧列表选择聊天室，开始对话。</p>
            </div>
          </main>

          <!-- Right: Characters Panel -->
          <aside class="room-characters-panel">
            <!-- Single Chat Mode: Just show character info without tabs -->
            <template v-if="currentRoomMode === 'single'">
              <div class="panel-tabs-wrapper">
                <button
                  class="icon-close-role-panel-button"
                  @click="isRolePanelCollapsed = true"
                  aria-label="收起角色面板"
                >
                  <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 5l7 7-7 7M5 5l7 7-7 7" />
                  </svg>
                </button>
              </div>
              <div class="characters-panel-header">
                <h3>对话角色</h3>
              </div>
              <div class="characters-list">
                <div class="character-chip-card">
                  <img v-if="currentRoomCharacters[0].avatarUrl" :src="currentRoomCharacters[0].avatarUrl" :alt="currentRoomCharacters[0].name" />
                  <div v-else class="char-avatar-placeholder">{{ currentRoomCharacters[0].name?.charAt(0) }}</div>
                  <div class="character-info-row">
                    <div class="character-info">
                      <strong>{{ currentRoomCharacters[0].name }}</strong>
                      <span>{{ currentRoomCharacters[0].description || '暂无描述' }}</span>
                    </div>
                    <button @click="openEditCharacterModal(currentRoomCharacters[0])" class="edit-char-btn">
                      <svg width="14" height="14" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" />
                      </svg>
                      编辑
                    </button>
                  </div>
                </div>
              </div>
            </template>

            <!-- Group Chat Mode: Show tabs for roles and members -->
            <template v-else>
              <!-- Tab Switcher with Collapse Button -->
              <div class="panel-tabs-wrapper">
                <button
                  class="icon-close-role-panel-button"
                  @click="isRolePanelCollapsed = true"
                  aria-label="收起角色面板"
                >
                  <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 5l7 7-7 7M5 5l7 7-7 7" />
                  </svg>
                </button>
                <div class="panel-tabs">
                  <button
                    class="panel-tab"
                    :class="{ active: !showMembersTab }"
                    @click="showMembersTab = false"
                  >
                    聊天室角色
                  </button>
                  <button
                    class="panel-tab"
                    :class="{ active: showMembersTab }"
                    @click="showMembersTab = true; roomStore.fetchRoomMembers(selectedRoomId!)"
                  >
                    聊天室成员
                  </button>
                </div>
              </div>

              <!-- Characters Tab -->
              <template v-if="!showMembersTab">
                <div class="characters-panel-header">
                  <div>
                    <h3>聊天室角色</h3>
                    <p>{{ currentRoomCharacters.length }} 个角色参与讨论</p>
                  </div>
                  <button @click="openAddCharacterModal" class="add-char-btn">+ 邀请</button>
                </div>

              <div v-if="currentRoomCharacters.length === 0" class="characters-empty">
                  <div class="empty-role-icon">👥</div>
                  <h4>还没有角色</h4>
                  <p>添加角色后，就可以开始多角色对话。</p>
                  <button @click="openAddCharacterModal">添加角色</button>
                </div>

                <div v-else class="characters-list">
                  <div v-for="char in currentRoomCharacters" :key="char.id" class="character-chip-card">
                    <img v-if="char.avatarUrl" :src="char.avatarUrl" :alt="char.name" />
                    <div v-else class="char-avatar-placeholder">{{ char.name?.charAt(0) }}</div>
                    <div>
                      <strong>{{ char.name }}</strong>
                      <span>{{ char.description || '暂无描述' }}</span>
                    </div>
                  </div>
                </div>
              </template>

            <!-- Members Tab -->
            <template v-if="showMembersTab">
              <div class="members-panel-content">
                <div class="members-panel-header">
                  <div>
                    <h3>聊天室成员</h3>
                    <p>{{ roomStore.roomMembers.length }} 人</p>
                  </div>
                  <button @click="showInviteModal = true" class="add-char-btn">+ 邀请</button>
                </div>
                <div class="members-list">
                  <div v-if="roomStore.roomMembersLoading" class="members-loading">
                    加载中...
                  </div>
                  <div v-else-if="roomStore.roomMembers.length === 0" class="members-empty">
                    暂无成员
                  </div>
                  <div v-else v-for="member in roomStore.roomMembers" :key="member.userId" class="member-item">
                    <div class="member-avatar-wrapper">
                      <img
                        v-if="member.avatarUrl"
                        :src="resolveAvatarUrl(member.avatarUrl)"
                        :alt="member.displayName"
                        class="member-avatar"
                      />
                      <img
                        v-else
                        src="/image.png"
                        :alt="member.displayName"
                        class="member-avatar"
                      />
                    </div>
                    <div class="member-info">
                      <strong>{{ member.displayName }}</strong>
                      <span class="member-role">{{ member.role === 'owner' ? '创建者' : member.role === 'admin' ? '管理员' : '成员' }}</span>
                    </div>
                  </div>
                </div>
              </div>
            </template>

            <div class="conversation-mode-card">
              <div class="mode-switch-header">
                <span class="mode-switch-title">对话模式</span>
              </div>
              <div class="mode-switch-container" @click="switchMode(currentChatMode === 'dialogue' ? 'discussion' : 'dialogue')">
                <div class="mode-switch-track">
                  <div
                    class="mode-switch-thumb"
                    :class="currentChatMode === 'discussion' ? 'thumb-right' : 'thumb-left'"
                  ></div>
                  <span class="mode-label left" :class="{ active: currentChatMode === 'dialogue' }">对话模式</span>
                  <span class="mode-label right" :class="{ active: currentChatMode === 'discussion' }">讨论模式</span>
                </div>
              </div>
              <p class="mode-desc">{{ currentChatMode === 'dialogue' ? '多角色同时响应 (1~N)' : '多角色轮流讨论' }}</p>
            </div>
            </template>
          </aside>
        </div>
      </template>

      <!-- Discover View -->
      <template v-else>
        <!-- Header -->
        <header class="content-header">
          <h1 class="page-title">发现</h1>
          <div class="search-bar">
            <svg class="search-icon" width="18" height="18" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
            </svg>
            <input
              v-model="searchQuery"
              type="text"
              class="search-input"
              placeholder="搜索角色、讨论、场景..."
            />
          </div>
        </header>

        <!-- Featured Characters -->
        <section class="featured-section">
          <div class="section-header">
            <h2 class="section-title">推荐角色</h2>
            <a href="#" class="see-all" @click.prevent>查看全部</a>
          </div>
          <div v-if="featuredCharactersLoading" class="featured-loading">
            <div class="loading-spinner"></div>
          </div>
          <div v-else-if="featuredCharacters.length === 0" class="featured-empty">
            暂无推荐角色
          </div>
          <div v-else class="featured-scroll">
            <div
              v-for="char in featuredCharacters"
              :key="char.id"
              class="character-card"
            >
              <div class="character-avatar-wrap">
                <img :src="char.avatar" :alt="char.name" class="character-avatar" />
                <span v-if="char.online" class="online-indicator"></span>
              </div>
              <div class="character-info">
                <span class="character-name">{{ char.name }}</span>
                <span class="character-role">{{ char.role }}</span>
              </div>
            </div>
          </div>
        </section>

        <!-- Category Tabs -->
        <section class="category-tabs">
          <button
            v-for="cat in categories"
            :key="cat.id"
            class="category-chip"
            :class="{ active: selectedCategory === cat.id }"
            @click="selectedCategory = cat.id"
            :style="selectedCategory === cat.id && cat.color ? { backgroundColor: cat.color + '20', borderColor: cat.color, color: cat.color } : {}"
          >
            <span class="chip-emoji">{{ cat.emoji }}</span>
            <span class="chip-label">{{ cat.label }}</span>
          </button>
        </section>

        <!-- Hot Rooms -->
        <section class="rooms-section">
          <div class="section-header">
            <h2 class="section-title">
              <span class="hot-badge">🔥</span>
              热门聊天室
            </h2>
            <span class="room-count">{{ roomCardsData.length }} 个房间</span>
          </div>

          <!-- Room Grid -->
          <div class="room-grid">
            <div
              v-for="room in roomCardsData"
              :key="room.id"
              class="room-card"
              @click="enterRoom(room.id)"
            >
              <!-- Cover Image -->
              <div class="room-cover">
                <img :src="room.cover" :alt="room.title" class="cover-img" />
                <div v-if="room.isHot" class="hot-tag">🔥 热门</div>
                <div class="cover-overlay"></div>
              </div>

              <!-- Room Info -->
              <div class="room-body">
                <h3 class="room-title">{{ room.title }}</h3>

                <!-- Participants -->
                <div class="room-participants">
                  <div class="avatar-stack">
                    <img
                      v-for="(avatar, i) in room.participantAvatars"
                      :key="i"
                      :src="avatar"
                      :alt="room.participants[i]"
                      class="participant-avatar"
                      :style="{ zIndex: 3 - i }"
                    />
                  </div>
                  <span class="participant-names">{{ room.participants.slice(0, 3).join('、') }}</span>
                </div>

                <!-- Latest Message -->
                <div class="latest-message">
                  <span class="message-sender">{{ room.latestMessage.sender }}:</span>
                  <span class="message-text">{{ room.latestMessage.text }}</span>
                </div>

                <!-- Stats -->
                <div class="room-stats">
                  <span class="stat">
                    <svg width="14" height="14" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0z" />
                    </svg>
                    {{ room.onlineCount }} 在线
                  </span>
                  <span class="stat">
                    <svg width="14" height="14" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
                    </svg>
                    {{ room.messageCount }} 条消息
                  </span>
                </div>
              </div>
            </div>
          </div>
        </section>
      </template>
    </main>


    <!-- Create Room Modal -->
    <CreateRoomModal
      :show="showCreateModal"
      @close="showCreateModal = false"
      @created="handleRoomCreated"
    />

    <!-- Create Character Modal -->
    <CreateCharacterModal
      :show="showCreateCharacterModal"
      :context="createCharacterRoomId ? 'room' : 'character-library'"
      :room-id="createCharacterRoomId"
      @close="showCreateCharacterModal = false"
      @created="handleCharacterCreated"
      @added-to-room="handleAddedToRoom"
    />

    <!-- Edit Character Modal -->
    <CreateCharacterModal
      v-if="showEditCharacterModal"
      :show="showEditCharacterModal"
      mode="edit"
      :character="editingCharacter"
      @close="closeEditCharacterModal"
      @updated="handleCharacterUpdated"
    />

    <!-- Invite Member Modal -->
    <Teleport to="body">
      <Transition name="modal">
        <div v-if="showInviteModal" class="invite-modal-overlay" @click.self="showInviteModal = false">
          <div class="invite-modal">
            <header class="invite-modal-header">
              <h2>邀请成员</h2>
              <button class="modal-close-btn" @click="showInviteModal = false">
                <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
                </svg>
              </button>
            </header>
            <div class="invite-modal-body">
              <p class="invite-hint">输入用户名或邮箱邀请用户加入当前聊天室</p>
              <input
                v-model="inviteKeyword"
                type="text"
                placeholder="用户名或邮箱"
                class="invite-input"
                @keyup.enter="handleInviteMember"
              />
              <p v-if="inviteError" class="invite-error">{{ inviteError }}</p>
            </div>
            <footer class="invite-modal-footer">
              <button class="invite-cancel-btn" @click="showInviteModal = false">取消</button>
              <button class="invite-submit-btn" @click="handleInviteMember" :disabled="inviteLoading || !inviteKeyword.trim()">
                {{ inviteLoading ? '邀请中...' : '邀请' }}
              </button>
            </footer>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<style scoped>
/* ===== Page Layout ===== */
.page-layout {
  display: grid;
  grid-template-columns: var(--global-sidebar-width) 1fr;
  min-height: 100vh;
  background: var(--app-bg);
  opacity: 0;
  overflow: visible;
  transition: opacity 0.4s ease, background-color 0.25s ease, grid-template-columns 0.22s ease;
}

.page-layout.mounted {
  opacity: 1;
}

/* ===== Left Sidebar ===== */
.sidebar {
  background: var(--sidebar-bg);
  border-right: 1px solid var(--border-color);
  display: flex;
  flex-direction: column;
  padding: 1rem;
  position: sticky;
  top: 0;
  height: 100vh;
  overflow: visible;
  transition: background-color 0.25s ease, border-color 0.25s ease;
  width: var(--global-sidebar-width);
  min-width: var(--global-sidebar-width);
}

.sidebar-collapse-btn {
  position: absolute;
  top: 1rem;
  right: -14px;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  border: 1px solid var(--border-color);
  background: var(--card-bg);
  color: var(--text-secondary);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 10;
  transition: all 0.2s ease;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}

.sidebar-collapse-btn:hover {
  background: var(--bg-primary);
  color: var(--text-primary);
  transform: scale(1.1);
}

.sidebar .logo-text,
.sidebar .nav-label,
.sidebar .recent-chat-text,
.sidebar .user-info-text {
  transition: opacity 0.2s ease;
}

.page-layout.global-collapsed .sidebar .logo-text,
.page-layout.global-collapsed .sidebar .nav-label,
.page-layout.global-collapsed .sidebar .recent-chat-text,
.page-layout.global-collapsed .sidebar .user-info-text {
  opacity: 0;
  width: 0;
  overflow: hidden;
  white-space: nowrap;
}

.page-layout.global-collapsed .sidebar {
  padding: 0.75rem 0.5rem;
}

.page-layout.global-collapsed .sidebar-brand {
  justify-content: center;
  padding: 0.25rem;
}

.page-layout.global-collapsed .create-dropdown-wrapper {
  width: 100%;
}

.page-layout.global-collapsed .create-btn {
  width: 48px;
  height: 48px;
  padding: 0;
  justify-content: center;
  margin: 0 auto 0.75rem;
}

.page-layout.global-collapsed .create-btn span {
  display: none;
}

.page-layout.global-collapsed .nav-item {
  justify-content: center;
  padding: 0.6rem;
}

.page-layout.global-collapsed .nav-emoji {
  margin: 0;
}

.page-layout.global-collapsed .recent-chats {
  display: none;
}

.sidebar-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0.25rem;
  margin-bottom: 0.875rem;
}

.sidebar-brand-logo {
  width: 32px;
  height: 32px;
  object-fit: contain;
  border-radius: 8px;
  flex-shrink: 0;
}

.logo-text {
  font-size: 22px;
  font-weight: 800;
  font-family: Inter, SF Pro Display, PingFang SC, sans-serif;
  line-height: 1;
  color: var(--text-primary);
  letter-spacing: -0.5px;
  transition: color 0.25s ease;
}

/* Create Button - Compact */
.create-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.4rem;
  width: 160px;
  height: 42px;
  background: var(--button-bg);
  border: none;
  border-radius: 14px;
  color: var(--button-text);
  font-size: 15px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s ease;
  margin-bottom: 1rem;
}

.create-btn:hover {
  opacity: 0.85;
}

.create-btn svg {
  opacity: 0.9;
}

/* Create Dropdown Wrapper */
.create-dropdown-wrapper {
  position: relative;
  display: inline-block;
  width: fit-content;
  margin-bottom: 1rem;
}

.create-dropdown-menu {
  position: absolute;
  left: calc(100% + 6px);
  top: 0;
  width: 150px;
  background: #1f1f1f;
  border-radius: 16px;
  padding: 6px;
  box-shadow: 0 8px 20px rgba(0, 0, 0, 0.16);
  z-index: 9999;
}

.dropdown-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  height: 42px;
  padding: 0 12px;
  background: transparent;
  border: none;
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.15s ease;
  text-align: left;
}

.dropdown-item:hover {
  background: rgba(255, 255, 255, 0.06);
}

.dropdown-icon {
  font-size: 16px;
  width: 20px;
  text-align: center;
}

.dropdown-label {
  font-size: 0.85rem;
  font-weight: 500;
  color: #fafafa;
}

/* Dropdown animation */
.dropdown-enter-active,
.dropdown-leave-active {
  transition: all 0.15s ease;
}

.dropdown-enter-from,
.dropdown-leave-to {
  opacity: 0;
  transform: translateX(-4px);
}

.dropdown-enter-to,
.dropdown-leave {
  opacity: 1;
  transform: translateX(0);
}

/* Navigation - Minimal & Light */
.nav-menu {
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
  margin-bottom: 1rem;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  padding: 0.5rem 0.65rem;
  border-radius: 8px;
  color: var(--text-secondary);
  text-decoration: none;
  font-size: 0.875rem;
  font-weight: 400;
  transition: all 0.15s ease;
}

.nav-item:hover {
  background: var(--bg-primary);
  color: var(--text-primary);
}

.nav-item.active {
  background: var(--bg-primary);
  color: var(--text-primary);
  font-weight: 500;
}

.nav-emoji {
  font-size: 1rem;
  width: 20px;
  text-align: center;
  flex-shrink: 0;
}

/* Recent Chats - Minimal List */
.recent-chats {
  flex: 1;
  overflow-y: auto;
  margin-bottom: 0.75rem;
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 0.25rem;
  margin-bottom: 0.5rem;
}

.section-title {
  font-size: 0.7rem;
  font-weight: 500;
  letter-spacing: 0.04em;
  color: var(--text-muted);
  text-transform: uppercase;
  transition: color 0.25s ease;
}

.chat-list {
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
}

.chat-item {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  padding: 0.5rem 0.5rem;
  border-radius: 8px;
  text-decoration: none;
  transition: all 0.15s ease;
}

.chat-item:hover {
  background: var(--bg-primary);
}

.chat-avatar {
  width: 28px;
  height: 28px;
  border-radius: 6px;
  background: var(--bg-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-secondary);
  flex-shrink: 0;
  overflow: hidden;
}

.chat-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.chat-avatar-placeholder {
  font-size: 0.75rem;
  font-weight: 600;
  color: var(--text-muted);
}

.chat-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 0.05rem;
}

.chat-name {
  font-size: 0.8rem;
  font-weight: 500;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  transition: color 0.25s ease;
}

.chat-preview {
  font-size: 0.7rem;
  color: var(--text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  transition: color 0.25s ease;
}

/* ===== Main Content ===== */
.main-content {
  padding: 1.5rem 2rem;
  overflow-y: auto;
}

.main-content:has(.rooms-chat-shell) {
  padding-bottom: 0;
}

/* Header */
.content-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 2rem;
}

.page-title {
  font-size: 1.5rem;
  font-weight: 700;
  color: var(--text-primary);
  transition: color 0.25s ease;
}

.search-bar {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.65rem 1rem;
  background: var(--input-bg);
  border: 1px solid var(--border-color);
  border-radius: 999px;
  width: 320px;
  transition: all 0.25s ease;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}

.search-bar:focus-within {
  border-color: #27272a;
  box-shadow: 0 0 0 3px rgba(24, 24, 27, 0.1);
}

.search-icon {
  color: var(--text-muted);
  flex-shrink: 0;
}

.search-input {
  flex: 1;
  border: none;
  background: transparent;
  font-size: 0.9rem;
  color: var(--text-primary);
  outline: none;
}

.search-input::placeholder {
  color: var(--text-muted);
}

/* Featured Section */
.featured-section {
  margin-bottom: 2rem;
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 1rem;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 1.1rem;
  font-weight: 600;
  color: var(--text-primary);
  transition: color 0.25s ease;
}

.see-all {
  font-size: 0.85rem;
  font-weight: 500;
  color: #18181b;
  text-decoration: none;
}

.see-all:hover {
  text-decoration: underline;
}

/* Featured Scroll */
.featured-scroll {
  display: flex;
  gap: 1rem;
  overflow-x: auto;
  padding: 0.5rem 0;
  scrollbar-width: none;
  -ms-overflow-style: none;
}

.featured-scroll::-webkit-scrollbar {
  display: none;
}

.featured-loading {
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 2rem;
}

.featured-empty {
  text-align: center;
  padding: 2rem;
  color: var(--text-muted);
}

.character-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.6rem;
  padding: 1rem;
  background: var(--card-bg);
  border-radius: 16px;
  border: 1px solid var(--border-color);
  min-width: 100px;
  cursor: pointer;
  transition: all 0.25s ease;
}

.character-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.1);
  border-color: #27272a;
}

.character-avatar-wrap {
  position: relative;
}

.character-avatar {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  object-fit: cover;
  border: 2px solid var(--border-color);
  transition: border-color 0.25s ease;
}

.online-indicator {
  position: absolute;
  bottom: 2px;
  right: 2px;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: #10B981;
  border: 2px solid white;
}

.character-info {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.15rem;
  text-align: center;
}

.character-name {
  font-size: 0.85rem;
  font-weight: 600;
  color: var(--text-primary);
  transition: color 0.25s ease;
}

.character-role {
  font-size: 0.7rem;
  color: var(--text-muted);
  transition: color 0.25s ease;
}

/* Category Tabs */
.category-tabs {
  display: flex;
  gap: 0.5rem;
  margin-bottom: 1.5rem;
  overflow-x: auto;
  padding: 0.25rem 0;
  scrollbar-width: none;
}

.category-tabs::-webkit-scrollbar {
  display: none;
}

.category-chip {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.5rem 0.9rem;
  background: var(--card-bg);
  border: 1px solid var(--border-color);
  border-radius: 999px;
  font-size: 0.85rem;
  font-weight: 500;
  color: var(--text-secondary);
  cursor: pointer;
  white-space: nowrap;
  transition: all 0.2s ease;
}

.category-chip:hover {
  background: var(--bg-primary);
  color: var(--text-primary);
}

.category-chip.active {
  background: #EEF2FF;
  border-color: #27272a;
  color: #18181b;
}

.chip-emoji {
  font-size: 0.95rem;
}

/* Rooms Section */
.rooms-section {
  margin-bottom: 2rem;
}

.hot-badge {
  font-size: 1.2rem;
}

.room-count {
  font-size: 0.85rem;
  color: var(--text-muted);
  transition: color 0.25s ease;
}

/* Room Grid */
.room-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1.25rem;
}

@media (max-width: 1400px) {
  .room-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 900px) {
  .room-grid {
    grid-template-columns: 1fr;
  }
}

/* Room Card */
.room-card {
  background: var(--card-bg);
  border-radius: 16px;
  border: 1px solid var(--border-color);
  overflow: hidden;
  cursor: pointer;
  transition: all 0.3s ease;
}

.room-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.12);
  border-color: #27272a;
}

.room-cover {
  position: relative;
  aspect-ratio: 16 / 9;
  overflow: hidden;
}

.cover-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 0.3s ease;
}

.room-card:hover .cover-img {
  transform: scale(1.05);
}

.cover-overlay {
  position: absolute;
  inset: 0;
  background: linear-gradient(to bottom, transparent 50%, rgba(0,0,0,0.5) 100%);
}

.hot-tag {
  position: absolute;
  top: 0.75rem;
  right: 0.75rem;
  padding: 0.3rem 0.6rem;
  background: rgba(255, 100, 50, 0.9);
  border-radius: 6px;
  font-size: 0.7rem;
  font-weight: 600;
  color: white;
}

.room-body {
  padding: 1rem;
}

.room-title {
  font-size: 1rem;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 0.75rem;
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  transition: color 0.25s ease;
}

.room-participants {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  margin-bottom: 0.75rem;
}

.avatar-stack {
  display: flex;
}

.participant-avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  border: 2px solid white;
  margin-left: -8px;
  object-fit: cover;
}

.participant-avatar:first-child {
  margin-left: 0;
}

.participant-names {
  font-size: 0.8rem;
  color: var(--text-secondary);
  transition: color 0.25s ease;
}

.latest-message {
  display: flex;
  gap: 0.4rem;
  padding: 0.6rem 0.75rem;
  background: var(--panel-bg);
  border-radius: 8px;
  margin-bottom: 0.75rem;
  font-size: 0.8rem;
  transition: background-color 0.25s ease;
}

.message-sender {
  font-weight: 600;
  color: #18181b;
}

.message-text {
  color: var(--text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.room-stats {
  display: flex;
  gap: 1rem;
}

.stat {
  display: flex;
  align-items: center;
  gap: 0.35rem;
  font-size: 0.8rem;
  color: var(--text-muted);
  transition: color 0.25s ease;
}

/* ===== Responsive ===== */
@media (max-width: 768px) {
  .page-layout {
    grid-template-columns: 1fr;
  }

  .sidebar {
    display: none;
  }

  .main-content {
    padding: 1rem;
  }

  .search-bar {
    width: 100%;
  }

  .content-header {
    flex-direction: column;
    gap: 1rem;
    align-items: stretch;
  }
}

/* ===== Character Library Styles ===== */
.create-btn-large {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.75rem 1.25rem;
  background: linear-gradient(135deg, #18181b 0%, #3f3f46 100%);
  border: none;
  border-radius: 10px;
  color: white;
  font-size: 0.9rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
}

.create-btn-large:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(24, 24, 27, 0.3);
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 4rem 2rem;
  text-align: center;
}

.empty-icon {
  font-size: 4rem;
  margin-bottom: 1.5rem;
}

.empty-title {
  font-size: 1.5rem;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 0.5rem;
}

.empty-desc {
  font-size: 1rem;
  color: var(--text-muted);
  margin-bottom: 2rem;
}

.empty-btn {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.875rem 1.5rem;
  background: linear-gradient(135deg, #18181b 0%, #3f3f46 100%);
  border: none;
  border-radius: 12px;
  color: white;
  font-size: 1rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
}

.empty-btn:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 16px rgba(24, 24, 27, 0.35);
}

.character-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 1.25rem;
}

.character-card-item {
  display: flex;
  align-items: flex-start;
  gap: 1rem;
  padding: 1.25rem;
  background: var(--card-bg);
  border: 1px solid var(--border-color);
  border-radius: 16px;
  transition: all 0.25s ease;
}

.character-card-item:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.08);
  border-color: #27272a;
}

.character-card-item .character-avatar {
  width: 56px;
  height: 56px;
  border-radius: 12px;
  overflow: hidden;
  flex-shrink: 0;
  background: var(--bg-primary);
  display: flex;
  align-items: center;
  justify-content: center;
}

.character-card-item .character-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.character-card-item .avatar-placeholder {
  font-size: 1.5rem;
  font-weight: 700;
  color: var(--text-muted);
}

.character-card-item .character-info {
  flex: 1;
  min-width: 0;
}

.character-card-item .character-name {
  font-size: 1.1rem;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 0.25rem;
}

.character-card-item .character-tagline {
  font-size: 0.875rem;
  color: var(--text-secondary);
  margin-bottom: 0.5rem;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.character-card-item .character-date {
  font-size: 0.75rem;
  color: var(--text-muted);
}

.character-card-item .edit-btn {
  display: flex;
  align-items: center;
  gap: 0.35rem;
  padding: 0.5rem 0.75rem;
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  color: var(--text-secondary);
  font-size: 0.8rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s ease;
  flex-shrink: 0;
}

.character-card-item .edit-btn:hover {
  background: var(--border-color);
  color: var(--text-primary);
}

.character-card-item .card-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 0.5rem;
  flex-shrink: 0;
}

.character-card-item .chat-btn {
  display: flex;
  align-items: center;
  gap: 0.35rem;
  padding: 0.5rem 0.75rem;
  background: var(--button-bg);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  color: var(--button-text);
  font-size: 0.8rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s ease;
}

.character-card-item .chat-btn:hover {
  opacity: 0.85;
}

/* ===== My Rooms Styles ===== */
.page-subtitle {
  font-size: 0.9rem;
  color: var(--text-muted);
  margin-top: 0.25rem;
}

.loading-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 4rem 2rem;
  gap: 1rem;
  color: var(--text-muted);
}

.loading-spinner {
  width: 32px;
  height: 32px;
  border: 3px solid var(--border-color);
  border-top-color: var(--text-primary);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.my-room-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.my-room-item {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 18px 20px;
  background: var(--card-bg);
  border: 1px solid var(--border-color);
  border-radius: 18px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.my-room-item:hover {
  transform: translateY(-2px);
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.1);
  border-color: #27272a;
}

.my-room-item:hover .room-enter-hint {
  opacity: 1;
  transform: translateX(0);
}

.room-avatar-group {
  display: flex;
  align-items: center;
}

.room-char-avatar {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  overflow: hidden;
  background: var(--bg-primary);
  border: 2px solid var(--card-bg);
  margin-left: -10px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.room-char-avatar:first-child {
  margin-left: 0;
}

.room-char-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.room-char-avatar .avatar-placeholder {
  font-size: 1rem;
  font-weight: 600;
  color: var(--text-muted);
}

.room-char-avatar.empty {
  background: linear-gradient(135deg, #18181b 0%, #3f3f46 100%);
}

.room-char-avatar.empty span {
  font-size: 1.2rem;
}

.room-main {
  flex: 1;
  min-width: 0;
}

.room-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 4px;
}

.room-name {
  font-size: 1rem;
  font-weight: 600;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.room-time {
  font-size: 0.75rem;
  color: var(--text-muted);
  white-space: nowrap;
}

.room-topic {
  font-size: 0.85rem;
  color: var(--text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin-bottom: 4px;
}

.room-meta {
  font-size: 0.75rem;
  color: var(--text-muted);
}

.room-enter-hint {
  opacity: 0;
  transform: translateX(-8px);
  transition: all 0.2s ease;
  color: var(--text-muted);
  flex-shrink: 0;
}

/* Dark mode for my rooms */
.dark .my-room-item:hover {
  border-color: rgba(71, 85, 105, 0.85);
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.3);
}

/* ===== My Rooms Three-Column Layout ===== */
.rooms-chat-shell {
  height: calc(100% + 1.5rem);
  display: grid;
  grid-template-columns: var(--room-list-width) minmax(0, 1fr) var(--role-panel-width);
  background: #f6f7fb;
  margin: -1.5rem -2rem 0;
  overflow: hidden;
  transition: grid-template-columns 0.22s ease;
}

.dark .rooms-chat-shell {
  background: #020617;
}

/* Left: Room List Panel */
.rooms-list-panel {
  min-width: 0;
  background: #ffffff;
  border-right: 1px solid rgba(226, 232, 240, 0.9);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  position: relative;
}

.dark .rooms-list-panel {
  background: #0f172a;
  border-right-color: rgba(71, 85, 105, 0.85);
}

.page-layout.room-list-collapsed .rooms-list-panel {
  border-right: none;
}

.room-list-toggle-btn {
  position: absolute;
  top: 50%;
  right: -14px;
  transform: translateY(-50%);
  width: 28px;
  height: 28px;
  border-radius: 50%;
  border: 1px solid #e2e8f0;
  background: #ffffff;
  color: #0f172a;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 10;
  transition: all 0.2s ease;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}

.room-list-toggle-btn:hover {
  background: #f1f5f9;
  transform: translateY(-50%) scale(1.1);
}

.dark .room-list-toggle-btn {
  background: #1e293b;
  color: #e2e8f0;
  border-color: rgba(71, 85, 105, 0.85);
}

.icon-close-room-list-button {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  border: none;
  background: transparent;
  color: #94a3b8;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.15s ease;
}

.icon-close-room-list-button:hover {
  background: #f1f5f9;
  color: #0f172a;
}

.dark .icon-close-room-list-button:hover {
  background: #1e293b;
  color: #f1f5f9;
}

.rooms-list-header {
  height: 88px;
  padding: 20px 18px 14px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid rgba(226, 232, 240, 0.9);
}

.dark .rooms-list-header {
  border-bottom-color: rgba(71, 85, 105, 0.85);
}

.rooms-list-header h2 {
  margin: 0;
  font-size: 22px;
  font-weight: 800;
  color: #0f172a;
}

.dark .rooms-list-header h2 {
  color: #f1f5f9;
}

.rooms-list-header p {
  margin: 6px 0 0;
  font-size: 13px;
  color: #94a3b8;
}

.icon-create-room-button {
  width: 42px;
  height: 42px;
  border-radius: 14px;
  border: none;
  background: #0f172a;
  color: #ffffff;
  font-size: 24px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}

.icon-create-room-button:hover {
  background: #1e293b;
  transform: translateY(-1px);
}

.dark .icon-create-room-button {
  background: #f8fafc;
  color: #0f172a;
}

.dark .icon-create-room-button:hover {
  background: #e2e8f0;
}

.rooms-search {
  padding: 0 14px 12px;
}

.rooms-search input {
  width: 100%;
  height: 42px;
  border-radius: 14px;
  border: 1px solid #e2e8f0;
  background: #f8fafc;
  padding: 0 14px;
  outline: none;
  font-size: 14px;
  color: #0f172a;
}

.dark .rooms-search input {
  background: #1e293b;
  border-color: rgba(71, 85, 105, 0.85);
  color: #f1f5f9;
}

.rooms-search input::placeholder {
  color: #94a3b8;
}

.rooms-search input:focus {
  border-color: #d6a84f;
}

.rooms-loading {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 1rem;
  color: #94a3b8;
}

.rooms-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 2rem;
  text-align: center;
}

.rooms-empty .empty-icon {
  font-size: 3rem;
  margin-bottom: 1rem;
}

.rooms-empty h3 {
  margin: 0;
  font-size: 1.1rem;
  font-weight: 600;
  color: #0f172a;
}

.dark .rooms-empty h3 {
  color: #f1f5f9;
}

.rooms-empty p {
  margin: 8px 0 1.5rem;
  font-size: 14px;
  color: #94a3b8;
}

.rooms-list-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 8px 10px 16px;
}

.room-list-item {
  width: 100%;
  display: flex;
  gap: 12px;
  padding: 14px 12px;
  border: none;
  border-radius: 16px;
  background: transparent;
  cursor: pointer;
  text-align: left;
  transition: background 0.15s ease;
}

.room-list-item:hover {
  background: #f1f5f9;
}

.dark .room-list-item:hover {
  background: #1e293b;
}

.room-list-item.active {
  background: #0f172a;
  color: #ffffff;
}

.dark .room-list-item.active {
  background: #f8fafc;
  color: #0f172a;
}

.dark .room-list-item.active p,
.dark .room-list-item.active small,
.dark .room-list-item.active span {
  color: inherit;
  opacity: 0.7;
}

.room-list-icon {
  width: 42px;
  height: 42px;
  flex: 0 0 42px;
  border-radius: 14px;
  background: #f1f5f9;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
}

.dark .room-list-icon {
  background: #1e293b;
}

.room-list-item.active .room-list-icon {
  background: rgba(255, 255, 255, 0.14);
}

.dark .room-list-item.active .room-list-icon {
  background: #0f172a;
}

.room-list-content {
  min-width: 0;
  flex: 1;
}

.room-list-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.room-list-title-row strong {
  font-size: 14px;
  font-weight: 800;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: inherit;
}

.room-list-title-row span {
  font-size: 12px;
  color: #94a3b8;
  flex-shrink: 0;
}

.room-list-content p {
  display: block;
  margin-top: 4px;
  font-size: 13px;
  color: #94a3b8;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.room-list-content small {
  display: block;
  margin-top: 2px;
  font-size: 12px;
  color: #94a3b8;
}

/* Center: Chat Main Panel */
.chat-main-panel {
  min-width: 0;
  min-height: 0;
  height: 100%;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  background: radial-gradient(circle at top, rgba(214, 168, 79, 0.08), transparent 280px), #f8fafc;
  position: relative;
  z-index: 1;
}

.dark .chat-main-panel {
  background: radial-gradient(circle at top, rgba(214, 168, 79, 0.05), transparent 280px), #020617;
}

.chat-empty-state {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  color: #64748b;
}

.chat-empty-icon {
  width: 72px;
  height: 72px;
  border-radius: 24px;
  border: 1px solid rgba(214, 168, 79, 0.45);
  color: #d6a84f;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 32px;
  margin-bottom: 18px;
}

.chat-empty-state h2 {
  margin: 0;
  font-size: 24px;
  font-weight: 700;
  color: #0f172a;
}

.dark .chat-empty-state h2 {
  color: #f1f5f9;
}

.chat-empty-state p {
  margin-top: 10px;
  font-size: 15px;
  color: #64748b;
  max-width: 320px;
}

/* Right: Characters Panel */
.room-characters-panel {
  min-width: 0;
  background: #ffffff;
  border-left: 1px solid rgba(226, 232, 240, 0.9);
  display: flex;
  flex-direction: column;
  transition: transform 0.3s ease;
  overflow: hidden;
  position: relative;
  z-index: 10;
}

.dark .room-characters-panel {
  background: #0f172a;
  border-left-color: rgba(71, 85, 105, 0.85);
}

.page-layout.role-panel-collapsed .room-characters-panel {
  border-left: none;
}

.panel-tabs-wrapper {
  display: flex;
  align-items: center;
  padding: 8px 8px 0;
  gap: 8px;
}

/* Panel Tabs */
.panel-tabs {
  display: flex;
  flex: 1;
  min-width: 0;
  border-bottom: 1px solid rgba(226, 232, 240, 0.9);
}

.dark .panel-tabs {
  border-bottom-color: rgba(71, 85, 105, 0.85);
}

.panel-tab {
  flex: 1;
  padding: 14px;
  font-size: 13px;
  font-weight: 500;
  color: #94a3b8;
  background: transparent;
  border: none;
  border-bottom: 2px solid transparent;
  cursor: pointer;
  transition: all 0.15s;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}

.panel-tab:hover {
  color: #0f172a;
}

.panel-tab.active {
  color: #0f172a;
  border-bottom-color: #0f172a;
}

.dark .panel-tab:hover,
.dark .panel-tab.active {
  color: #f1f5f9;
}

.dark .panel-tab.active {
  border-bottom-color: #f1f5f9;
}

.tab-badge {
  background: #e2e8f0;
  color: #64748b;
  font-size: 11px;
  padding: 2px 6px;
  border-radius: 10px;
}

.dark .tab-badge {
  background: #1e293b;
  color: #94a3b8;
}

/* Members Panel */
.members-panel-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.members-list {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
}

.members-loading,
.members-empty {
  text-align: center;
  padding: 24px;
  color: #94a3b8;
  font-size: 14px;
}

.member-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px;
  border-radius: 12px;
  margin-bottom: 8px;
}

.member-item:hover {
  background: #f8fafc;
}

.dark .member-item:hover {
  background: #1e293b;
}

.member-avatar-wrapper {
  position: relative;
  width: 40px;
  height: 40px;
}

.member-avatar-wrapper .member-avatar {
  position: absolute;
  top: 0;
  left: 0;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  object-fit: cover;
}

.member-avatar-wrapper .member-avatar-placeholder {
  position: absolute;
  top: 0;
  left: 0;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: #e2e8f0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 600;
  color: #64748b;
}

.dark .member-avatar-wrapper .member-avatar-placeholder {
  background: #1e293b;
  color: #94a3b8;
}

.member-avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  object-fit: cover;
}

.member-avatar-placeholder {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: #e2e8f0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 600;
  color: #64748b;
}

.dark .member-avatar-placeholder {
  background: #1e293b;
  color: #94a3b8;
}

.member-info {
  flex: 1;
}

.member-info strong {
  display: block;
  font-size: 14px;
  font-weight: 600;
  color: #0f172a;
}

.dark .member-info strong {
  color: #f1f5f9;
}

.member-role {
  font-size: 12px;
  color: #94a3b8;
}

.members-footer {
  padding: 12px;
  border-top: 1px solid rgba(226, 232, 240, 0.9);
}

.dark .members-footer {
  border-top-color: rgba(71, 85, 105, 0.85);
}

.invite-member-btn {
  width: 100%;
  padding: 12px;
  border-radius: 12px;
  border: 1px dashed #cbd5e1;
  background: transparent;
  color: #64748b;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  transition: all 0.15s;
}

.invite-member-btn:hover {
  border-color: #0f172a;
  color: #0f172a;
  background: #f8fafc;
}

.dark .invite-member-btn:hover {
  border-color: #f1f5f9;
  color: #f1f5f9;
  background: #1e293b;
}

/* Invite Modal */
.invite-modal-overlay {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgba(15, 23, 42, 0.06);
  backdrop-filter: blur(4px);
}

.dark .invite-modal-overlay {
  background: transparent;
  backdrop-filter: none;
}

.invite-modal {
  width: 420px;
  max-width: 100%;
  background: #ffffff;
  border-radius: 20px;
  box-shadow: 0 28px 60px rgba(15, 23, 42, 0.2);
  overflow: hidden;
}

.dark .invite-modal {
  background: #0f172a;
}

.invite-modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 24px;
  border-bottom: 1px solid rgba(226, 232, 240, 0.9);
}

.dark .invite-modal-header {
  border-bottom-color: rgba(71, 85, 105, 0.85);
}

.invite-modal-header h2 {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: #0f172a;
}

.dark .invite-modal-header h2 {
  color: #f1f5f9;
}

.modal-close-btn {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  border: none;
  background: transparent;
  color: #94a3b8;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}

.modal-close-btn:hover {
  background: #f1f5f9;
  color: #0f172a;
}

.dark .modal-close-btn:hover {
  background: #1e293b;
  color: #f1f5f9;
}

.invite-modal-body {
  padding: 24px;
}

.invite-hint {
  margin: 0 0 16px;
  font-size: 14px;
  color: #64748b;
}

.invite-input {
  width: 100%;
  padding: 12px 16px;
  border-radius: 12px;
  border: 1px solid #e2e8f0;
  background: #f8fafc;
  font-size: 14px;
  color: #0f172a;
  outline: none;
  transition: all 0.15s;
}

.invite-input:focus {
  border-color: #0f172a;
  box-shadow: 0 0 0 3px rgba(15, 23, 42, 0.08);
}

.dark .invite-input {
  background: #1e293b;
  border-color: rgba(71, 85, 105, 0.85);
  color: #f1f5f9;
}

.dark .invite-input:focus {
  border-color: #94a3b8;
  box-shadow: 0 0 0 3px rgba(148, 163, 184, 0.16);
}

.invite-error {
  margin: 12px 0 0;
  font-size: 13px;
  color: #dc2626;
}

.invite-modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding: 16px 24px;
  border-top: 1px solid rgba(226, 232, 240, 0.9);
}

.dark .invite-modal-footer {
  border-top-color: rgba(71, 85, 105, 0.85);
}

.invite-cancel-btn {
  padding: 10px 20px;
  border-radius: 12px;
  border: 1px solid #e2e8f0;
  background: transparent;
  color: #64748b;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
}

.invite-cancel-btn:hover {
  background: #f8fafc;
}

.dark .invite-cancel-btn {
  border-color: rgba(71, 85, 105, 0.85);
  color: #94a3b8;
}

.dark .invite-cancel-btn:hover {
  background: #1e293b;
}

.invite-submit-btn {
  padding: 10px 20px;
  border-radius: 12px;
  border: none;
  background: #0f172a;
  color: #ffffff;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
}

.invite-submit-btn:hover:not(:disabled) {
  background: #1e293b;
}

.invite-submit-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.dark .invite-submit-btn {
  background: #f8fafc;
  color: #0f172a;
}

.dark .invite-submit-btn:hover:not(:disabled) {
  background: #e2e8f0;
}

.characters-panel-header {
  height: 76px;
  padding: 18px;
  border-bottom: 1px solid rgba(226, 232, 240, 0.9);
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.dark .characters-panel-header {
  border-bottom-color: rgba(71, 85, 105, 0.85);
}

.characters-panel-header h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 800;
  color: #0f172a;
}

.dark .characters-panel-header h3 {
  color: #f1f5f9;
}

.characters-panel-header p {
  margin: 4px 0 0;
  font-size: 12px;
  color: #94a3b8;
}

.members-panel-header {
  height: 76px;
  padding: 18px;
  border-bottom: 1px solid rgba(226, 232, 240, 0.9);
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.dark .members-panel-header {
  border-bottom-color: rgba(71, 85, 105, 0.85);
}

.members-panel-header h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 800;
  color: #0f172a;
}

.dark .members-panel-header h3 {
  color: #f1f5f9;
}

.members-panel-header p {
  margin: 4px 0 0;
  font-size: 12px;
  color: #94a3b8;
}

.add-char-btn {
  width: 100px;
  height: 36px;
  border-radius: 12px;
  border: none;
  background: #0f172a;
  color: #ffffff;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
}

.add-char-btn:hover {
  background: #1e293b;
}

.dark .add-char-btn {
  background: #f8fafc;
  color: #0f172a;
}

.dark .add-char-btn:hover {
  background: #e2e8f0;
}

.icon-close-role-panel-button {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  border: none;
  background: transparent;
  color: #94a3b8;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.15s ease;
}

.icon-close-role-panel-button:hover {
  background: #f1f5f9;
  color: #0f172a;
}

.dark .icon-close-role-panel-button:hover {
  background: #1e293b;
  color: #f1f5f9;
}

.characters-empty {
  margin: 18px;
  padding: 24px 16px;
  border-radius: 20px;
  border: 1px dashed #cbd5e1;
  text-align: center;
  color: #64748b;
}

.dark .characters-empty {
  border-color: rgba(71, 85, 105, 0.85);
}

.characters-empty h4 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: #0f172a;
}

.dark .characters-empty h4 {
  color: #f1f5f9;
}

.characters-empty p {
  margin: 8px 0 0;
  font-size: 13px;
}

.characters-empty button {
  margin-top: 14px;
  height: 38px;
  padding: 0 16px;
  border-radius: 12px;
  border: none;
  background: #0f172a;
  color: #ffffff;
  font-size: 14px;
  cursor: pointer;
}

.characters-empty button:hover {
  background: #1e293b;
}

.dark .characters-empty button {
  background: #f8fafc;
  color: #0f172a;
}

.dark .characters-empty button:hover {
  background: #e2e8f0;
}

.characters-list {
  flex: 1;
  overflow-y: auto;
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.character-chip-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border-radius: 16px;
  background: #f1f5f9;
  border: 1px solid #e2e8f0;
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
}

.dark .character-chip-card {
  background: #1e293b;
  border-color: rgba(71, 85, 105, 0.85);
}

.character-chip-card img {
  width: 42px;
  height: 42px;
  border-radius: 50%;
  object-fit: cover;
}

.char-avatar-placeholder {
  width: 42px;
  height: 42px;
  border-radius: 50%;
  background: #e2e8f0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  font-weight: 600;
  color: #64748b;
}

.dark .char-avatar-placeholder {
  background: #0f172a;
  color: #94a3b8;
}

.character-info-row {
  display: flex;
  align-items: center;
  flex: 1;
  gap: 12px;
  min-width: 0;
  overflow: hidden;
}

.character-info-row .character-info {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  max-width: calc(100% - 80px);
}

.edit-char-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 10px;
  font-size: 12px;
  font-weight: 500;
  border: 1px solid;
  border-radius: 8px;
  background: var(--button-bg);
  border-color: var(--button-bg);
  color: var(--button-text);
  cursor: pointer;
  transition: all 0.15s ease;
  flex-shrink: 0;
}

.edit-char-btn:hover {
  opacity: 0.85;
}

.character-chip-card strong {
  display: block;
  font-size: 14px;
  font-weight: 600;
  color: #0f172a;
}

.dark .character-chip-card strong {
  color: #f1f5f9;
}

.character-chip-card span {
  display: block;
  margin-top: 3px;
  font-size: 12px;
  color: #94a3b8;
  overflow: hidden;
  text-overflow: ellipsis;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  display: -webkit-box;
  word-break: break-word;
}

.conversation-mode-card {
  margin: 14px;
  padding: 14px;
  border-radius: 18px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
}

.dark .conversation-mode-card {
  background: #1e293b;
  border-color: rgba(71, 85, 105, 0.85);
}

.mode-switch-header {
  margin-bottom: 12px;
}

.mode-switch-title {
  font-size: 13px;
  font-weight: 600;
  color: #64748b;
}

.dark .mode-switch-title {
  color: #94a3b8;
}

.mode-switch-container {
  cursor: pointer;
  padding: 4px 0;
}

.mode-switch-track {
  position: relative;
  display: flex;
  align-items: center;
  background: #e2e8f0;
  border-radius: 20px;
  padding: 4px;
  height: 40px;
}

.dark .mode-switch-track {
  background: #0f172a;
}

.mode-switch-thumb {
  position: absolute;
  width: calc(50% - 4px);
  height: 32px;
  background: #0f172a;
  border-radius: 16px;
  transition: all 0.25s cubic-bezier(0.4, 0, 0.2, 1);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
}

.dark .mode-switch-thumb {
  background: #f8fafc;
}

.mode-switch-thumb.thumb-left {
  left: 4px;
}

.mode-switch-thumb.thumb-right {
  left: calc(50%);
}

.mode-label {
  flex: 1;
  text-align: center;
  font-size: 13px;
  font-weight: 500;
  color: #94a3b8;
  transition: color 0.25s ease;
  position: relative;
  z-index: 1;
  cursor: pointer;
}

.dark .mode-label {
  color: #64748b;
}

.mode-label.active {
  color: #ffffff;
}

.dark .mode-label.active {
  color: #0f172a;
}

.mode-desc {
  margin: 10px 0 0;
  font-size: 12px;
  color: #94a3b8;
  text-align: center;
}

.dark .mode-desc {
  color: #64748b;
}

/* Responsive */
@media (max-width: 1200px) {
  .rooms-chat-shell {
    grid-template-columns: var(--room-list-width, 300px) minmax(0, 1fr);
  }

  .room-characters-panel {
    position: fixed;
    right: 0;
    top: 0;
    bottom: 0;
    width: var(--role-panel-width, 280px);
    z-index: 1000;
    box-shadow: -4px 0 24px rgba(0, 0, 0, 0.15);
    transform: translateX(100%);
    transition: transform 0.3s ease;
  }

  .page-layout:not(.role-panel-collapsed) .room-characters-panel {
    transform: translateX(0);
  }

  .page-layout.role-panel-collapsed .room-characters-panel {
    transform: translateX(100%);
  }

  .page-layout.room-list-collapsed .rooms-list-panel {
    transform: translateX(-100%);
  }

  .room-list-toggle-btn {
    display: flex;
  }
}

@media (max-width: 768px) {
  .rooms-chat-shell {
    grid-template-columns: 1fr;
  }

  .rooms-list-panel {
    position: fixed;
    left: 0;
    top: 0;
    bottom: 0;
    width: var(--room-list-width, 300px);
    z-index: 1000;
    box-shadow: 4px 0 24px rgba(0, 0, 0, 0.15);
    transform: translateX(-100%);
    transition: transform 0.3s ease;
  }

  .page-layout:not(.room-list-collapsed) .rooms-list-panel {
    transform: translateX(0);
  }

  .page-layout.room-list-collapsed .rooms-list-panel {
    transform: translateX(-100%);
  }

  .chat-main-panel {
    height: 100%;
  }

  .room-list-toggle-btn {
    left: auto;
    right: -14px;
  }
}
</style>
