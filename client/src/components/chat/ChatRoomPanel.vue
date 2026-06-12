<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { useSocket, type ChatMessage } from '@/composables/useSocket'
import { useMessageStore } from '@/stores/message'
import { useRoomStore } from '@/stores/room'
import { useCharacterStore } from '@/stores/character'
import { useAuthStore } from '@/stores/auth'
import type { Character } from '@/types'
import MessageList from '@/components/chat/MessageList.vue'
import ChatInput from '@/components/chat/ChatInput.vue'
import CharacterSidebar from '@/components/character/CharacterSidebar.vue'
import CharacterAddPanel from '@/components/character/CharacterAddPanel.vue'
import CharacterDetailModal from '@/components/character/CharacterDetailModal.vue'
import RoomSettingsModal from '@/components/room/RoomSettingsModal.vue'

const props = defineProps<{
  roomId: string
  embedded?: boolean // when true, hide header and CharacterSidebar for embedding in larger layout
  onToggleRoomList?: () => void
  onToggleRolePanel?: () => void
  showRoomListToggle?: boolean
  showRolePanelToggle?: boolean
}>()

const messageStore = useMessageStore()
const roomStore = useRoomStore()
const characterStore = useCharacterStore()
const authStore = useAuthStore()

// Local state
const showCharacterPanel = ref(false)
const showCharacterDetail = ref(false)
const showRoomSettings = ref(false)
const detailCharacter = ref<Character | null>(null)
const activeCharacterId = ref<string | null>(null)
const characterError = ref<string | null>(null)
const connectionError = ref<string | null>(null)

// 消息列表 ref
const messageListRef = ref<InstanceType<typeof MessageList> | null>(null)

// 滚动到底部（仅在用户主动发送消息时调用）
function scrollToBottom() {
  messageListRef.value?.scrollToBottom()
}

// 监听消息变化，自动滚动（仅用于流式消息时的实时跟进）
watch(
  () => [Object.keys(messageStore.streamingMessages).length, messageStore.thinkingCharacterId],
  () => {
    scrollToBottom()
  }
)

const { isConnected, sendMessage, leaveRoom, pauseDiscussion, resumeDiscussion } = useSocket(props.roomId, {
  onMessage: (msg: ChatMessage) => {
    messageStore.addMessage(msg)
  },
  onThinking: (characterId: string | null) => {
    messageStore.setThinking(characterId)
    if (isDiscussionMode.value && characterId) {
      roomStore.isDiscussing = true
    }
  },
  onStream: (data: { characterId: string; chunk: string }) => {
    messageStore.updateStreamingMessage(data.characterId, data.chunk)
  },
  onError: (error: string) => {
    connectionError.value = error
    console.error('[DEBUG] Socket error:', error)
  },
  onPaused: () => {
    messageStore.setPaused()
  },
  onResumed: () => {
    messageStore.setResumed()
  },
  onDiscussionState: (data: { phase: string; selectedCharacters?: string[]; message?: string }) => {
    messageStore.setDiscussionPhase(data.phase, data.selectedCharacters, data.message)
  },
  onModeratorMessage: (data: { content: string; type: string }) => {
    messageStore.moderatorMessage = data
  }
}, authStore.accessToken)

// Computed
const currentRoom = computed(() => roomStore.currentRoom)
const characters = computed(() => characterStore.characters)
const messages = computed(() => messageStore.messages)
const thinkingCharacterId = computed(() => messageStore.thinkingCharacterId)
const isDiscussionMode = computed(() => currentRoom.value?.chatMode === 'discussion')
const isDiscussing = computed(() => roomStore.isDiscussing)

const statusClass = computed(() => {
  switch (messageStore.discussionPhase) {
    case 'MODERATING': return 'text-blue-500'
    case 'SPEAKING': return 'text-green-500'
    case 'WAITING_FOR_USER': return 'text-yellow-500'
    case 'PAUSED': return 'text-gray-500'
    default: return 'text-gray-400'
  }
})

const statusText = computed(() => {
  switch (messageStore.discussionPhase) {
    case 'MODERATING': return '主持人分析中...'
    case 'SPEAKING': return '讨论中'
    case 'WAITING_FOR_USER': return '等待你参与'
    case 'PAUSED': return '已暂停'
    default: return ''
  }
})

// Mobile sidebar state
const sidebarOpen = ref(false)

function openSidebar() {
  sidebarOpen.value = true
}

function closeSidebar() {
  sidebarOpen.value = false
}

function handleCharacterSelected(character: any) {
  if (character === null) {
    showCharacterPanel.value = true
  } else {
    activeCharacterId.value = character.id
  }
  closeSidebar()
}

function handleCharacterDetail(character: Character) {
  detailCharacter.value = character
  showCharacterDetail.value = true
  closeSidebar()
}

async function switchMode(mode: 'dialogue' | 'discussion') {
  if (mode === currentRoom.value?.chatMode) return
  try {
    await roomStore.updateRoomMode(props.roomId, { chatMode: mode })
  } catch (e) {
    console.error('[DEBUG] Failed to switch mode:', e)
  }
}

// Load data when roomId changes
async function loadRoomData() {
  if (!props.roomId) return

  try {
    await roomStore.fetchRoomById(props.roomId)
    await characterStore.fetchCharacters()
    await messageStore.loadMessages(props.roomId)
    // After messages render, jump to the bottom so the user sees the latest
    // exchange instead of having to scroll past history. Two nextTick hops
    // because the MessageList's inner scroll container is nested one level
    // below the ref's host element.
    await nextTick()
    await nextTick()
    scrollToBottom()
  } catch (error) {
    console.error('[DEBUG] Failed to load chat data:', error)
  }
}

watch(() => props.roomId, () => {
  if (props.roomId) {
    loadRoomData()
  }
}, { immediate: true })

onMounted(async () => {
  if (props.roomId) {
    await loadRoomData()
  }
})

onUnmounted(() => {
  leaveRoom()
  messageStore.clearMessages(props.roomId)
  roomStore.setCurrentRoom(null)
})

// Handle sending a message
function handleSend(content: string) {
  if (!content.trim()) return

  const userMsg: ChatMessage = {
    id: 'temp-' + Date.now(),
    roomId: props.roomId,
    characterId: null,
    characterName: null,
    senderType: 'USER',
    userId: authStore.user?.id || null,
    content: content.trim(),
    avatarUrl: null,
    createdAt: new Date().toISOString()
  }
  messageStore.addMessage(userMsg)

  if (isDiscussionMode.value) {
    roomStore.isDiscussing = true
  }

  sendMessage(content.trim())
}

// Handle pause/resume discussion
function togglePause() {
  if (messageStore.paused) {
    resumeDiscussion()
    messageStore.setResumed()
  } else {
    pauseDiscussion()
    messageStore.setPaused()
  }
}

// Handle adding a character to the room
async function handleCharacterAdded(character: Character) {
  characterError.value = null
  try {
    await roomStore.addCharacterToRoom(props.roomId, character.id)
    const room = roomStore.rooms.find(r => r.id === props.roomId)
    if (room) {
      roomStore.setCurrentRoom(room)
    }
  } catch (error) {
    const message = error instanceof Error ? error.message : '添加角色失败'
    characterError.value = message
    console.error('[DEBUG] Failed to add character:', error)
  }
}
</script>

<template>
  <div class="chat-panel h-full flex flex-col overflow-hidden">
    <!-- Room Header -->
    <header v-if="!props.embedded" class="header">
      <!-- Mobile: hamburger menu -->
      <button
        class="lg:hidden p-2 -ml-2 rounded-lg hover:bg-[var(--color-parchment)] text-[var(--color-text-secondary)] transition-colors"
        @click="openSidebar"
        aria-label="打开角色列表"
      >
        <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M4 6h16M4 12h16M4 18h16" />
        </svg>
      </button>

      <!-- Room name and info -->
      <div class="flex-1 flex items-center gap-3 min-w-0">
        <div class="flex items-center gap-2">
          <div class="w-1 h-8 bg-gradient-to-b from-[var(--color-gold-dark)] to-[var(--color-gold)] rounded-full"></div>
          <h1 class="text-lg font-semibold text-[var(--color-navy)] truncate font-['Playfair_Display']">
            {{ currentRoom?.name || '聊天室' }}
          </h1>
        </div>
      </div>
    </header>

    <!-- Embedded Header with Toggle Buttons -->
    <header v-else class="chat-main-header">
      <div class="chat-header-left">
        <button
          v-if="props.showRoomListToggle"
          class="chat-header-icon-button"
          @click="props.onToggleRoomList?.()"
          aria-label="切换聊天室列表"
        >
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 6h16M4 12h16M4 18h16" />
          </svg>
        </button>

        <div class="flex items-center gap-2">
          <div class="w-1 h-6 bg-gradient-to-b from-[var(--color-gold-dark)] to-[var(--color-gold)] rounded-full"></div>
          <div>
            <h1 class="text-base font-semibold text-[var(--color-navy)] truncate font-['Playfair_Display']">
              {{ currentRoom?.name || '聊天室' }}
            </h1>
            <p v-if="currentRoom?.characters?.length === 1" class="text-xs text-[var(--text-muted)] truncate">
              {{ currentRoom.characters[0].description || '暂无描述' }}
            </p>
          </div>
        </div>
      </div>

      <div class="chat-header-right">
        <span
          v-if="currentRoom?.characters?.length > 0"
          class="hidden sm:inline-flex items-center gap-1.5 px-3 py-1 text-xs font-medium bg-[var(--color-parchment)] text-[var(--color-navy)] rounded-full border border-[var(--color-border)]"
        >
          <svg class="w-3.5 h-3.5 text-[var(--color-gold)]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0z" />
          </svg>
          {{ currentRoom.characters.length }}
        </span>
        <button
          v-if="props.showRolePanelToggle"
          class="chat-header-icon-button"
          @click="showRoomSettings = true"
          aria-label="设置"
          title="设置"
        >
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" />
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
          </svg>
        </button>
        <button
          v-if="props.showRolePanelToggle"
          class="chat-header-icon-button"
          @click="props.onToggleRolePanel?.()"
          aria-label="切换角色面板"
        >
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0z" />
          </svg>
        </button>
      </div>
    </header>

    <!-- Character error banner -->
    <div v-if="characterError" class="px-4 py-2 bg-red-50 border-b border-red-200 text-red-600 text-sm">
      {{ characterError }}
      <button @click="characterError = null" class="ml-2 underline">关闭</button>
    </div>

    <!-- Main content area -->
    <div class="flex-1 flex min-h-0">
      <!-- Character sidebar (hidden when embedded, handled by external panel) -->
      <CharacterSidebar
        v-if="!props.embedded"
        :show="sidebarOpen"
        :characters="currentRoom?.characters || []"
        :active-character-id="thinkingCharacterId"
        :is-thinking="!!thinkingCharacterId"
        :chat-mode="currentRoom?.chatMode || 'dialogue'"
        :is-discussing="isDiscussing"
        @close="closeSidebar"
        @character-selected="handleCharacterSelected"
        @character-detail="handleCharacterDetail"
        @switch-mode="switchMode"
      />

      <!-- Message area -->
      <main class="h-full flex-1 flex flex-col min-h-0 min-w-0 bg-[var(--color-cream)] overflow-hidden">
        <!-- Connection warning -->
        <div v-if="!isConnected" class="px-4 py-2 bg-yellow-50 border-b border-yellow-200 text-yellow-700 text-sm flex items-center gap-2 shrink-0 flex-shrink-0">
          <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
          </svg>
          连接中... 消息暂存本地
        </div>
        <!-- Discussion status indicator -->
        <div v-if="isDiscussionMode && messageStore.discussionPhase !== 'IDLE'" class="px-4 py-2 bg-[var(--color-parchment)] border-b border-[var(--color-border)] flex items-center justify-center">
          <span :class="['text-sm font-medium flex items-center gap-2', statusClass]">
            <span v-if="messageStore.discussionPhase === 'MODERATING'" class="animate-pulse">🎤</span>
            <span v-if="messageStore.discussionPhase === 'SPEAKING'">💬</span>
            <span v-if="messageStore.discussionPhase === 'WAITING_FOR_USER'">⏳</span>
            <span v-if="messageStore.discussionPhase === 'PAUSED'">⏸</span>
            {{ statusText }}
          </span>
        </div>
        <MessageList
          ref="messageListRef"
          :messages="messages"
          :thinking-character-id="thinkingCharacterId"
          :characters="currentRoom?.characters || []"
          :streaming-messages="messageStore.streamingMessages"
          :current-user-id="authStore.user?.id"
        />
        <!-- Discussion control bar (only in discussion mode) -->
        <div v-if="isDiscussionMode && isDiscussing" class="shrink-0 px-4 py-2 bg-[var(--color-parchment)] border-t border-[var(--color-border)] flex items-center justify-center gap-4">
          <button
            v-if="!messageStore.paused"
            @click="togglePause"
            class="flex items-center gap-2 px-4 py-2 bg-[var(--color-navy)] text-white rounded-lg hover:bg-[var(--color-navy-light)] transition-colors text-sm font-medium"
          >
            <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 9v6m4-6v6m7-3a9 9 0 11-18 0 9 9 0 0118 0z" />
            </svg>
            暂停讨论
          </button>
          <button
            v-else
            @click="togglePause"
            class="flex items-center gap-2 px-4 py-2 bg-[var(--color-gold)] text-[var(--color-navy)] rounded-lg hover:bg-[var(--color-gold-light)] transition-colors text-sm font-medium"
          >
            <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M14.752 11.168l-3.197-2.132A1 1 0 0010 9.87v4.263a1 1 0 001.555.832l3.197-2.132a1 1 0 000-1.664z" />
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
            </svg>
            继续讨论
          </button>
          <!-- Show waiting message when in WAITING_FOR_USER phase -->
          <span v-if="messageStore.discussionPhase === 'WAITING_FOR_USER'" class="text-sm text-yellow-600 font-medium">
            🎤 主持人正在等待你的观点...
          </span>
          <span v-else-if="messageStore.paused" class="text-sm text-[var(--color-text-secondary)]">
            讨论已暂停，发送消息将继续
          </span>
        </div>
        <!-- Chat input - fixed footer -->
        <div class="shrink-0 border-t border-[var(--color-border)] bg-[var(--color-ivory)]">
          <ChatInput
            :disabled="false"
            @send="handleSend"
          />
        </div>
      </main>

      <!-- Character add panel -->
      <CharacterAddPanel
        :show="showCharacterPanel"
        @close="showCharacterPanel = false"
        @character-added="handleCharacterAdded"
      />

      <!-- Character detail modal -->
      <CharacterDetailModal
        :show="showCharacterDetail"
        :character="detailCharacter"
        @close="showCharacterDetail = false"
      />

      <!-- Room settings modal -->
      <RoomSettingsModal
        :show="showRoomSettings"
        :room-id="props.roomId"
        @close="showRoomSettings = false"
      />
    </div>
  </div>
</template>

<style scoped>
.chat-panel {
  height: 100%;
  contain: layout style;
}

/* 消息滚动区域样式 */
:deep(.message-scroll-container) {
  scrollbar-width: thin;
  scrollbar-color: var(--color-gold) var(--color-parchment);
}

:deep(.message-scroll-container)::-webkit-scrollbar {
  width: 8px;
}

:deep(.message-scroll-container)::-webkit-scrollbar-track {
  background: var(--color-parchment);
  border-radius: 4px;
}

:deep(.message-scroll-container)::-webkit-scrollbar-thumb {
  background: linear-gradient(180deg, var(--color-gold) 0%, var(--color-gold-dark) 100%);
  border-radius: 4px;
}

:deep(.message-scroll-container)::-webkit-scrollbar-thumb:hover {
  background: linear-gradient(180deg, var(--color-gold-light) 0%, var(--color-gold) 100%);
}

:deep(.dark) .message-scroll-container {
  scrollbar-color: var(--color-gold) var(--color-space);
}

:deep(.dark) .message-scroll-container::-webkit-scrollbar-track {
  background: var(--color-space);
}

.header {
  height: 68px;
  flex-shrink: 0;
  padding: 0 1.25rem;
  display: flex;
  align-items: center;
  gap: 0.875rem;
  background: linear-gradient(180deg, var(--color-ivory) 0%, var(--color-cream) 100%);
  border-bottom: 1px solid rgba(224, 214, 200, 0.6);
  position: relative;
}

.header::after {
  content: '';
  position: absolute;
  bottom: 0;
  left: 50%;
  transform: translateX(-50%);
  width: 120px;
  height: 2px;
  background: linear-gradient(90deg, transparent, var(--color-gold), transparent);
}

/* Embedded header with toggle buttons */
.chat-main-header {
  height: 64px;
  padding: 0 1rem;
  border-bottom: 1px solid rgba(226, 232, 240, 0.9);
  background: rgba(255, 255, 255, 0.86);
  backdrop-filter: blur(12px);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
}

:deep(.dark) .chat-main-header {
  background: rgba(15, 23, 42, 0.86);
  border-color: rgba(71, 85, 105, 0.85);
}

.chat-header-left,
.chat-header-right {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.chat-header-icon-button {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  border: 1px solid #e2e8f0;
  background: #ffffff;
  color: #0f172a;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.15s ease;
}

.chat-header-icon-button:hover {
  background: #f1f5f9;
  border-color: #cbd5e1;
}

:deep(.dark) .chat-header-icon-button {
  background: #1e293b;
  color: #e2e8f0;
  border-color: rgba(71, 85, 105, 0.85);
}

:deep(.dark) .chat-header-icon-button:hover {
  background: #334155;
}
</style>
