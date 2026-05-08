<script setup lang="ts">
import type { Message } from '../../types';

const props = defineProps<{
  message: Message;
}>();

function getDisplayName(): string {
  if (props.message.role === 'user') {
    return 'You';
  }
  return props.message.characterName || 'Unknown';
}

function getAvatar(): string | null {
  return props.message.characterAvatar || null;
}
</script>

<template>
  <div class="message-bubble" :class="{ user: message.role === 'user' }">
    <div class="avatar">
      <img v-if="getAvatar()" :src="getAvatar()!" :alt="getDisplayName()" />
      <div v-else class="avatar-placeholder">
        {{ getDisplayName().charAt(0) }}
      </div>
    </div>
    <div class="content">
      <div class="meta">
        <span class="name">{{ getDisplayName() }}</span>
        <span class="time">{{ new Date(message.createdAt).toLocaleTimeString() }}</span>
      </div>
      <p class="text">{{ message.content }}</p>
    </div>
  </div>
</template>

<style scoped>
.message-bubble {
  display: flex;
  gap: 0.75rem;
  padding: 0.75rem;
  border-radius: 8px;
  background: #f5f5f5;
  margin-bottom: 0.5rem;
}

.message-bubble.user {
  background: #e3f2fd;
  flex-direction: row-reverse;
}

.avatar {
  flex-shrink: 0;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  overflow: hidden;
}

.avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.avatar-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  font-weight: bold;
}

.content {
  flex: 1;
  min-width: 0;
}

.meta {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 0.25rem;
}

.name {
  font-weight: 600;
  color: #333;
}

.time {
  font-size: 0.75rem;
  color: #999;
}

.text {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
}

.message-bubble.user .meta {
  flex-direction: row-reverse;
}
</style>
