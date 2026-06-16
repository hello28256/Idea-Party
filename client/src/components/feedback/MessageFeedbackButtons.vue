<script setup lang="ts">
import { computed } from 'vue'
import { ThumbsUp, ThumbsDown } from 'lucide-vue-next'
import type { MessageFeedbackPayload } from '@/composables/useSocket'

interface Props {
  feedback: MessageFeedbackPayload | null | undefined
}

interface Emits {
  (e: 'change', payload: MessageFeedbackPayload | null): void
  (e: 'open-modal', current: MessageFeedbackPayload | null): void
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()

const currentType = computed(() => props.feedback?.type ?? null)

function handleLike() {
  if (currentType.value === 'LIKE') {
    // 取消
    emit('change', null)
  } else {
    emit('change', {
      type: 'LIKE',
      category: null,
      comment: null,
      createdAt: new Date().toISOString()
    })
  }
}

function handleDislike() {
  if (currentType.value === 'DISLIKE') {
    // 已点踩 → 让用户改原因/取消
    emit('open-modal', props.feedback ?? null)
  } else {
    emit('open-modal', {
      type: 'DISLIKE',
      category: null,
      comment: null,
      createdAt: new Date().toISOString()
    })
  }
}
</script>

<template>
  <div class="feedback-buttons">
    <button
      type="button"
      class="fb-btn"
      :class="{ active: currentType === 'LIKE' }"
      data-type="LIKE"
      :aria-pressed="currentType === 'LIKE'"
      :title="currentType === 'LIKE' ? '取消点赞' : '这条回复有用'"
      @click="handleLike"
    >
      <ThumbsUp :size="14" />
    </button>
    <button
      type="button"
      class="fb-btn"
      :class="{ active: currentType === 'DISLIKE' }"
      data-type="DISLIKE"
      :aria-pressed="currentType === 'DISLIKE'"
      :title="currentType === 'DISLIKE' ? '修改反馈' : '这条回复有问题'"
      @click="handleDislike"
    >
      <ThumbsDown :size="14" />
    </button>
  </div>
</template>

<style scoped>
.feedback-buttons {
  display: inline-flex;
  gap: 4px;
  align-items: center;
}

.fb-btn {
  width: 26px;
  height: 26px;
  border-radius: 8px;
  border: none;
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: all 0.15s ease;
  padding: 0;
}

.fb-btn:hover {
  background: rgba(0, 0, 0, 0.05);
  color: var(--color-text-primary);
}

.fb-btn.active[data-type="LIKE"] {
  color: #10B981;
  background: rgba(16, 185, 129, 0.1);
}

.fb-btn.active[data-type="DISLIKE"] {
  color: #EF4444;
  background: rgba(239, 68, 68, 0.1);
}

.fb-btn:focus-visible {
  outline: 2px solid var(--color-gold);
  outline-offset: 1px;
}

@media (prefers-color-scheme: dark) {
  .fb-btn {
    color: #6b6b75;
  }
  .fb-btn:hover {
    background: rgba(255, 255, 255, 0.08);
    color: #e8e8f0;
  }
}
</style>
