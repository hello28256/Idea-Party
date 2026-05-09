<script setup lang="ts">
import { computed } from 'vue'
import Avatar from '@/components/ui/Avatar.vue'
import type { ChatMessage } from '@/composables/useSocket'

const props = defineProps<{
  message: ChatMessage
  isOwn?: boolean
}>()

const formattedTime = computed(() => {
  if (!props.message.createdAt) return ''
  const date = new Date(props.message.createdAt)
  return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
})

const displayName = computed(() => {
  if (props.message.senderType === 'USER') {
    return '你'
  }
  return props.message.characterName || '未知角色'
})

const isStreaming = computed(() => {
  return props.message.content.endsWith('...')
})
</script>

<template>
  <div class="message-bubble" :class="{ 'own': isOwn }">
    <Avatar
      v-if="!isOwn"
      :src="message.avatarUrl"
      :name="displayName"
      size="medium"
    />

    <div class="message-content">
      <div class="message-header">
        <span class="sender-name">{{ displayName }}</span>
      </div>
      <div class="message-body">
        <span class="message-text">{{ message.content }}</span>
        <span v-if="isStreaming" class="streaming-dots">
          <span class="dot"></span>
          <span class="dot"></span>
          <span class="dot"></span>
        </span>
      </div>
      <div class="message-time">{{ formattedTime }}</div>
    </div>

    <Avatar
      v-if="isOwn"
      :name="displayName"
      size="medium"
    />
  </div>
</template>

<style scoped>
.message-bubble {
  display: flex;
  gap: 0.75rem;
  padding: 0.75rem;
  background-color: #F0FDF4;
  border-radius: 0.75rem;
  max-width: 80%;
}

.message-bubble.own {
  background-color: rgba(16, 185, 129, 0.1);
  margin-left: auto;
  flex-direction: row-reverse;
}

.message-content {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  min-width: 0;
}

.message-header {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.sender-name {
  font-size: 0.875rem;
  font-weight: 500;
  color: #374151;
}

.message-body {
  display: flex;
  align-items: center;
  gap: 0.25rem;
}

.message-text {
  font-size: 1rem;
  color: #1F2937;
  word-break: break-word;
  white-space: pre-wrap;
}

.message-time {
  font-size: 0.75rem;
  color: #6B7280;
}

/* Streaming dots animation */
.streaming-dots {
  display: inline-flex;
  gap: 2px;
}

.streaming-dots .dot {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background-color: #10B981;
  animation: blink 1s ease-in-out infinite;
}

.streaming-dots .dot:nth-child(2) {
  animation-delay: 0.2s;
}

.streaming-dots .dot:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes blink {
  0%, 60%, 100% {
    opacity: 0.3;
  }
  30% {
    opacity: 1;
  }
}
</style>
