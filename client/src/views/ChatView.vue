<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
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

const route = useRoute()
const router = useRouter()
const messageStore = useMessageStore()
const roomStore = useRoomStore()
const characterStore = useCharacterStore()

const roomId = computed(() => route.params.roomId as string)

// Local state
const showCharacterPanel = ref(false)
const showCharacterDetail = ref(false)
const showRoomSettings = ref(false)
const showModeDropdown = ref(false)
const modeDropdownRef = ref<HTMLElement | null>(null)
const detailCharacter = ref<Character | null>(null)
const activeCharacterId = ref<string | null>(null)
const characterError = ref<string | null>(null)
const connectionError = ref<string | null>(null)
const { isConnected, sendMessage, stopDiscussion: stopDiscussionSocket, leaveRoom } = useSocket(roomId.value, {
  onMessage: (msg: ChatMessage) => {
    messageStore.addMessage(msg)
  },
  onThinking: (characterId: string | null) => {
    messageStore.setThinking(characterId)
    // 在讨论模式下，当角色开始思考时设置讨论状态
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

// Mobile sidebar state (drawer)
const sidebarOpen = ref(false)

function openSidebar() {
  sidebarOpen.value = true
}

function closeSidebar() {
  sidebarOpen.value = false
}

function handleCharacterSelected(character: any) {
  if (character === null) {
    // User wants to add a character
    showCharacterPanel.value = true
  } else {
    activeCharacterId.value = character.id
  }
  // Close sidebar on mobile after selection
  closeSidebar()
}

function handleCharacterDetail(character: Character) {
  detailCharacter.value = character
  showCharacterDetail.value = true
  closeSidebar()
}

function toggleModeDropdown() {
  showModeDropdown.value = !showModeDropdown.value
}

async function switchMode(mode: 'dialogue' | 'discussion') {
  showModeDropdown.value = false
  if (mode === currentRoom.value?.chatMode) return
  try {
    await roomStore.updateRoomMode(roomId.value, { chatMode: mode })
  } catch (e) {
    console.error('[DEBUG] Failed to switch mode:', e)
  }
}

// Close dropdown when clicking outside
function handleClickOutside(event: MouseEvent) {
  if (modeDropdownRef.value && !modeDropdownRef.value.contains(event.target as Node)) {
    showModeDropdown.value = false
  }
}

// Load data on mount
onMounted(async () => {
  if (!roomId.value) {
    router.push('/rooms')
    return
  }

  try {
    // Load room details (with characters)
    await roomStore.fetchRoomById(roomId.value)

    // Load characters
    await characterStore.fetchCharacters()

    // Load message history
    await messageStore.loadMessages(roomId.value)
  } catch (error) {
    console.error('[DEBUG] Failed to load chat data:', error)
  }

  // Add click outside listener for mode dropdown
  document.addEventListener('click', handleClickOutside)
})

onUnmounted(() => {
  document.removeEventListener('click', handleClickOutside)
  leaveRoom()
  messageStore.clearMessages()
  roomStore.setCurrentRoom(null)
})

// Handle sending a message
function handleSend(content: string) {
  if (!content.trim()) return

  // Optimistically add user message to local state
  const userMsg: ChatMessage = {
    id: 'temp-' + Date.now(),
    roomId: roomId.value,
    characterId: null,
    characterName: null,
    senderType: 'USER',
    content: content.trim(),
    avatarUrl: null,
    createdAt: new Date().toISOString()
  }
  messageStore.addMessage(userMsg)

  // In discussion mode, mark as discussing when user sends a message
  if (isDiscussionMode.value) {
    roomStore.isDiscussing = true
  }

  // Send via socket
  sendMessage(content.trim())
}

// Handle stop discussion button
function stopDiscussion() {
  stopDiscussionSocket()
  roomStore.isDiscussing = false
}

// Handle adding a character to the room
async function handleCharacterAdded(character: Character) {
  characterError.value = null
  try {
    await roomStore.addCharacterToRoom(roomId.value, character.id)
    // Refresh room data
    const room = roomStore.rooms.find(r => r.id === roomId.value)
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
  <div class="chat-view h-screen flex flex-col overflow-hidden">
    <!-- Room Header -->
    <header class="header">
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

        <!-- Mode quick toggle with dropdown -->
        <div class="relative" ref="modeDropdownRef">
          <button
            class="flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-xs font-medium border transition-all"
            :class="isDiscussionMode
              ? 'bg-amber-50 border-amber-200 text-amber-700 hover:bg-amber-100'
              : 'bg-blue-50 border-blue-200 text-blue-700 hover:bg-blue-100'"
            @click="toggleModeDropdown"
          >
            <svg v-if="isDiscussionMode" class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 8h2a2 2 0 012 2v6a2 2 0 01-2 2h-2v4l-4-4H9a1.994 1.994 0 01-1.414-.586m0 0L11 14h4a2 2 0 002-2V6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2v4l.586-.586z" />
            </svg>
            <svg v-else class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
            </svg>
            {{ isDiscussionMode ? '讨论' : '对话' }}
            <svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
            </svg>
          </button>

          <!-- Mode dropdown menu -->
          <div
            v-if="showModeDropdown"
            class="absolute right-0 mt-1 w-40 bg-white rounded-lg shadow-lg border border-gray-100 py-1 z-50"
          >
            <button
              class="w-full px-3 py-2 text-left text-sm hover:bg-gray-50 flex items-center gap-2"
              :class="!isDiscussionMode ? 'text-blue-600 font-medium' : 'text-gray-700'"
              @click="switchMode('dialogue')"
            >
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
              </svg>
              对话模式
            </button>
            <button
              class="w-full px-3 py-2 text-left text-sm hover:bg-gray-50 flex items-center gap-2"
              :class="isDiscussionMode ? 'text-amber-600 font-medium' : 'text-gray-700'"
              @click="switchMode('discussion')"
            >
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 8h2a2 2 0 012 2v6a2 2 0 01-2 2h-2v4l-4-4H9a1.994 1.994 0 01-1.414-.586m0 0L11 14h4a2 2 0 002-2V6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2v4l.586-.586z" />
              </svg>
              讨论模式
            </button>
            <hr class="my-1 border-gray-100" />
            <button
              class="w-full px-3 py-2 text-left text-sm hover:bg-gray-50 text-gray-500 flex items-center gap-2"
              @click="showRoomSettings = true; showModeDropdown = false"
            >
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" />
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
              </svg>
              更多设置
            </button>
          </div>
        </div>

        <!-- Stop discussion button (discussion mode only) -->
        <button
          v-if="isDiscussionMode && isDiscussing"
          class="px-2.5 py-1.5 text-xs font-medium bg-red-100 text-red-600 rounded-lg hover:bg-red-200 transition-colors flex items-center gap-1"
          @click="stopDiscussion"
        >
          <svg class="w-3 h-3" fill="currentColor" viewBox="0 0 24 24">
            <rect x="6" y="6" width="12" height="12" rx="1" />
          </svg>
          停止
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
      <!-- Character sidebar -->
      <CharacterSidebar
        :show="sidebarOpen"
        :characters="currentRoom?.characters || []"
        :active-character-id="thinkingCharacterId"
        :is-thinking="!!thinkingCharacterId"
        @close="closeSidebar"
        @character-selected="handleCharacterSelected"
        @character-detail="handleCharacterDetail"
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
        :room-id="roomId"
        @close="showRoomSettings = false"
      />
    </div>
  </div>
</template>

<style scoped>
.chat-view {
  contain: layout style;
}

.header {
  height: 64px;
  padding: 0 1rem;
  display: flex;
  align-items: center;
  gap: 0.75rem;
  background: linear-gradient(180deg, var(--color-ivory) 0%, var(--color-cream) 100%);
  border-bottom: 1px solid var(--color-border);
  position: relative;
}

.header::after {
  content: '';
  position: absolute;
  bottom: 0;
  left: 50%;
  transform: translateX(-50%);
  width: 100px;
  height: 1px;
  background: linear-gradient(90deg, transparent, var(--color-gold), transparent);
}
</style>
