<script setup lang="ts">
import { ref, onUnmounted, watch } from 'vue';
import { useRoomStore } from '../../stores/room';
import { useMessagesStore } from '../../stores/messages';
import { useSocket } from '../../composables/useSocket';
import type { Message } from '../../types';
import MessageList from './MessageList.vue';
import MessageInput from './MessageInput.vue';

const roomStore = useRoomStore();
const messagesStore = useMessagesStore();
const { isConnected, joinRoom, leaveRoom, on } = useSocket();

const isThinking = ref(false);

function handleNewMessage(data: unknown) {
  const message = data as Message;
  messagesStore.addMessage(message);
}

function handleAIThinking(data: unknown) {
  isThinking.value = (data as { thinking: boolean }).thinking;
}

watch(
  () => roomStore.currentRoom,
  async (room) => {
    if (room) {
      await messagesStore.fetchMessages(room.id);
      joinRoom(room.id);
      on('chat message', handleNewMessage);
      on('ai-thinking', handleAIThinking);
    }
  },
  { immediate: true }
);

onUnmounted(() => {
  if (roomStore.currentRoom) {
    leaveRoom(roomStore.currentRoom.id);
  }
});

async function handleSendMessage(content: string) {
  if (!roomStore.currentRoom) return;

  try {
    // Add message locally for immediate feedback
    messagesStore.addMessage({
      id: 'temp-' + Date.now(),
      content,
      role: 'user',
      roomId: roomStore.currentRoom.id,
      createdAt: new Date().toISOString(),
    });

    // TODO: Trigger AI response via Socket.IO
    isThinking.value = true;
    setTimeout(() => {
      isThinking.value = false;
    }, 1500);
  } catch (e) {
    console.error('Failed to send message:', e);
  }
}
</script>

<template>
  <div class="chat-room">
    <div class="chat-header">
      <div class="connection-status" :class="{ connected: isConnected }"></div>
      <h2>{{ roomStore.currentRoom?.name || 'Chat Room' }}</h2>
      <span v-if="roomStore.currentRoom?.theme" class="theme">
        {{ roomStore.currentRoom.theme }}
      </span>
    </div>

    <div class="thinking-indicator" v-if="isThinking">
      AI is thinking...
    </div>

    <MessageList
      :messages="messagesStore.messages"
      :is-loading="messagesStore.isLoading"
    />

    <MessageInput @send="handleSendMessage" />
  </div>
</template>

<style scoped>
.chat-room {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #fff;
}

.chat-header {
  display: flex;
  align-items: center;
  gap: 1rem;
  padding: 1rem;
  background: #667eea;
  color: white;
}

.connection-status {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #ff4444;
}

.connection-status.connected {
  background: #44ff44;
}

.chat-header h2 {
  margin: 0;
}

.theme {
  padding: 0.25rem 0.75rem;
  background: rgba(255, 255, 255, 0.2);
  border-radius: 4px;
  font-size: 0.875rem;
}

.thinking-indicator {
  padding: 0.5rem 1rem;
  background: #fff3cd;
  color: #856404;
  text-align: center;
  font-size: 0.875rem;
}
</style>
