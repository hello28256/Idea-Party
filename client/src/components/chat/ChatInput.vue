<script setup lang="ts">
import { ref, computed } from 'vue'
import { Send } from 'lucide-vue-next'

// ChatInput：聊天室底部的消息输入区。
// 对外只暴露「是否禁用」与「send 事件」两个契约：父组件（ChatRoom 等）通过 disabled 屏蔽
// AI 正在生成/未连上 WebSocket 的场景，发送时拿到已 trim 的纯文本，由父组件决定是普通消息
// 还是 Moderator 触发新一轮发言。

const props = withDefaults(defineProps<{
  disabled?: boolean
}>(), {
  disabled: false
})

const emit = defineEmits<{
  send: [content: string]
}>()

const content = ref('')
// textareaRef 同时承担两件事：响应式拿到 DOM（v-model 双向绑定需要），
// 以及在 autoResize 里手动改写 style.height —— 因此必须是非空的 HTMLTextAreaElement 引用。
const textareaRef = ref<HTMLTextAreaElement | null>(null)

// canSend 的 trim() 校验保证「全空白字符」不能发送，避免误触回车把空消息丢给后端。
const canSend = computed(() => {
  return content.value.trim().length > 0 && !props.disabled
})

// 处理键盘事件：拦截 IME 组合中的 Enter（让用户确认候选词），并实现回车发送 / Shift+回车换行。
function handleKeydown(event: KeyboardEvent) {
  // When a CJK IME (pinyin / kana / hangul) is composing, Enter is used to
  // confirm a candidate — not to send the message. Skip in that case so the
  // user can pick an English letter from the candidate list without losing
  // whatever they've already typed.
  if (event.isComposing || event.keyCode === 229) {
    return;
  }
  // Enter sends message, Shift+Enter allows new line
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    handleSend()
  }
}

// 发送主流程：canSend 兜底防双击 / 父组件外部触发；清空内容并重置高度，
// 避免下一次输入前还残留上一次的多行高度造成视觉跳动。
function handleSend() {
  if (!canSend.value) return

  emit('send', content.value.trim())
  content.value = ''

  // Reset textarea height
  if (textareaRef.value) {
    textareaRef.value.style.height = 'auto'
  }
}

// 输入自适应高度：先重置 auto 再读取 scrollHeight，是为了能正确收缩到更短的内容
// （如果不先归零，长文本切短时高度不会回退）。封顶约 4 行避免长消息把输入区顶穿屏幕。
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
    <div class="input-footer">
      <p class="disclaimer">内容由 AI 生成，仅供参考</p>
      <p class="input-hint">回车发送消息，Shift+Enter 换行</p>
    </div>
  </div>
</template>

<style scoped>
.chat-input {
  padding: 0.5rem 1.5rem 1.5rem;
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

.input-footer {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  height: 1.5rem;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
}

.disclaimer,
.input-hint {
  font-size: 0.625rem;
  color: var(--color-text-muted);
  margin: 0;
  line-height: 1;
}

.disclaimer {
  opacity: 0.6;
}

.input-hint {
  letter-spacing: 0.02em;
}
</style>
