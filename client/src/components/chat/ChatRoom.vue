<script setup lang="ts">
import { ref, onUnmounted, watch } from 'vue';
import { useRoomStore } from '../../stores/room';
import { useMessagesStore } from '../../stores/messages';
import { useSocket, type AIChunkData, type AICompleteData } from '../../composables/useSocket';
import type { Message } from '../../types';
import MessageList from './MessageList.vue';
import MessageInput from './MessageInput.vue';

const roomStore = useRoomStore();
const messagesStore = useMessagesStore();
const { isConnected, joinRoom, leaveRoom, on, emit, onAIChunk, onAIComplete } = useSocket();

const isThinking = ref(false);
const thinkingCharacterName = ref('');

function handleNewMessage(data: unknown) {
  const message = data as Message;
  messagesStore.addMessage(message);
}

function handleAIThinking(data: unknown) {
  isThinking.value = (data as { thinking: boolean }).thinking;
}

function handleAIChunk(data: unknown) {
  const chunk = data as AIChunkData;
  // Update thinking indicator with character name
  thinkingCharacterName.value = chunk.characterName;
  // The message will be streamed in via the complete event
}

function handleAIComplete(data: unknown) {
  const complete = data as AICompleteData;
  isThinking.value = false;
  thinkingCharacterName.value = '';
  // Add the complete AI message to the message list
  messagesStore.addMessage({
    id: complete.messageId,
    content: complete.content,
    role: 'character',
    characterId: complete.characterId,
    characterName: complete.characterName,
    roomId: roomStore.currentRoom?.id || '',
    createdAt: new Date().toISOString(),
  });
}

watch(
  () => roomStore.currentRoom,
  async (room) => {
    if (room) {
      await messagesStore.fetchMessages(room.id);
      joinRoom(room.id);
      on('chat message', handleNewMessage);
      on('ai-thinking', handleAIThinking);
      onAIChunk(handleAIChunk);
      onAIComplete(handleAIComplete);
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

    // Trigger AI response via Socket.IO
    isThinking.value = true;
    thinkingCharacterName.value = roomStore.currentRoom.characters?.[0]?.name || 'AI';
    emit('trigger-ai', {
      roomId: roomStore.currentRoom.id,
      message: content,
    });
  } catch (e) {
    console.error('Failed to send message:', e);
    isThinking.value = false;
    thinkingCharacterName.value = '';
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
      {{ thinkingCharacterName }} is thinking...
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
