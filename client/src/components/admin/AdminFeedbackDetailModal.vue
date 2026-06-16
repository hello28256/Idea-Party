<script setup lang="ts">
import { ref, watch } from 'vue'
import { X, ThumbsUp, ThumbsDown, Copy, Eye, RefreshCw, Pencil } from 'lucide-vue-next'
import type { AdminFeedbackDetail } from '@/api/messageFeedback'
import { messageEventsApi, type MessageSignals } from '@/api/messageEvents'

interface Props {
  show: boolean
  detail: AdminFeedbackDetail | null
}

const props = defineProps<Props>()
defineEmits<{ (e: 'close'): void }>()

const CATEGORY_LABELS: Record<string, string> = {
  IRRELEVANT: '答非所问',
  INACCURATE: '事实不准',
  UNSAFE: '不安全/不当',
  STYLE_BAD: '风格差',
  OTHER: '其他'
}

const signals = ref<MessageSignals | null>(null)
const signalsLoading = ref(false)
const signalsError = ref<string | null>(null)

function formatTime(iso: string): string {
  if (!iso) return ''
  return new Date(iso).toLocaleString('zh-CN', { hour12: false })
}

function formatDwell(ms: number | null): string {
  if (ms == null) return '—'
  if (ms < 1000) return `${Math.round(ms)} ms`
  return `${(ms / 1000).toFixed(1)} s`
}

async function loadSignals(messageId: string) {
  signalsLoading.value = true
  signalsError.value = null
  try {
    signals.value = await messageEventsApi.adminSignals(messageId)
  } catch (e) {
    // 404 or other errors: show empty rather than blocking the detail view
    signals.value = null
    signalsError.value = e instanceof Error ? e.message : '加载隐式信号失败'
  } finally {
    signalsLoading.value = false
  }
}

watch(
  () => [props.show, props.detail?.messageId],
  ([show, mid]) => {
    if (show && typeof mid === 'string') {
      loadSignals(mid)
    } else {
      signals.value = null
    }
  }
)
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="show && detail" class="modal-overlay" @click.self="$emit('close')">
        <div class="modal-container">
          <button class="close-btn" @click="$emit('close')">
            <X :size="20" />
          </button>

          <header class="header">
            <span class="type-badge" :class="detail.type.toLowerCase()">
              <ThumbsUp v-if="detail.type === 'LIKE'" :size="14" />
              <ThumbsDown v-else :size="14" />
              {{ detail.type === 'LIKE' ? '点赞' : '点踩' }}
            </span>
            <h2 class="title">反馈详情</h2>
            <p class="meta">{{ formatTime(detail.createdAt) }} · {{ detail.displayName }} (@{{ detail.username }})</p>
          </header>

          <section class="block">
            <h3>原因分类</h3>
            <p>{{ detail.category ? CATEGORY_LABELS[detail.category] || detail.category : '—' }}</p>
          </section>

          <section v-if="detail.comment" class="block">
            <h3>用户备注</h3>
            <p class="comment">{{ detail.comment }}</p>
          </section>

          <section v-if="detail.userPrompt" class="block">
            <h3>用户提问</h3>
            <div class="prompt-card">
              <p class="prompt-content">{{ detail.userPrompt }}</p>
              <p class="prompt-meta">{{ formatTime(detail.userPromptAt!) }}</p>
            </div>
          </section>

          <section class="block">
            <h3>原消息</h3>
            <div class="message-card">
              <div class="message-meta">
                <span v-if="detail.characterName">{{ detail.characterName }}</span>
                <span v-else>未知</span>
                <span>·</span>
                <span>房间：{{ detail.roomName }}</span>
                <span>·</span>
                <span>{{ formatTime(detail.messageCreatedAt) }}</span>
              </div>
              <p class="message-content">{{ detail.messageContent }}</p>
            </div>
          </section>

          <section class="block">
            <h3>隐式信号</h3>
            <div v-if="signalsLoading" class="signals-empty">加载中...</div>
            <div v-else-if="!signals || (signals.rewriteCount + signals.copyCount + signals.readCompleteCount + signals.editCount === 0)" class="signals-empty">
              暂无隐式信号数据
            </div>
            <div v-else class="signals-grid">
              <div class="signal">
                <RefreshCw :size="14" />
                <span class="num">{{ signals.rewriteCount }}</span>
                <span class="label">重写</span>
              </div>
              <div class="signal">
                <Copy :size="14" />
                <span class="num">{{ signals.copyCount }}</span>
                <span class="label">复制</span>
              </div>
              <div class="signal">
                <Eye :size="14" />
                <span class="num">{{ signals.readCompleteCount }}</span>
                <span class="label">读完整</span>
              </div>
              <div class="signal">
                <Pencil :size="14" />
                <span class="num">{{ signals.editCount }}</span>
                <span class="label">编辑</span>
              </div>
              <div class="signal signal-dwell">
                <span class="num">{{ formatDwell(signals.averageDwellMs) }}</span>
                <span class="label">平均停留</span>
              </div>
              <div class="signal signal-users">
                <span class="num">{{ signals.uniqueUsers }}</span>
                <span class="label">独立用户</span>
              </div>
            </div>
            <p v-if="signalsError" class="signals-hint">（{{ signalsError }}）</p>
          </section>

          <footer class="footer">
            <span class="ids">反馈 ID：{{ detail.id }} · 消息 ID：{{ detail.messageId }}</span>
          </footer>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: 1rem;
}

.modal-container {
  position: relative;
  background: #FFFFFF;
  border-radius: 20px;
  padding: 1.75rem 2rem;
  width: 100%;
  max-width: 560px;
  max-height: 88vh;
  overflow-y: auto;
  box-shadow: 0 28px 90px rgba(15, 23, 42, 0.28);
}

.close-btn {
  position: absolute;
  top: 0.75rem;
  right: 0.75rem;
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: transparent;
  border: none;
  color: #94A3B8;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}
.close-btn:hover { background: #F1F5F9; color: #1E293B; }

.header { margin-bottom: 1.5rem; }
.title { font-size: 1.25rem; font-weight: 700; color: #0f172a; margin: 0.5rem 0 0.25rem; }
.meta { font-size: 0.8rem; color: #64748B; margin: 0; }

.type-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 0.75rem;
  font-weight: 600;
}
.type-badge.like { background: rgba(16, 185, 129, 0.1); color: #10B981; }
.type-badge.dislike { background: rgba(239, 68, 68, 0.1); color: #EF4444; }

.block { margin-bottom: 1.25rem; }
.block h3 {
  font-size: 0.75rem;
  font-weight: 600;
  color: #94A3B8;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  margin: 0 0 0.4rem;
}
.block p { font-size: 0.9rem; color: #1E293B; margin: 0; line-height: 1.6; }

.comment {
  background: #F8FAFC;
  padding: 10px 12px;
  border-radius: 10px;
  white-space: pre-wrap;
}

.message-card {
  background: #F8FAFC;
  border: 1px solid #E2E8F0;
  border-radius: 12px;
  padding: 12px 14px;
}

.prompt-card {
  background: #EEF2FF;
  border: 1px solid #C7D2FE;
  border-radius: 12px;
  padding: 12px 14px;
}
.prompt-content {
  font-size: 0.9rem;
  color: #1E293B;
  margin: 0 0 6px;
  white-space: pre-wrap;
  word-break: break-word;
}
.prompt-meta {
  font-size: 0.7rem;
  color: #6366F1;
  margin: 0;
  font-family: monospace;
}
.message-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 0.75rem;
  color: #64748B;
  margin-bottom: 8px;
}
.message-content {
  white-space: pre-wrap;
  word-break: break-word;
  color: #0f172a;
}

/* Implicit signals grid */
.signals-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
}
.signal {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  background: #F8FAFC;
  border: 1px solid #E2E8F0;
  border-radius: 10px;
  padding: 10px 6px;
  color: #475569;
}
.signal .num {
  font-size: 1.1rem;
  font-weight: 700;
  color: #0f172a;
}
.signal .label {
  font-size: 0.7rem;
  color: #64748B;
}
.signals-empty {
  font-size: 0.85rem;
  color: #94A3B8;
  padding: 6px 0;
}
.signals-hint {
  font-size: 0.7rem;
  color: #94A3B8;
  margin: 0.5rem 0 0;
}

.footer {
  border-top: 1px solid #E2E8F0;
  padding-top: 0.75rem;
  margin-top: 0.5rem;
}
.ids { font-size: 0.7rem; color: #94A3B8; font-family: monospace; }

.modal-enter-active, .modal-leave-active { transition: opacity 0.2s ease; }
.modal-enter-from, .modal-leave-to { opacity: 0; }

@media (prefers-color-scheme: dark) {
  .modal-container { background: #1f1f28; }
  .block h3 { color: #94a0b0; }
  .block p { color: #e8e8f0; }
  .title { color: #e8e8f0; }
  .message-card, .signal { background: #25252f; border-color: #2a2a35; }
  .message-content, .signal .num { color: #e8e8f0; }
  .comment { background: #25252f; color: #e8e8f0; }
  .footer { border-color: #2a2a35; }
  .close-btn:hover { background: #2a2a35; color: #e8e8f0; }
}
</style>
