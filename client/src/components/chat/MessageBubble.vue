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

// Get avatar gradient based on character name
const avatarGradient = computed(() => {
  const gradients = [
    'linear-gradient(135deg, #C9A962 0%, #A68B4B 100%)',
    'linear-gradient(135deg, #722F37 0%, #5D2428 100%)',
    'linear-gradient(135deg, #1E2A3A 0%, #2D3E50 100%)',
    'linear-gradient(135deg, #277568 0%, #1D5A4E 100%)',
    'linear-gradient(135deg, #8B6914 0%, #6B5010 100%)',
  ]
  if (props.message.senderType === 'USER') {
    return gradients[0]
  }
  const hash = props.message.characterName?.charCodeAt(0) || 0
  return gradients[hash % gradients.length]
})
</script>

<template>
  <div class="message-bubble" :class="{ 'own': isOwn }">
    <Avatar
      v-if="!isOwn"
      :src="message.avatarUrl"
      :name="displayName"
      size="medium"
      :gradient="avatarGradient"
    />

    <div class="message-content">
      <div class="message-header">
        <span class="sender-name">{{ displayName }}</span>
        <span class="sender-role" v-if="message.senderType !== 'USER'">思想家</span>
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
      :gradient="avatarGradient"
    />
  </div>
</template>

<style scoped>
.message-bubble {
  display: flex;
  gap: 1rem;
  padding: 1rem 1.25rem;
  background: linear-gradient(145deg, var(--color-ivory), var(--color-parchment));
  border-radius: 1rem;
  max-width: 85%;
  border: 1px solid var(--color-border);
  position: relative;
  transition: all 0.3s ease;
  animation: fadeInUp 0.4s ease-out;
}

.message-bubble:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 20px rgba(201, 169, 98, 0.1);
}

.message-bubble.own {
  background: linear-gradient(145deg, var(--color-navy-light), var(--color-navy));
  margin-left: auto;
  flex-direction: row-reverse;
  border-color: transparent;
}

.message-bubble.own .message-content {
  align-items: flex-end;
}

.message-bubble.own .sender-name {
  color: var(--color-gold-light);
}

.message-bubble.own .message-text {
  color: rgba(255, 255, 255, 0.95);
}

.message-bubble.own .message-time {
  color: rgba(255, 255, 255, 0.5);
}

.message-content {
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
  min-width: 0;
  flex: 1;
}

.message-header {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.sender-name {
  font-family: 'Playfair Display', serif;
  font-size: 0.9rem;
  font-weight: 600;
  color: var(--color-navy);
}

.sender-role {
  font-size: 0.7rem;
  text-transform: uppercase;
  letter-spacing: 0.1em;
  color: var(--color-gold);
  background: rgba(201, 169, 98, 0.1);
  padding: 0.125rem 0.5rem;
  border-radius: 999px;
}

.message-body {
  display: flex;
  align-items: center;
  gap: 0.375rem;
}

.message-text {
  font-size: 1rem;
  line-height: 1.6;
  color: var(--color-text-primary);
  word-break: break-word;
  white-space: pre-wrap;
}

.message-time {
  font-size: 0.75rem;
  color: var(--color-text-muted);
}

/* Streaming dots animation */
.streaming-dots {
  display: inline-flex;
  gap: 3px;
}

.streaming-dots .dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background-color: var(--color-gold);
  animation: blink 1.2s ease-in-out infinite;
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
    transform: scale(0.8);
  }
  30% {
    opacity: 1;
    transform: scale(1);
  }
}

@keyframes fadeInUp {
  from {
    opacity: 0;
    transform: translateY(15px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>
