<script setup lang="ts">
import { ref, watch, nextTick } from 'vue'
import MessageBubble from './MessageBubble.vue'
import ThinkingIndicator from './ThinkingIndicator.vue'
import type { ChatMessage } from '@/composables/useSocket'
import type { Character } from '@/types'

const props = defineProps<{
  messages: ChatMessage[]
  thinkingCharacterId: string | null
  characters: Character[]
}>()

const messagesContainer = ref<HTMLElement | null>(null)

function scrollToBottom() {
  nextTick(() => {
    if (messagesContainer.value) {
      messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
    }
  })
}

watch(() => props.messages.length, () => {
  scrollToBottom()
})

watch(() => props.thinkingCharacterId, () => {
  scrollToBottom()
})

function getCharacterName(characterId: string | null): string {
  if (!characterId) return 'AI'
  const char = props.characters.find(c => c.id === characterId)
  return char?.name || 'AI'
}

const hasMessages = () => props.messages.length > 0
</script>

<template>
  <div class="message-list" ref="messagesContainer">
    <!-- Empty state -->
    <div v-if="!hasMessages()" class="empty-state">
      <h3 class="empty-title">还没有消息</h3>
      <p class="empty-body">开始对话，让角色们展开讨论</p>
    </div>

    <!-- Messages -->
    <div v-else class="messages">
      <MessageBubble
        v-for="msg in messages"
        :key="msg.id"
        :message="msg"
        :is-own="msg.senderType === 'USER'"
      />
    </div>

    <!-- Thinking indicator -->
    <div v-if="thinkingCharacterId" class="thinking-area">
      <ThinkingIndicator :character-name="getCharacterName(thinkingCharacterId)" />
    </div>
  </div>
</template>

<style scoped>
.message-list {
  flex: 1;
  overflow-y: auto;
  padding: 1rem;
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.messages {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.empty-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 2rem;
}

.empty-title {
  font-size: 1.25rem;
  font-weight: 600;
  color: #374151;
  margin: 0 0 0.5rem 0;
}

.empty-body {
  font-size: 0.875rem;
  color: #6B7280;
  margin: 0;
}

.thinking-area {
  padding: 0.5rem 0;
}
</style>
