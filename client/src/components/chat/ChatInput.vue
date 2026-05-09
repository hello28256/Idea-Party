<script setup lang="ts">
import { ref, computed } from 'vue'
import { Send } from 'lucide-vue-next'

const props = withDefaults(defineProps<{
  disabled?: boolean
}>(), {
  disabled: false
})

const emit = defineEmits<{
  send: [content: string]
}>()

const content = ref('')
const textareaRef = ref<HTMLTextAreaElement | null>(null)

const canSend = computed(() => {
  return content.value.trim().length > 0 && !props.disabled
})

function handleKeydown(event: KeyboardEvent) {
  // Send on Enter (without Shift)
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    handleSend()
  }
}

function handleSend() {
  if (!canSend.value) return

  emit('send', content.value.trim())
  content.value = ''

  // Reset textarea height
  if (textareaRef.value) {
    textareaRef.value.style.height = 'auto'
  }
}

function autoResize() {
  if (textareaRef.value) {
    textareaRef.value.style.height = 'auto'
    // Max height approximately 4 lines
    const maxHeight = 96 // 4 * 24px line height
    textareaRef.value.style.height = Math.min(textareaRef.value.scrollHeight, maxHeight) + 'px'
  }
}
</script>

<template>
  <div class="chat-input" :class="{ disabled }">
    <div class="input-wrapper">
      <textarea
        ref="textareaRef"
        v-model="content"
        :disabled="disabled"
        placeholder="输入消息..."
        rows="1"
        @keydown="handleKeydown"
        @input="autoResize"
      ></textarea>

      <button
        class="send-button"
        :disabled="!canSend"
        @click="handleSend"
      >
        <Send :size="18" />
      </button>
    </div>
  </div>
</template>

<style scoped>
.chat-input {
  padding: 0.75rem;
  background-color: white;
  border-top: 1px solid #E5E7EB;
}

.chat-input.disabled {
  opacity: 0.5;
}

.input-wrapper {
  display: flex;
  align-items: flex-end;
  gap: 0.5rem;
  border: 1px solid #D1D5DB;
  border-radius: 0.5rem;
  padding: 0.5rem;
  background-color: white;
}

.input-wrapper:focus-within {
  border-color: #10B981;
  box-shadow: 0 0 0 2px rgba(16, 185, 129, 0.1);
}

textarea {
  flex: 1;
  border: none;
  outline: none;
  resize: none;
  font-size: 1rem;
  line-height: 1.5;
  padding: 0.25rem;
  background: transparent;
  max-height: 96px;
  overflow-y: auto;
}

textarea::placeholder {
  color: #9CA3AF;
}

.send-button {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border: none;
  border-radius: 0.375rem;
  background-color: #10B981;
  color: white;
  cursor: pointer;
  transition: background-color 0.2s;
  flex-shrink: 0;
}

.send-button:hover:not(:disabled) {
  background-color: #059669;
}

.send-button:disabled {
  background-color: #D1D5DB;
  cursor: not-allowed;
}
</style>
