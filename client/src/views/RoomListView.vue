<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useRoomStore } from '@/stores/room'
import CreateRoomModal from '@/components/room/CreateRoomModal.vue'
import UserDropdown from '@/components/ui/UserDropdown.vue'

const router = useRouter()
const route = useRoute()
const roomStore = useRoomStore()

const showCreateModal = ref(false)
const showCreateDropdown = ref(false)
const dropdownRef = ref<HTMLElement | null>(null)
const selectedCategory = ref('all')
const searchQuery = ref('')
const mounted = ref(false)

// Navigation items - only 5 primary nav items
const navItems = [
  { id: 'discover', label: '发现', emoji: '🔍' },
  { id: 'characters', label: '角色库', emoji: '📚' },
  { id: 'trending', label: '热门', emoji: '🔥' },
  { id: 'categories', label: '分类', emoji: '📂' },
  { id: 'my-rooms', label: '我的聊天室', emoji: '💬' },
  { id: 'recent', label: '最近', emoji: '🕐' },
]

// Active nav item based on current route
const activeNavId = computed(() => {
  const path = route.path
  if (path === '/rooms' || path === '/') return 'discover'
  if (path.startsWith('/characters')) return 'characters'
  if (path.startsWith('/chat')) return 'discover'
  return 'discover'
})

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

// Featured characters with AI-style avatar URLs
const featuredCharacters = [
  { id: 1, name: '爱因斯坦', role: '物理学家', avatar: 'https://api.dicebear.com/7.x/personas/svg?seed=Einstein&backgroundColor=b6e3f4', category: 'scientist', online: true },
  { id: 2, name: '梅西', role: '足球巨星', avatar: 'https://api.dicebear.com/7.x/personas/svg?seed=Messi&backgroundColor=c0aede', category: 'athlete', online: true },
  { id: 3, name: '马斯克', role: '科技先锋', avatar: 'https://api.dicebear.com/7.x/personas/svg?seed=Musk&backgroundColor=d1d4f9', category: 'entrepreneur', online: true },
  { id: 4, name: '泰勒·斯威夫特', role: '音乐天后', avatar: 'https://api.dicebear.com/7.x/personas/svg?seed=Taylor&backgroundColor=ffd5dc', category: 'star', online: false },
  { id: 5, name: '宫崎骏', role: '动画大师', avatar: 'https://api.dicebear.com/7.x/personas/svg?seed=Miyazaki&backgroundColor=ffdfbf', category: 'anime', online: true },
  { id: 6, name: '莎士比亚', role: '文学巨匠', avatar: 'https://api.dicebear.com/7.x/personas/svg?seed=Shakespeare&backgroundColor=e0c3fc', category: 'writer', online: true },
  { id: 7, name: '苏格拉底', role: '哲学先驱', avatar: 'https://api.dicebear.com/7.x/personas/svg?seed=Socrates&backgroundColor=d1f4d1', category: 'philosopher', online: false },
  { id: 8, name: '牛顿', role: '科学巨匠', avatar: 'https://api.dicebear.com/7.x/personas/svg?seed=Newton&backgroundColor=c4b5fd', category: 'scientist', online: true },
]

// Live activities
const liveActivities = ref([
  { id: 1, character: '马斯克', avatar: 'https://api.dicebear.com/7.x/personas/svg?seed=Musk&backgroundColor=d1d4f9', action: '加入了', room: '「未来 AI 实验室」', time: '刚刚', color: '#FB923C' },
  { id: 2, character: '爱因斯坦', avatar: 'https://api.dicebear.com/7.x/personas/svg?seed=Einstein&backgroundColor=b6e3f4', action: '发言', room: '「相对论探讨」', time: '2分钟前', color: '#4F7DF3' },
  { id: 3, character: '莎士比亚', avatar: 'https://api.dicebear.com/7.x/personas/svg?seed=Shakespeare&backgroundColor=e0c3fc', action: '回复了', room: '「文学沙龙」', time: '5分钟前', color: '#34D399' },
  { id: 4, character: '梅西', avatar: 'https://api.dicebear.com/7.x/personas/svg?seed=Messi&backgroundColor=c0aede', action: '正在讨论', room: '「天赋与努力」', time: '8分钟前', color: '#10B981' },
  { id: 5, character: '宫崎骏', avatar: 'https://api.dicebear.com/7.x/personas/svg?seed=Miyazaki&backgroundColor=ffdfbf', action: '发布了新观点', room: '「创作的意义」', time: '12分钟前', color: '#EC4899' },
])

// Online users
const onlineUsers = ref([
  { name: '爱因斯坦', avatar: 'https://api.dicebear.com/7.x/personas/svg?seed=Einstein&backgroundColor=b6e3f4', status: '讨论中' },
  { name: '马斯克', avatar: 'https://api.dicebear.com/7.x/personas/svg?seed=Musk&backgroundColor=d1d4f9', status: '发言中' },
  { name: '莎士比亚', avatar: 'https://api.dicebear.com/7.x/personas/svg?seed=Shakespeare&backgroundColor=e0c3fc', status: '思考中' },
  { name: '宫崎骏', avatar: 'https://api.dicebear.com/7.x/personas/svg?seed=Miyazaki&backgroundColor=ffdfbf', status: '创作中' },
  { name: '梅西', avatar: 'https://api.dicebear.com/7.x/personas/svg?seed=Messi&backgroundColor=c0aede', status: '在线' },
])

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

// Recent chats
const recentChats = ref([
  { id: '1', name: '相对论探讨', lastMessage: '爱因斯坦: 时间是相对的...', time: '5分钟前', avatar: 'https://api.dicebear.com/7.x/personas/svg?seed=Einstein&backgroundColor=b6e3f4' },
  { id: '2', name: '文学沙龙', lastMessage: '莎士比亚: 生存还是毁灭...', time: '1小时前', avatar: 'https://api.dicebear.com/7.x/personas/svg?seed=Shakespeare&backgroundColor=e0c3fc' },
  { id: '3', name: '未来 AI 实验室', lastMessage: '马斯克: AI 将改变一切', time: '2小时前', avatar: 'https://api.dicebear.com/7.x/personas/svg?seed=Musk&backgroundColor=d1d4f9' },
])

onMounted(() => {
  roomStore.fetchRooms()
  setTimeout(() => { mounted.value = true }, 50)

  // Close dropdown when clicking outside
  document.addEventListener('click', handleClickOutside)
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
  router.push(`/chat/${roomId}`)
}

function enterRoom(roomId: string) {
  router.push(`/chat/${roomId}`)
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
  router.push('/characters/create')
}

function handleCreateRoom() {
  closeCreateDropdown()
  showCreateModal.value = true
}
</script>

<template>
  <div class="page-layout" :class="{ mounted }">
    <!-- Left Sidebar -->
    <aside class="sidebar">
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
        <div class="featured-scroll">
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
    </main>

    <!-- Right Sidebar -->
    <aside class="right-sidebar">
      <!-- Live Activity -->
      <div class="widget">
        <div class="widget-header">
          <span class="widget-title">
            <span class="live-dot"></span>
            实时动态
          </span>
          <span class="live-count">{{ liveActivities.length }} 个在线</span>
        </div>
        <div class="activity-feed">
          <div
            v-for="activity in liveActivities"
            :key="activity.id"
            class="activity-item"
          >
            <img :src="activity.avatar" :alt="activity.character" class="activity-avatar" />
            <div class="activity-content">
              <p class="activity-text">
                <span class="activity-name" :style="{ color: activity.color }">{{ activity.character }}</span>
                {{ activity.action }}
              </p>
              <p class="activity-room">{{ activity.room }}</p>
              <span class="activity-time">{{ activity.time }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Online Now -->
      <div class="widget">
        <div class="widget-header">
          <span class="widget-title">在线角色</span>
        </div>
        <div class="online-list">
          <div
            v-for="user in onlineUsers"
            :key="user.name"
            class="online-item"
          >
            <div class="online-avatar-wrap">
              <img :src="user.avatar" :alt="user.name" class="online-avatar" />
              <span class="online-status"></span>
            </div>
            <div class="online-info">
              <span class="online-name">{{ user.name }}</span>
              <span class="online-status-text">{{ user.status }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Quick Actions -->
      <div class="widget quick-actions">
        <button class="action-btn" @click="showCreateModal = true">
          <svg width="18" height="18" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
          </svg>
          创建聊天室
        </button>
        <button class="action-btn secondary" @click="router.push('/settings')">
          <svg width="18" height="18" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" />
            <circle cx="12" cy="12" r="3"/>
          </svg>
          设置
        </button>
      </div>
    </aside>

    <!-- Create Room Modal -->
    <CreateRoomModal
      :show="showCreateModal"
      @close="showCreateModal = false"
      @created="handleRoomCreated"
    />
  </div>
</template>

<style scoped>
/* ===== Page Layout ===== */
.page-layout {
  display: grid;
  grid-template-columns: 260px 1fr 300px;
  min-height: 100vh;
  background: var(--app-bg);
  opacity: 0;
  overflow: visible;
  transition: opacity 0.4s ease, background-color 0.25s ease;
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
  border-color: #4F7DF3;
  box-shadow: 0 0 0 3px rgba(79, 125, 243, 0.1);
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
  color: #4F7DF3;
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
  border-color: #4F7DF3;
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
  border-color: #4F7DF3;
  color: #4F7DF3;
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
  border-color: #4F7DF3;
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
  color: #4F7DF3;
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

/* ===== Right Sidebar ===== */
.right-sidebar {
  background: var(--card-bg);
  border-left: 1px solid var(--border-color);
  padding: 1.25rem;
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
  position: sticky;
  top: 0;
  height: 100vh;
  overflow-y: auto;
  transition: background-color 0.25s ease, border-color 0.25s ease;
}

/* Widget */
.widget {
  background: var(--panel-bg);
  border-radius: 14px;
  padding: 1rem;
  transition: background-color 0.25s ease;
}

.widget-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 1rem;
}

.widget-title {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.9rem;
  font-weight: 600;
  color: var(--text-primary);
  transition: color 0.25s ease;
}

.live-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #10B981;
  animation: livePulse 2s ease-in-out infinite;
}

@keyframes livePulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}

.live-count {
  font-size: 0.75rem;
  color: var(--text-muted);
  transition: color 0.25s ease;
}

/* Activity Feed */
.activity-feed {
  display: flex;
  flex-direction: column;
  gap: 0.85rem;
}

.activity-item {
  display: flex;
  gap: 0.75rem;
}

.activity-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  object-fit: cover;
  flex-shrink: 0;
}

.activity-content {
  flex: 1;
  min-width: 0;
}

.activity-text {
  font-size: 0.8rem;
  color: var(--text-secondary);
  line-height: 1.4;
  transition: color 0.25s ease;
}

.activity-name {
  font-weight: 600;
}

.activity-room {
  font-size: 0.75rem;
  color: var(--text-muted);
  margin-top: 0.15rem;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  transition: color 0.25s ease;
}

.activity-time {
  font-size: 0.7rem;
  color: var(--text-muted);
  transition: color 0.25s ease;
}

/* Online List */
.online-list {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.online-item {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.online-avatar-wrap {
  position: relative;
}

.online-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  object-fit: cover;
}

.online-status {
  position: absolute;
  bottom: 0;
  right: 0;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #10B981;
  border: 2px solid var(--card-bg);
}

.online-info {
  display: flex;
  flex-direction: column;
  gap: 0.1rem;
}

.online-name {
  font-size: 0.85rem;
  font-weight: 500;
  color: var(--text-primary);
  transition: color 0.25s ease;
}

.online-status-text {
  font-size: 0.75rem;
  color: #10B981;
}

/* Quick Actions */
.quick-actions {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  margin-top: auto;
  background: transparent;
  padding: 0;
}

.action-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  width: 100%;
  padding: 0.75rem 1rem;
  background: linear-gradient(135deg, #4F7DF3 0%, #6B7FFF 100%);
  border: none;
  border-radius: 10px;
  color: white;
  font-size: 0.85rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
}

.action-btn:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(79, 125, 243, 0.3);
}

.action-btn.secondary {
  background: var(--bg-primary);
  color: var(--text-secondary);
}

.action-btn.secondary:hover {
  background: var(--border-color);
  color: var(--text-primary);
  box-shadow: none;
}

/* ===== Responsive ===== */
@media (max-width: 1200px) {
  .page-layout {
    grid-template-columns: 260px 1fr;
  }

  .right-sidebar {
    display: none;
  }
}

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
</style>
