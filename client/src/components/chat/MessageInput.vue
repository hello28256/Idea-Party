<script setup lang="ts">
import { ref } from 'vue';

const emit = defineEmits<{
  send: [content: string];
}>();

const message = ref('');

function handleSubmit() {
  const content = message.value.trim();
  if (content) {
    emit('send', content);
    message.value = '';
  }
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    handleSubmit();
  }
}
</script>

<template>
  <div class="message-input">
    <textarea
      v-model="message"
      placeholder="Type your message..."
      rows="2"
      @keydown="handleKeydown"
    ></textarea>
    <button @click="handleSubmit" :disabled="!message.trim()">
      Send
    </button>
  </div>
</template>

<style scoped>
.message-input {
  display: flex;
  gap: 0.5rem;
  padding: 1rem;
  background: #f5f5f5;
  border-top: 1px solid #e0e0e0;
}

textarea {
  flex: 1;
  padding: 0.75rem;
  border: 1px solid #ddd;
  border-radius: 8px;
  resize: none;
  font-family: inherit;
  font-size: 1rem;
}

textarea:focus {
  outline: none;
  border-color: #667eea;
}

button {
  padding: 0.75rem 1.5rem;
  background: #667eea;
  color: white;
  border: none;
  border-radius: 8px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.2s;
}

button:hover:not(:disabled) {
  background: #5568d3;
}

button:disabled {
  background: #ccc;
  cursor: not-allowed;
}
</style>
