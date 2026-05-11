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
  gap: 0.875rem;
  padding: 1rem 1.25rem;
  background: linear-gradient(145deg, var(--color-ivory) 0%, var(--color-parchment) 100%);
  border-radius: 1.25rem;
  max-width: 82%;
  border: 1px solid rgba(224, 214, 200, 0.6);
  position: relative;
  transition: all 0.35s cubic-bezier(0.4, 0, 0.2, 1);
  animation: fadeInUp 0.4s ease-out;
  box-shadow:
    0 1px 3px rgba(44, 36, 22, 0.04),
    0 4px 12px rgba(201, 169, 98, 0.08);
}

.message-bubble::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  border-radius: 1.25rem;
  border: 1px solid rgba(201, 169, 98, 0.15);
  pointer-events: none;
}

.message-bubble:hover {
  transform: translateY(-2px) scale(1.005);
  box-shadow:
    0 2px 6px rgba(44, 36, 22, 0.06),
    0 8px 24px rgba(201, 169, 98, 0.12);
}

.message-bubble.own {
  background: linear-gradient(145deg, var(--color-navy-light) 0%, var(--color-navy) 100%);
  margin-left: auto;
  flex-direction: row-reverse;
  border-color: rgba(30, 42, 58, 0.3);
  box-shadow:
    0 1px 3px rgba(0, 0, 0, 0.15),
    0 4px 16px rgba(30, 42, 58, 0.2);
}

.message-bubble.own::before {
  border-color: rgba(201, 169, 98, 0.2);
}

.message-bubble.own:hover {
  transform: translateY(-2px) scale(1.005);
  box-shadow:
    0 2px 6px rgba(0, 0, 0, 0.2),
    0 8px 24px rgba(30, 42, 58, 0.25);
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
  color: rgba(255, 255, 255, 0.45);
}

.message-bubble.own .sender-role {
  background: rgba(201, 169, 98, 0.15);
  color: var(--color-gold);
}

.message-content {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
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
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--color-navy);
  letter-spacing: 0.01em;
}

.sender-role {
  font-size: 0.65rem;
  text-transform: uppercase;
  letter-spacing: 0.12em;
  font-weight: 500;
  color: var(--color-gold-dark);
  background: rgba(201, 169, 98, 0.12);
  padding: 0.2rem 0.55rem;
  border-radius: 999px;
  border: 1px solid rgba(201, 169, 98, 0.2);
}

.message-body {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.message-text {
  font-size: 0.975rem;
  line-height: 1.65;
  color: var(--color-text-primary);
  word-break: break-word;
  white-space: pre-wrap;
}

.message-time {
  font-size: 0.7rem;
  color: var(--color-text-muted);
  letter-spacing: 0.02em;
  margin-top: 0.25rem;
}

/* Elegant streaming dots animation */
.streaming-dots {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  padding: 0 0.25rem;
}

.streaming-dots .dot {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--color-gold) 0%, var(--color-gold-light) 100%);
  animation: elegantBlink 1.4s ease-in-out infinite;
  box-shadow: 0 0 6px rgba(201, 169, 98, 0.4);
}

.streaming-dots .dot:nth-child(2) {
  animation-delay: 0.2s;
  width: 5px;
  height: 5px;
}

.streaming-dots .dot:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes elegantBlink {
  0%, 100% {
    opacity: 0.25;
    transform: translateY(0) scale(0.85);
  }
  50% {
    opacity: 1;
    transform: translateY(-2px) scale(1);
  }
}

@keyframes fadeInUp {
  from {
    opacity: 0;
    transform: translateY(12px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>
