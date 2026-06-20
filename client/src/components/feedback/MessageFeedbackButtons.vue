<script setup lang="ts">
// 单条 AI 消息的点赞/点踩按钮组件。
// 点赞是「即点即生效」的乐观更新；点踩是「二次确认/补全原因」流程，
// 所以点赞直接 emit('change')，点踩要走 Modal 收集 category/comment 后再 emit。
// 父组件（MessageBubble）负责把反馈持久化到后端，本组件只反映当前反馈状态并发出用户意图。
import { computed } from 'vue'
import { ThumbsUp, ThumbsDown } from 'lucide-vue-next'
import type { MessageFeedbackPayload } from '@/composables/useSocket'

// 与 MessageFeedbackModal.vue / 后端 FeedbackCategory 枚举保持一致
// 维护说明：后端枚举新增/重命名时，本表必须同步，否则 hover 提示会回退到原始 key
const CATEGORY_LABELS: Record<string, string> = {
  IRRELEVANT: '答非所问',
  INACCURATE: '事实不准',
  UNSAFE: '不安全/不当',
  STYLE_BAD: '风格差',
  OTHER: '其他'
}

// feedback：父组件传入的当前反馈状态，null 表示无反馈
interface Props {
  feedback: MessageFeedbackPayload | null | undefined
}

// 'change'：点赞/取消点赞的最终结果（无 category/comment），父组件直接落库。
// 'open-modal'：点踩或修改已有点踩时触发，由父组件挂载 Modal 收集原因后再走 'change'。
interface Emits {
  (e: 'change', payload: MessageFeedbackPayload | null): void
  (e: 'open-modal', current: MessageFeedbackPayload | null): void
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()

// 从完整 feedback 中只取 type，避免父组件无意义重渲时带动整个 payload 触发响应式
const currentType = computed(() => props.feedback?.type ?? null)

// 已点 👎 时，鼠标悬停提示：差评原因 + 备注摘要
// 用原生 title 而非 tooltip 库：减少依赖、消息流中 hover 时也能立即看到原因摘要
const dislikeTitle = computed(() => {
  if (currentType.value !== 'DISLIKE') return '这条回复有问题'
  const category = props.feedback?.category
  const categoryLabel = category ? CATEGORY_LABELS[category] ?? category : null
  const comment = props.feedback?.comment?.trim()
  if (categoryLabel && comment) return `差评原因：${categoryLabel}\n备注：${comment}`
  if (categoryLabel) return `差评原因：${categoryLabel}`
  return '查看/修改反馈'
})

// 点赞 = 一次性原子动作：再点一次 = 取消；切到点踩时由父组件的 'open-modal' 路径接管
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

// 点踩必须走 Modal 收集原因/备注（合规 & 数据可用性需要），所以这里永远不直接 emit('change')
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
      :title="dislikeTitle"
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
