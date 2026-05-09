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
        placeholder="输入消息，让思想碰撞..."
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
    <p class="input-hint">按 Enter 发送，Shift + Enter 换行</p>
  </div>
</template>

<style scoped>
.chat-input {
  padding: 1rem 1.25rem;
  background: linear-gradient(180deg, var(--color-ivory) 0%, var(--color-cream) 100%);
}

.chat-input.disabled {
  opacity: 0.5;
}

.input-wrapper {
  display: flex;
  align-items: flex-end;
  gap: 0.75rem;
  border: 1px solid var(--color-border);
  border-radius: 16px;
  padding: 0.5rem 0.5rem 0.5rem 1rem;
  background: var(--color-cream);
  transition: all 0.3s ease;
  box-shadow: inset 0 2px 4px rgba(0, 0, 0, 0.02);
}

.input-wrapper:focus-within {
  border-color: var(--color-gold);
  box-shadow: 0 0 0 3px rgba(201, 169, 98, 0.15), inset 0 2px 4px rgba(0, 0, 0, 0.02);
}

textarea {
  flex: 1;
  border: none;
  outline: none;
  resize: none;
  font-size: 1rem;
  line-height: 1.6;
  padding: 0.375rem 0;
  background: transparent;
  max-height: 96px;
  overflow-y: auto;
  color: var(--color-text-primary);
}

textarea::placeholder {
  color: var(--color-text-muted);
  font-style: italic;
}

.send-button {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border: none;
  border-radius: 12px;
  background: linear-gradient(135deg, var(--color-navy) 0%, var(--color-navy-light) 100%);
  color: var(--color-gold);
  cursor: pointer;
  transition: all 0.3s ease;
  flex-shrink: 0;
}

.send-button:hover:not(:disabled) {
  transform: scale(1.05);
  box-shadow: 0 4px 15px rgba(30, 42, 58, 0.3);
}

.send-button:active:not(:disabled) {
  transform: scale(0.95);
}

.send-button:disabled {
  background: var(--color-parchment);
  color: var(--color-text-muted);
  cursor: not-allowed;
}

.input-hint {
  text-align: center;
  font-size: 0.75rem;
  color: var(--color-text-muted);
  margin-top: 0.5rem;
}
</style>
