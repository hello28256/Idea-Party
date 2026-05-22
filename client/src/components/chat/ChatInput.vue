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
  // Allow Shift+Enter for new line, but don't send on plain Enter
  // Messages are sent only via the send button
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
    <p class="input-hint">点击发送按钮发送消息</p>
  </div>
</template>

<style scoped>
.chat-input {
  padding: 1rem 1.5rem 1.25rem;
  background: linear-gradient(180deg, var(--color-ivory) 0%, var(--color-cream) 100%);
  position: relative;
}

.chat-input::before {
  content: '';
  position: absolute;
  top: 0;
  left: 50%;
  transform: translateX(-50%);
  width: 60%;
  height: 1px;
  background: linear-gradient(90deg, transparent, rgba(201, 169, 98, 0.3), transparent);
}

.chat-input.disabled {
  opacity: 0.5;
}

.input-wrapper {
  display: flex;
  align-items: flex-end;
  gap: 0.875rem;
  border: 1px solid var(--color-border);
  border-radius: 20px;
  padding: 0.5rem 0.5rem 0.5rem 1.25rem;
  background: linear-gradient(145deg, var(--color-cream) 0%, var(--color-ivory) 100%);
  transition: all 0.35s cubic-bezier(0.4, 0, 0.2, 1);
  box-shadow:
    inset 0 2px 4px rgba(44, 36, 22, 0.02),
    0 1px 3px rgba(44, 36, 22, 0.04);
}

.input-wrapper:focus-within {
  border-color: var(--color-gold);
  box-shadow:
    0 0 0 3px rgba(201, 169, 98, 0.12),
    0 0 24px rgba(201, 169, 98, 0.08),
    inset 0 2px 4px rgba(44, 36, 22, 0.02);
}

textarea {
  flex: 1;
  border: none;
  outline: none;
  resize: none;
  font-size: 1rem;
  line-height: 1.6;
  padding: 0.5rem 0;
  background: transparent;
  max-height: 100px;
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
  width: 48px;
  height: 48px;
  border: none;
  border-radius: 14px;
  background: linear-gradient(145deg, var(--color-navy) 0%, var(--color-navy-light) 100%);
  color: var(--color-gold);
  cursor: pointer;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  flex-shrink: 0;
  position: relative;
  overflow: hidden;
}

.send-button::before {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(145deg, var(--color-gold) 0%, var(--color-gold-light) 100%);
  opacity: 0;
  transition: opacity 0.3s ease;
}

.send-button:hover:not(:disabled)::before {
  opacity: 1;
}

.send-button:hover:not(:disabled) {
  transform: scale(1.05);
  box-shadow:
    0 4px 16px rgba(30, 42, 58, 0.35),
    0 0 20px rgba(201, 169, 98, 0.2);
  color: var(--color-navy);
}

.send-button:active:not(:disabled) {
  transform: scale(0.95);
}

.send-button:disabled {
  background: var(--color-parchment);
  color: var(--color-text-muted);
  cursor: not-allowed;
}

.send-button :deep(svg) {
  position: relative;
  z-index: 1;
  transition: transform 0.3s ease;
}

.send-button:hover:not(:disabled) :deep(svg) {
  transform: translateX(1px);
}

.input-hint {
  text-align: center;
  font-size: 0.7rem;
  color: var(--color-text-muted);
  margin-top: 0.625rem;
  letter-spacing: 0.02em;
}
</style>
