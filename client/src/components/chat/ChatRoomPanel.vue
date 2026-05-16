<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useSocket, type ChatMessage } from '@/composables/useSocket'
import { useMessageStore } from '@/stores/message'
import { useRoomStore } from '@/stores/room'
import { useCharacterStore } from '@/stores/character'
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

// Local state
const showCharacterPanel = ref(false)
const showCharacterDetail = ref(false)
const showRoomSettings = ref(false)
const detailCharacter = ref<Character | null>(null)
const activeCharacterId = ref<string | null>(null)
const characterError = ref<string | null>(null)
const connectionError = ref<string | null>(null)

const { isConnected, sendMessage, leaveRoom } = useSocket(props.roomId, {
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
  }
})

// Computed
const currentRoom = computed(() => roomStore.currentRoom)
const characters = computed(() => characterStore.characters)
const messages = computed(() => messageStore.messages)
const thinkingCharacterId = computed(() => messageStore.thinkingCharacterId)
const isDiscussionMode = computed(() => currentRoom.value?.chatMode === 'discussion')
const isDiscussing = computed(() => roomStore.isDiscussing)

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
  messageStore.clearMessages()
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
        <!-- Character count badge -->
        <span
          v-if="currentRoom?.characterCount && currentRoom.characterCount > 0"
          class="hidden sm:inline-flex items-center gap-1.5 px-3 py-1 text-xs font-medium bg-[var(--color-parchment)] text-[var(--color-navy)] rounded-full border border-[var(--color-border)]"
        >
          <svg class="w-3.5 h-3.5 text-[var(--color-gold)]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0z" />
          </svg>
          {{ currentRoom.characterCount }} 位思想家
        </span>
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
          <h1 class="text-base font-semibold text-[var(--color-navy)] truncate font-['Playfair_Display']">
            {{ currentRoom?.name || '聊天室' }}
          </h1>
        </div>
      </div>

      <div class="chat-header-right">
        <span
          v-if="currentRoom?.characterCount && currentRoom.characterCount > 0"
          class="hidden sm:inline-flex items-center gap-1.5 px-3 py-1 text-xs font-medium bg-[var(--color-parchment)] text-[var(--color-navy)] rounded-full border border-[var(--color-border)]"
        >
          <svg class="w-3.5 h-3.5 text-[var(--color-gold)]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0z" />
          </svg>
          {{ currentRoom.characterCount }}
        </span>
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
    <div class="flex-1 flex overflow-hidden">
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
      <main class="flex-1 flex flex-col min-h-0 min-w-0 overflow-hidden bg-[var(--color-cream)]">
        <!-- Connection warning -->
        <div v-if="!isConnected" class="px-4 py-2 bg-yellow-50 border-b border-yellow-200 text-yellow-700 text-sm flex items-center gap-2">
          <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
          </svg>
          连接中... 消息暂存本地
        </div>
        <!-- Messages -->
        <div class="flex-1 min-h-0 overflow-hidden">
          <MessageList
            :messages="messages"
            :thinking-character-id="thinkingCharacterId"
            :characters="characters"
          />
        </div>

        <!-- Chat input -->
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
  contain: layout style;
}

.header {
  height: 68px;
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
