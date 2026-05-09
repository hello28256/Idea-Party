<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSocket, type ChatMessage } from '@/composables/useSocket'
import { useMessageStore } from '@/stores/message'
import { useRoomStore } from '@/stores/room'
import { useCharacterStore } from '@/stores/character'
import MessageList from '@/components/chat/MessageList.vue'
import ChatInput from '@/components/chat/ChatInput.vue'
import CharacterSidebar from '@/components/character/CharacterSidebar.vue'
import CharacterAddPanel from '@/components/character/CharacterAddPanel.vue'

const route = useRoute()
const router = useRouter()
const messageStore = useMessageStore()
const roomStore = useRoomStore()
const characterStore = useCharacterStore()

const roomId = computed(() => route.params.roomId as string)

// Local state
const showCharacterPanel = ref(false)
const activeCharacterId = ref<string | null>(null)
const { socket, isConnected, sendMessage, leaveRoom } = useSocket(roomId.value, {
  onMessage: (msg: ChatMessage) => {
    messageStore.addMessage(msg)
  },
  onThinking: (characterId: string | null) => {
    messageStore.setThinking(characterId)
  },
  onStream: (data: { characterId: string; chunk: string }) => {
    messageStore.updateStreamingMessage(data.characterId, data.chunk)
  },
  onError: (error: string) => {
    console.error('[DEBUG] Socket error:', error)
  }
})

// Computed
const currentRoom = computed(() => roomStore.currentRoom)
const characters = computed(() => characterStore.characters)
const messages = computed(() => messageStore.messages)
const thinkingCharacterId = computed(() => messageStore.thinkingCharacterId)

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

// Load data on mount
onMounted(async () => {
  if (!roomId.value) {
    router.push('/rooms')
    return
  }

  try {
    // Load room details
    await roomStore.fetchRooms()
    const room = roomStore.rooms.find(r => r.id === roomId.value)
    if (room) {
      roomStore.setCurrentRoom(room)
    }

    // Load characters
    await characterStore.fetchCharacters()

    // Load message history
    await messageStore.loadMessages(roomId.value)
  } catch (error) {
    console.error('[DEBUG] Failed to load chat data:', error)
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

  // Send via socket
  sendMessage(content.trim())
}

// Handle adding a character to the room
async function handleCharacterAdded(characterId: string) {
  try {
    await roomStore.addCharacterToRoom(roomId.value, characterId)
    // Refresh room data
    const room = roomStore.rooms.find(r => r.id === roomId.value)
    if (room) {
      roomStore.setCurrentRoom(room)
    }
  } catch (error) {
    console.error('[DEBUG] Failed to add character:', error)
  }
}
</script>

<template>
  <div class="chat-view h-screen flex flex-col bg-white overflow-hidden">
    <!-- Room Header - Mobile: hamburger + title + character count -->
    <header class="h-14 px-4 flex items-center border-b border-[#E5E7EB] bg-white shrink-0">
      <!-- Mobile: hamburger menu -->
      <button
        class="lg:hidden p-2 -ml-2 rounded-md hover:bg-gray-100 text-[#6B7280]"
        @click="openSidebar"
        aria-label="打开角色列表"
      >
        <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 6h16M4 12h16M4 18h16" />
        </svg>
      </button>

      <!-- Room name -->
      <div class="flex-1 flex items-center gap-2 min-w-0">
        <h1 class="text-lg font-semibold text-[#1F2937] truncate">
          {{ currentRoom?.name || '聊天室' }}
        </h1>
        <!-- Character count badge -->
        <span
          v-if="currentRoom?.characterCount && currentRoom.characterCount > 0"
          class="hidden sm:inline-flex px-2 py-0.5 text-xs font-medium bg-[#F0FDF4] text-[#10B981] rounded-full shrink-0"
        >
          {{ currentRoom.characterCount }} 个角色
        </span>
      </div>

      <!-- Desktop: menu slot -->
      <div class="hidden lg:flex items-center gap-2">
        <button class="p-2 rounded-md hover:bg-gray-100 text-text-secondary" @click="router.push('/rooms')">
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 19l-7-7m0 0l7-7m-7 7h18" />
          </svg>
        </button>
        <button class="p-2 rounded-md hover:bg-gray-100 text-text-secondary">
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 5v.01M12 12v.01M12 19v.01M12 6a1 1 0 110-2 1 1 0 010 2zm0 7a1 1 0 110-2 1 1 0 010 2zm0 7a1 1 0 110-2 1 1 0 010 2z" />
          </svg>
        </button>
      </div>
    </header>

    <!-- Main content area -->
    <div class="flex-1 flex overflow-hidden">
      <!-- Character sidebar - uses CharacterSidebar component -->
      <CharacterSidebar
        :show="sidebarOpen"
        :characters="characters"
        :active-character-id="thinkingCharacterId"
        :is-thinking="!!thinkingCharacterId"
        @close="closeSidebar"
        @character-selected="handleCharacterSelected"
      />

      <!-- Message area -->
      <main class="flex-1 flex flex-col min-w-0">
        <!-- Messages -->
        <div class="flex-1 overflow-hidden">
          <MessageList
            :messages="messages"
            :thinking-character-id="thinkingCharacterId"
            :characters="characters"
          />
        </div>

        <!-- Chat input - fixed at bottom -->
        <div class="shrink-0 border-t border-[#E5E7EB] bg-white">
          <ChatInput
            :disabled="!isConnected"
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
    </div>
  </div>
</template>

<style scoped>
.chat-view {
  /* Ensures proper mobile viewport handling */
  contain: layout style;
}
</style>
