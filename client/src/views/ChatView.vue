<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSocket, type ChatMessage } from '@/composables/useSocket'
import { useMessageStore } from '@/stores/message'
import { useRoomStore } from '@/stores/room'
import { useCharacterStore } from '@/stores/character'
import MessageList from '@/components/chat/MessageList.vue'
import ChatInput from '@/components/chat/ChatInput.vue'
import CharacterCard from '@/components/character/CharacterCard.vue'
import CharacterAddPanel from '@/components/character/CharacterAddPanel.vue'
import RoomHeader from '@/components/room/RoomHeader.vue'

const route = useRoute()
const router = useRouter()
const messageStore = useMessageStore()
const roomStore = useRoomStore()
const characterStore = useCharacterStore()

const roomId = computed(() => route.params.roomId as string)

// Local state
const showCharacterPanel = ref(false)
const showMobileSidebar = ref(false)
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

// Toggle mobile sidebar
function toggleMobileSidebar() {
  showMobileSidebar.value = !showMobileSidebar.value
}
</script>

<template>
  <div class="chat-view">
    <!-- Room Header -->
    <RoomHeader v-if="currentRoom" :room="currentRoom">
      <template #menu-items>
        <button class="w-full px-4 py-2 text-left text-sm hover:bg-gray-100">
          房间设置
        </button>
        <button
          class="w-full px-4 py-2 text-left text-sm hover:bg-gray-100 text-red-600"
          @click="router.push('/rooms')"
        >
          退出房间
        </button>
      </template>
    </RoomHeader>

    <!-- Main content area -->
    <div class="chat-content">
      <!-- Left sidebar - Character list -->
      <aside
        class="character-sidebar"
        :class="{ 'mobile-open': showMobileSidebar }"
      >
        <div class="sidebar-header">
          <h2 class="sidebar-title">角色列表</h2>
          <button
            class="add-character-btn"
            @click="showCharacterPanel = !showCharacterPanel"
          >
            添加角色
          </button>
        </div>

        <div class="character-list">
          <CharacterCard
            v-for="char in characters"
            :key="char.id"
            :character="char"
            :is-thinking="thinkingCharacterId === char.id"
          />

          <div v-if="characters.length === 0" class="no-characters">
            <p>还没有添加角色</p>
            <button
              class="text-[#10B981] text-sm"
              @click="showCharacterPanel = true"
            >
              添加第一个角色
            </button>
          </div>
        </div>
      </aside>

      <!-- Center - Message area -->
      <main class="message-area">
        <div class="mobile-header">
          <button class="menu-button" @click="toggleMobileSidebar">
            <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 6h16M4 12h16M4 18h16" />
            </svg>
          </button>
          <h1 class="text-lg font-semibold">{{ currentRoom?.name || '聊天室' }}</h1>
        </div>

        <MessageList
          :messages="messages"
          :thinking-character-id="thinkingCharacterId"
          :characters="characters"
        />

        <ChatInput
          :disabled="!isConnected"
          @send="handleSend"
        />
      </main>

      <!-- Right sidebar - Character add panel -->
      <aside
        v-if="showCharacterPanel"
        class="character-panel"
      >
        <CharacterAddPanel
          @character-added="handleCharacterAdded"
          @close="showCharacterPanel = false"
        />
      </aside>
    </div>

    <!-- Mobile overlay -->
    <div
      v-if="showMobileSidebar"
      class="mobile-overlay"
      @click="showMobileSidebar = false"
    ></div>
  </div>
</template>

<style scoped>
.chat-view {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background-color: white;
}

.chat-content {
  flex: 1;
  display: flex;
  overflow: hidden;
}

/* Left sidebar - Character list */
.character-sidebar {
  width: 260px;
  border-right: 1px solid #E5E7EB;
  display: flex;
  flex-direction: column;
  background-color: #FAFAFA;
}

.sidebar-header {
  padding: 1rem;
  border-bottom: 1px solid #E5E7EB;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.sidebar-title {
  font-size: 1rem;
  font-weight: 600;
  color: #374151;
  margin: 0;
}

.add-character-btn {
  padding: 0.5rem 1rem;
  background-color: #10B981;
  color: white;
  border: none;
  border-radius: 0.375rem;
  font-size: 0.875rem;
  font-weight: 500;
  cursor: pointer;
  transition: background-color 0.2s;
}

.add-character-btn:hover {
  background-color: #059669;
}

.character-list {
  flex: 1;
  overflow-y: auto;
  padding: 0.75rem;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.no-characters {
  text-align: center;
  padding: 2rem 1rem;
  color: #6B7280;
}

.no-characters p {
  margin: 0 0 0.5rem 0;
  font-size: 0.875rem;
}

/* Center - Message area */
.message-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.mobile-header {
  display: none;
  padding: 0.75rem;
  border-bottom: 1px solid #E5E7EB;
  align-items: center;
  gap: 0.75rem;
}

.menu-button {
  padding: 0.5rem;
  background: none;
  border: none;
  cursor: pointer;
  color: #6B7280;
}

/* Right sidebar - Character panel */
.character-panel {
  width: 320px;
  border-left: 1px solid #E5E7EB;
  overflow-y: auto;
}

/* Mobile overlay */
.mobile-overlay {
  display: none;
  position: fixed;
  inset: 0;
  background-color: rgba(0, 0, 0, 0.5);
  z-index: 40;
}

/* Responsive */
@media (max-width: 768px) {
  .character-sidebar {
    position: fixed;
    left: 0;
    top: 0;
    bottom: 0;
    z-index: 50;
    transform: translateX(-100%);
    transition: transform 0.3s ease;
  }

  .character-sidebar.mobile-open {
    transform: translateX(0);
  }

  .mobile-header {
    display: flex;
  }

  .mobile-overlay {
    display: block;
  }

  .character-panel {
    position: fixed;
    right: 0;
    top: 0;
    bottom: 0;
    z-index: 50;
  }
}
</style>
