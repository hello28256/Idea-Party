<script setup lang="ts">
import { ref, watch, nextTick, onMounted } from 'vue'
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
    const el = messagesContainer.value
    if (el) {
      el.scrollTop = el.scrollHeight
      console.log('[DEBUG] Scrolling to bottom. scrollTop:', el.scrollTop, 'scrollHeight:', el.scrollHeight)
    } else {
      console.log('[DEBUG] messagesContainer is null')
    }
    // Double ensure after layout
    setTimeout(() => {
      const el = messagesContainer.value
      if (el) {
        el.scrollTop = el.scrollHeight
      }
    }, 50)
  })
}

onMounted(() => {
  scrollToBottom()
})

watch(() => props.messages, () => {
  scrollToBottom()
}, { deep: true })

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
    <!-- Decorative header -->
    <div class="messages-header">
      <div class="header-flourish left"></div>
      <span class="header-text">思想交流</span>
      <div class="header-flourish right"></div>
    </div>

    <!-- Empty state -->
    <div v-if="!hasMessages()" class="empty-state">
      <div class="empty-icon">
        <svg class="w-16 h-16 text-[var(--color-gold)] opacity-50" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
        </svg>
      </div>
      <h3 class="empty-title">思想的火花等待点燃</h3>
      <p class="empty-body">发送消息，开启与历史伟人的对话</p>
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
  min-height: 0;
  height: 100%;
  overflow-y: auto;
  padding: 1.5rem;
  display: flex;
  flex-direction: column;
  gap: 1rem;
  box-sizing: border-box;
}

.messages-header {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 1rem;
  padding: 0.5rem 0 1rem;
  opacity: 0.6;
}

.header-text {
  font-family: 'Playfair Display', serif;
  font-size: 0.75rem;
  text-transform: uppercase;
  letter-spacing: 0.2em;
  color: var(--color-text-secondary);
}

.header-flourish {
  width: 40px;
  height: 1px;
  background: linear-gradient(90deg, transparent, var(--color-gold), transparent);
}

.header-flourish.left {
  background: linear-gradient(90deg, transparent, var(--color-gold));
}

.header-flourish.right {
  background: linear-gradient(90deg, var(--color-gold), transparent);
}

.messages {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.empty-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 3rem 2rem;
}

.empty-icon {
  margin-bottom: 1.5rem;
  animation: float 3s ease-in-out infinite;
}

.empty-title {
  font-family: 'Playfair Display', serif;
  font-size: 1.25rem;
  font-weight: 500;
  color: var(--color-navy);
  margin: 0 0 0.75rem 0;
}

.empty-body {
  font-size: 0.95rem;
  color: var(--color-text-secondary);
  margin: 0;
  max-width: 280px;
}

.thinking-area {
  padding: 0.75rem 0;
}

@keyframes float {
  0%, 100% {
    transform: translateY(0);
  }
  50% {
    transform: translateY(-8px);
  }
}
</style>
