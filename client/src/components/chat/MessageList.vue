<script setup lang="ts">
import { ref, watch, nextTick } from 'vue';
import MessageBubble from './MessageBubble.vue';
import type { Message } from '../../types';

const props = defineProps<{
  messages: Message[];
  isLoading: boolean;
}>();

const containerRef = ref<HTMLElement | null>(null);

function scrollToBottom() {
  if (containerRef.value) {
    containerRef.value.scrollTop = containerRef.value.scrollHeight;
  }
}

watch(
  () => props.messages.length,
  () => {
    nextTick(scrollToBottom);
  }
);
</script>

<template>
  <div class="message-list" ref="containerRef">
    <div v-if="isLoading" class="loading">Loading messages...</div>
    <div v-else-if="messages.length === 0" class="empty">
      No messages yet. Start the conversation!
    </div>
    <template v-else>
      <MessageBubble v-for="message in messages" :key="message.id" :message="message" />
    </template>
  </div>
</template>

<style scoped>
.message-list {
  flex: 1;
  overflow-y: auto;
  padding: 1rem;
  background: #fff;
}

.loading,
.empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #999;
}
</style>
