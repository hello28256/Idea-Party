<script setup lang="ts">
// 消息反馈弹窗：让用户为 AI 回复选择踩的原因并补充说明。
// 由 ChatMessage 气泡上的"踩"按钮触发，复用于"新增反馈"和"更新已有反馈"两种场景。
// 所有副作用都通过 emit 上交（提交 / 取消反馈 / 关闭），组件本身不直接调后端，便于单测和复用。
import { ref, watch } from 'vue'
import { ThumbsDown } from 'lucide-vue-next'
import type { MessageFeedbackPayload } from '@/composables/useSocket'

// 5 个固定原因，value 与后端 FeedbackCategory 枚举一一对应：调整顺序或新增项需要前后端同步。
const CATEGORIES: { value: string; label: string }[] = [
  { value: 'IRRELEVANT', label: '答非所问' },
  { value: 'INACCURATE', label: '事实不准' },
  { value: 'UNSAFE', label: '不安全/不当' },
  { value: 'STYLE_BAD', label: '风格差' },
  { value: 'OTHER', label: '其他' }
]

// current 为 null 表示新增反馈；非 null 表示编辑已有反馈，UI 据此切换「取消反馈」按钮和确认文案。
interface Props {
  show: boolean
  current: MessageFeedbackPayload | null
}

// submit 携带标准化后的 { category, comment }，由父组件决定走「新增」还是「更新」接口。
// cancel-feedback 用于撤销已存在的反馈记录；close 只关闭弹窗，不动服务端数据。
interface Emits {
  (e: 'close'): void
  (e: 'submit', payload: { category: string; comment: string | null }): void
  (e: 'cancel-feedback'): void
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()

const selectedCategory = ref<string>('')
const comment = ref<string>('')
const submitting = ref(false)

// 只在弹窗从隐藏变为打开时回填，避免关闭过程中残留状态污染下次打开的"新增反馈"场景。
watch(
  () => props.show,
  (open) => {
    if (open) {
      // 预填当前 feedback
      selectedCategory.value = props.current?.category ?? ''
      comment.value = props.current?.comment ?? ''
    }
  }
)

function pickCategory(value: string) {
  selectedCategory.value = value
}

// 提交反馈：未选原因直接放弃；trim 后的空串归一为 null，避免后端存到无意义空白。
// 注意：submitting 仅用于立刻锁住按钮防重复提交，真正的请求结果由父组件负责。
async function handleSubmit() {
  if (!selectedCategory.value) return
  submitting.value = true
  try {
    emit('submit', {
      category: selectedCategory.value,
      comment: comment.value.trim() || null
    })
  } finally {
    submitting.value = false
  }
}

// 正在提交时不允许关闭，否则会与父组件的请求生命周期脱钩，导致状态不一致。
function handleClose() {
  if (!submitting.value) emit('close')
}

// 撤销已有反馈与 handleSubmit 一样需要在请求进行中保持弹窗，避免 UI 与服务端状态错位。
function handleRemove() {
  if (!submitting.value) emit('cancel-feedback')
}
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="show" class="modal-overlay" @click.self="handleClose">
        <div class="modal-container">
          <button class="close-btn" @click="handleClose" :disabled="submitting">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="18" y1="6" x2="6" y2="18"/>
              <line x1="6" y1="6" x2="18" y2="18"/>
            </svg>
          </button>

          <div class="modal-icon">
            <ThumbsDown :size="28" />
          </div>

          <h2 class="modal-title">这条回复哪里有问题？</h2>
          <p class="modal-desc">选择原因（必选）并可补充说明，帮助我们改进回复质量。</p>

          <div class="category-grid">
            <button
              v-for="c in CATEGORIES"
              :key="c.value"
              type="button"
              class="chip"
              :class="{ active: selectedCategory === c.value }"
              :disabled="submitting"
              @click="pickCategory(c.value)"
            >
              {{ c.label }}
            </button>
          </div>

          <textarea
            v-model="comment"
            class="comment-input"
            :disabled="submitting"
            maxlength="1000"
            placeholder="补充说明（可选，1000 字以内）"
            rows="3"
          />

          <div class="modal-actions">
            <button
              v-if="current"
              type="button"
              class="btn-remove"
              :disabled="submitting"
              @click="handleRemove"
            >
              取消反馈
            </button>
            <button
              type="button"
              class="btn-cancel"
              :disabled="submitting"
              @click="handleClose"
            >
              返回
            </button>
            <button
              type="button"
              class="btn-confirm"
              :disabled="submitting || !selectedCategory"
              @click="handleSubmit"
            >
              {{ current ? '更新反馈' : '提交反馈' }}
            </button>
          </div>
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
  border: 1px solid rgba(226, 232, 240, 0.95);
  border-radius: 24px;
  padding: 2rem;
  width: 100%;
  max-width: 460px;
  box-shadow: 0 28px 90px rgba(15, 23, 42, 0.28);
  text-align: left;
}

.close-btn {
  position: absolute;
  top: 1rem;
  right: 1rem;
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
  transition: all 0.15s ease;
}

.close-btn:hover:not(:disabled) {
  background: #F1F5F9;
  color: #1E293B;
}

.close-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.modal-icon {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: #FEF2F2;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #EF4444;
  margin-bottom: 1rem;
}

.modal-title {
  font-size: 1.25rem;
  font-weight: 700;
  color: #0f172a;
  margin: 0 0 0.4rem;
}

.modal-desc {
  font-size: 0.85rem;
  color: #64748B;
  line-height: 1.5;
  margin-bottom: 1.25rem;
}

.category-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  margin-bottom: 1rem;
}

.chip {
  padding: 6px 14px;
  border-radius: 999px;
  background: #F1F5F9;
  border: 1px solid #E2E8F0;
  color: #475569;
  font-size: 0.85rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s ease;
}

.chip:hover:not(:disabled) {
  background: #E2E8F0;
}

.chip.active {
  background: #0f172a;
  color: #ffffff;
  border-color: #0f172a;
}

.chip:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.comment-input {
  width: 100%;
  padding: 10px 12px;
  border-radius: 12px;
  border: 1px solid #E2E8F0;
  background: #F8FAFC;
  font-family: inherit;
  font-size: 0.85rem;
  color: #0f172a;
  resize: vertical;
  min-height: 72px;
  margin-bottom: 1.25rem;
  box-sizing: border-box;
}

.comment-input:focus {
  outline: none;
  border-color: var(--color-gold);
  background: #ffffff;
}

.comment-input:disabled {
  opacity: 0.6;
}

.modal-actions {
  display: flex;
  gap: 0.5rem;
  justify-content: flex-end;
}

.btn-cancel,
.btn-confirm,
.btn-remove {
  height: 38px;
  padding: 0 16px;
  border-radius: 12px;
  font-size: 0.85rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s ease;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid transparent;
}

.btn-cancel {
  background: #F1F5F9;
  border-color: #E2E8F0;
  color: #475569;
}

.btn-cancel:hover:not(:disabled) {
  background: #E2E8F0;
  color: #1E293B;
}

.btn-confirm {
  background: #EF4444;
  color: #ffffff;
}

.btn-confirm:hover:not(:disabled) {
  background: #DC2626;
}

.btn-confirm:disabled {
  background: #FCA5A5;
  cursor: not-allowed;
}

.btn-remove {
  background: transparent;
  border-color: #E2E8F0;
  color: #94A3B8;
  margin-right: auto;
}

.btn-remove:hover:not(:disabled) {
  border-color: #EF4444;
  color: #EF4444;
}

.btn-cancel:disabled,
.btn-remove:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.25s ease;
}

.modal-enter-active .modal-container,
.modal-leave-active .modal-container {
  transition: transform 0.25s ease, opacity 0.25s ease;
}

.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}

.modal-enter-from .modal-container,
.modal-leave-to .modal-container {
  transform: scale(0.96) translateY(8px);
  opacity: 0;
}
</style>
