<script setup lang="ts">
// CustomScenarioModal：用户私有场景的"创建/编辑"弹窗。
//
// 设计要点（与 CreateCharacterModal 同模式）：
// - show: boolean 控制显隐（受控）；scenario prop 决定是创建还是编辑模式
// - 弹窗显示时（watch show=true）从 props.scenario 回填（编辑）或清空（创建）
// - 弹窗关闭时（watch show=false）重置所有 ref，避免下次打开残留
// - 提交走 scenarioStore.createUserScenario / updateUserScenario，统一错误处理
// - 表单校验：onSubmit 全字段校验（必填 + 长度 + 重名），不通过则阻止提交
//
// 字段集合与后端 UserScenarioRequest 一一对应：
//   emoji / title / description / characterName / userInputLabel / userInputPlaceholder / promptTemplate
// 不暴露给用户编辑的"硬编码行为字段"（mode/dynamicPrompt/requiresUserInput）由 store 内部补全。
import { ref, watch, computed } from 'vue'
import type { Scenario } from '@/stores/scenario'
import { useScenarioStore } from '@/stores/scenario'
import { useAuthStore } from '@/stores/auth'
import { useToast } from '@/composables/useToast'
import { userScenariosApi } from '@/api/scenarios'
import { EMOJI_CANDIDATES, isValidEmoji } from './emojiData'

interface Props {
  show: boolean
  // 编辑模式下携带要回填的场景；创建模式传 null/undefined
  scenario?: Scenario | null
}

const props = withDefaults(defineProps<Props>(), {
  scenario: null
})

// close：遮罩/关闭按钮触发
// saved：创建或编辑成功后抛出，携带最新场景（含 id）
const emit = defineEmits<{
  close: []
  saved: [scenario: Scenario]
}>()

const scenarioStore = useScenarioStore()
const authStore = useAuthStore()
const toast = useToast()

const isEditMode = computed(() => !!props.scenario?.id)

// ===== 表单状态 =====
// 与后端 UserScenarioRequest 一一对应：
// 实际只让用户填：emoji / title / description / promptTemplate
// 后端必填的 characterName 自动从 title 派生（保持后端契约不破）
// 原本独立的 userInputLabel / userInputPlaceholder 暂不对用户开放（设计简化）
const emoji = ref('')
const title = ref('')
const description = ref('')
const promptTemplate = ref('')
// 角色名 = 标题（保证后端 UserScenarioRequest.characterName 必填字段有值）
// 编辑模式允许用户保留原 characterName（不入表单，仅在提交时回填）
const fallbackCharacterName = ref('')

// 自定义 emoji 输入 vs 候选网格点击
const showCustomEmojiInput = ref(false)

// 加载与错误
const loading = ref(false)
const error = ref<string | null>(null)
// AI 自动生成 prompt 状态
const generatingPrompt = ref(false)

// 字段级错误（用于 inline 校验提示）
const fieldErrors = ref<Record<string, string>>({})

// 弹窗打开/关闭时同步状态：编辑模式回填，创建模式清空
watch(() => props.show, (newShow) => {
  if (newShow) {
    error.value = null
    fieldErrors.value = {}
    showCustomEmojiInput.value = false
    if (isEditMode.value && props.scenario) {
      emoji.value = props.scenario.emoji || ''
      title.value = props.scenario.title || ''
      description.value = props.scenario.description || ''
      // 编辑模式：保留原 characterName（用户即使改了 title 也不影响原角色名）
      fallbackCharacterName.value = props.scenario.characterName || ''
      promptTemplate.value = props.scenario.promptTemplate || ''
      // 如果 emoji 不在候选列表里，自动展示自定义输入
      if (emoji.value && !EMOJI_CANDIDATES.includes(emoji.value)) {
        showCustomEmojiInput.value = true
      }
    } else {
      emoji.value = '💡'
      title.value = ''
      description.value = ''
      promptTemplate.value = ''
      fallbackCharacterName.value = ''
    }
  }
}, { immediate: true })

// promptTemplate 字符数：仅用于 UI 提示，> 1800 时给出黄色提醒（避免撞 2000 上限）
const promptLength = computed(() => promptTemplate.value.length)

// ===== 校验 =====

function validateAll(): boolean {
  const errors: Record<string, string> = {}

  if (!isValidEmoji(emoji.value)) {
    errors.emoji = '请选择一个图标'
  }
  if (title.value.trim().length < 2) {
    errors.title = '标题至少 2 字符'
  }
  if (description.value.trim().length < 1) {
    errors.description = '请输入描述'
  }
  if (promptTemplate.value.trim().length < 1) {
    errors.promptTemplate = '请输入系统提示词'
  }

  // 重名校验（仅创建模式 + 标题非空）
  if (!isEditMode.value && title.value.trim().length >= 2) {
    if (scenarioStore.hasDuplicateTitle(title.value.trim())) {
      errors.title = '你已经创建过这个标题的场景了'
    }
  }

  fieldErrors.value = errors
  return Object.keys(errors).length === 0
}

// ===== 提交 =====

async function handleSubmit() {
  if (!validateAll()) {
    error.value = '请检查表单字段'
    return
  }
  if (!authStore.user) {
    error.value = '请先登录'
    return
  }

  loading.value = true
  error.value = null

  // characterName 派生：编辑模式保留原值，新建模式用 title 作为默认
  // 后端 UserScenarioRequest.characterName 必填，必须传一个非空字符串
  const resolvedCharacterName = isEditMode.value
    ? (fallbackCharacterName.value || title.value.trim())
    : title.value.trim()

  const req = {
    emoji: emoji.value,
    title: title.value.trim(),
    description: description.value.trim(),
    characterName: resolvedCharacterName,
    promptTemplate: promptTemplate.value.trim()
  }

  try {
    let result: Scenario | null = null
    if (isEditMode.value && props.scenario) {
      result = await scenarioStore.updateUserScenario(props.scenario.id, req)
      if (result) {
        toast.success(`已更新场景「${result.title}」`)
        emit('saved', result)
        emit('close')
      } else {
        error.value = scenarioStore.error || '更新失败'
      }
    } else {
      result = await scenarioStore.createUserScenario(req)
      if (result) {
        toast.success(`已创建场景「${result.title}」`)
        emit('saved', result)
        emit('close')
      } else {
        error.value = scenarioStore.error || '创建失败'
      }
    }
  } catch (e: any) {
    console.error('[CustomScenarioModal] Error:', e)
    error.value = e.response?.data?.message || e.message || '操作失败'
  } finally {
    loading.value = false
  }
}

function handleClose() {
  emit('close')
}

// 点击 emoji 候选
function selectEmoji(e: string) {
  emoji.value = e
  showCustomEmojiInput.value = false
  // 清除字段错误
  if (fieldErrors.value.emoji) {
    delete fieldErrors.value.emoji
  }
}

/**
 * AI 自动生成 system prompt 模板。
 * 触发条件：用户至少填了「标题」或「描述」其中一个。
 * 行为：调后端 userScenariosApi.generatePrompt（复用 CharacterService 的联网检索 + LLM 合成能力），
 * 把返回的 prompt 填入 promptTemplate textarea，用户可继续微调后保存。
 * 失败时由后端 fallback 兜底，前端不会收到 500——仅在网络异常时 toast 错误。
 */
async function handleGeneratePrompt() {
  if (!title.value.trim() && !description.value.trim()) {
    error.value = '请先填写标题或描述'
    return
  }
  generatingPrompt.value = true
  error.value = null
  try {
    const response = await userScenariosApi.generatePrompt({
      name: title.value.trim() || '自定义场景',
      description: description.value.trim() || undefined
    })
    const generated = response.data?.prompt
    if (generated) {
      promptTemplate.value = generated
      toast.success('已生成 prompt，可继续微调')
      // 清除字段错误
      if (fieldErrors.value.promptTemplate) {
        delete fieldErrors.value.promptTemplate
      }
    } else {
      error.value = '生成失败：返回为空'
    }
  } catch (e: any) {
    console.error('[CustomScenarioModal] generatePrompt failed:', e)
    error.value = e.response?.data?.message || e.message || '生成失败，请稍后重试'
  } finally {
    generatingPrompt.value = false
  }
}
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="show" class="custom-scenario-modal-overlay" @click.self="handleClose">
        <div class="custom-scenario-modal">
          <header class="custom-scenario-modal-header">
            <h2 class="custom-scenario-modal-title">
              {{ isEditMode ? '编辑场景' : '自定义场景' }}
            </h2>
            <button class="modal-close" type="button" :disabled="loading" @click="handleClose">×</button>
          </header>

          <div class="custom-scenario-modal-body">
            <form @submit.prevent="handleSubmit">
              <!-- emoji 选择器 -->
              <div class="form-group">
                <label class="form-label required">图标</label>
                <div class="emoji-picker">
                  <div v-if="!showCustomEmojiInput" class="emoji-grid">
                    <button
                      v-for="e in EMOJI_CANDIDATES"
                      :key="e"
                      type="button"
                      class="emoji-option"
                      :class="{ 'is-selected': emoji === e }"
                      @click="selectEmoji(e)"
                    >{{ e }}</button>
                  </div>
                  <div v-else class="emoji-custom">
                    <input
                      v-model="emoji"
                      type="text"
                      class="form-input"
                      placeholder="输入 emoji（如 🎤）"
                      maxlength="8"
                    />
                  </div>
                  <button
                    type="button"
                    class="emoji-toggle"
                    @click="showCustomEmojiInput = !showCustomEmojiInput"
                  >
                    {{ showCustomEmojiInput ? '选择候选' : '自定义' }}
                  </button>
                </div>
                <p v-if="fieldErrors.emoji" class="form-error">{{ fieldErrors.emoji }}</p>
              </div>

              <!-- 标题 -->
              <div class="form-group">
                <label class="form-label required">标题</label>
                <input
                  v-model="title"
                  type="text"
                  class="form-input"
                  placeholder="如：客户谈判 / 读书会"
                />
                <p v-if="fieldErrors.title" class="form-error">{{ fieldErrors.title }}</p>
              </div>

              <!-- 描述 -->
              <div class="form-group">
                <label class="form-label required">一句话描述</label>
                <textarea
                  v-model="description"
                  class="form-textarea"
                  placeholder="如：和一位 B 端采购总监，演练 5 步合同谈判流程"
                  rows="2"
                ></textarea>
                <p v-if="fieldErrors.description" class="form-error">{{ fieldErrors.description }}</p>
              </div>

              <!-- 提示词模板 -->
              <div class="form-group">
                <div class="prompt-label-row">
                  <label class="form-label">系统提示词（system prompt）</label>
                  <button
                    type="button"
                    class="btn-ai-generate"
                    :disabled="generatingPrompt || loading"
                    @click="handleGeneratePrompt"
                  >
                    <span v-if="generatingPrompt">生成中…</span>
                    <span v-else>✨ AI 自动生成</span>
                  </button>
                </div>
                <textarea
                  v-model="promptTemplate"
                  class="form-textarea form-textarea-tall"
                  placeholder="可以点上方「✨ AI 自动生成」自动合成；也可以直接手写。&#10;&#10;例如：&#10;你扮演一位...&#10;&#10;【对话流程】&#10;1. ...&#10;2. ...&#10;&#10;【风格】&#10;..."
                  rows="10"
                  maxlength="2000"
                ></textarea>
                <div class="prompt-meta">
                  <span :class="{ 'is-warn': promptLength > 1800 }">{{ promptLength }} / 2000 字符</span>
                </div>
                <p v-if="fieldErrors.promptTemplate" class="form-error">{{ fieldErrors.promptTemplate }}</p>
              </div>
            </form>

            <p v-if="error" class="form-error form-error-global">{{ error }}</p>
          </div>

          <footer class="custom-scenario-modal-footer">
            <button
              type="button"
              class="btn btn-secondary"
              :disabled="loading"
              @click="handleClose"
            >取消</button>
            <button
              type="button"
              class="btn btn-primary"
              :disabled="loading"
              @click="handleSubmit"
            >
              {{ loading ? '保存中…' : (isEditMode ? '保存' : '创建') }}
            </button>
          </footer>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
/* ===== 弹窗骨架（与 CreateRoomModal 风格对齐：圆角 24px / 渐变阴影 / 深色模式） ===== */

.custom-scenario-modal-overlay {
  position: fixed;
  inset: 0;
  z-index: 1000;
  background: var(--overlay-bg, rgba(15, 23, 42, 0.55));
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1rem;
}

.custom-scenario-modal {
  background: var(--modal-bg, #ffffff);
  border: 1px solid var(--modal-border, rgba(15, 23, 42, 0.08));
  border-radius: 24px;
  width: 100%;
  max-width: 640px;
  max-height: 90vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 24px 60px rgba(15, 23, 42, 0.18);
  overflow: hidden;
}

.custom-scenario-modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 1.25rem 1.5rem;
  border-bottom: 1px solid var(--modal-border, rgba(15, 23, 42, 0.08));
}

.custom-scenario-modal-title {
  margin: 0;
  font-size: 1.15rem;
  font-weight: 600;
  color: var(--text-primary, #111827);
}

.modal-close {
  background: transparent;
  border: none;
  font-size: 1.5rem;
  line-height: 1;
  color: var(--text-secondary, #6b7280);
  cursor: pointer;
  width: 32px;
  height: 32px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background-color 0.15s ease;
}

.modal-close:hover {
  background: var(--input-bg, #f3f4f6);
  color: var(--text-primary, #111827);
}

.modal-close:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.custom-scenario-modal-body {
  padding: 1.25rem 1.5rem;
  overflow-y: auto;
  flex: 1;
}

.custom-scenario-modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 0.75rem;
  padding: 1rem 1.5rem;
  border-top: 1px solid var(--modal-border, rgba(15, 23, 42, 0.08));
}

/* ===== 表单 ===== */

.form-group {
  margin-bottom: 1rem;
}

.form-label {
  display: block;
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--text-primary, #111827);
  margin-bottom: 0.4rem;
}

.form-label.required::after {
  content: ' *';
  color: #ef4444;
}

.form-input,
.form-textarea {
  width: 100%;
  padding: 0.6rem 0.75rem;
  background: var(--input-bg, #ffffff);
  border: 1px solid var(--input-border, #d1d5db);
  border-radius: 10px;
  font-size: 0.9rem;
  color: var(--text-primary, #111827);
  font-family: inherit;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
  box-sizing: border-box;
}

.form-textarea {
  resize: vertical;
  min-height: 60px;
  line-height: 1.5;
}

.form-textarea-tall {
  min-height: 180px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.85rem;
}

.form-input:focus,
.form-textarea:focus {
  outline: none;
  border-color: var(--input-focus-border, #0f172a);
  box-shadow: 0 0 0 3px rgba(15, 23, 42, 0.08);
}

.form-input:disabled,
.form-textarea:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.form-hint {
  margin: 0.3rem 0 0;
  font-size: 0.78rem;
  color: var(--text-muted, #9ca3af);
}

.form-hint.is-warn {
  color: #d97706;
}

.form-error {
  margin: 0.3rem 0 0;
  font-size: 0.8rem;
  color: #ef4444;
}

.form-error-global {
  margin-top: 0.5rem;
  padding: 0.5rem 0.75rem;
  background: rgba(239, 68, 68, 0.08);
  border-radius: 8px;
}

/* ===== AI 生成按钮 ===== */

.prompt-label-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 0.4rem;
  gap: 0.5rem;
}

.prompt-label-row .form-label {
  margin-bottom: 0;
}

.btn-ai-generate {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  padding: 0.35rem 0.75rem;
  background: linear-gradient(135deg, #f0f9ff 0%, #e0f2fe 100%);
  border: 1px solid #7dd3fc;
  border-radius: 8px;
  color: #0369a1;
  font-size: 0.8rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s ease;
  font-family: inherit;
  white-space: nowrap;
}

.btn-ai-generate:hover:not(:disabled) {
  background: linear-gradient(135deg, #e0f2fe 0%, #bae6fd 100%);
  border-color: #0ea5e9;
  transform: translateY(-1px);
  box-shadow: 0 2px 8px rgba(14, 165, 233, 0.15);
}

.btn-ai-generate:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

:global(html[data-theme="dark"]) .btn-ai-generate,
:global(.dark) .btn-ai-generate {
  background: linear-gradient(135deg, #1e3a8a 0%, #1e40af 100%);
  border-color: #3b82f6;
  color: #bfdbfe;
}

:global(html[data-theme="dark"]) .btn-ai-generate:hover:not(:disabled),
:global(.dark) .btn-ai-generate:hover:not(:disabled) {
  background: linear-gradient(135deg, #1e40af 0%, #2563eb 100%);
  border-color: #60a5fa;
}

/* ===== Emoji 选择器 ===== */

.emoji-picker {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.emoji-grid {
  display: grid;
  grid-template-columns: repeat(8, 1fr);
  gap: 0.4rem;
  padding: 0.5rem;
  background: var(--input-bg, #f9fafb);
  border-radius: 10px;
  max-height: 160px;
  overflow-y: auto;
}

.emoji-option {
  background: transparent;
  border: 1px solid transparent;
  border-radius: 8px;
  font-size: 1.4rem;
  line-height: 1;
  padding: 0.4rem 0;
  cursor: pointer;
  transition: all 0.15s ease;
  font-family: inherit;
}

.emoji-option:hover {
  background: var(--card-bg, #ffffff);
  border-color: var(--input-border, #d1d5db);
}

.emoji-option.is-selected {
  background: var(--card-bg, #ffffff);
  border-color: #0f172a;
  transform: scale(1.05);
}

.emoji-custom {
  width: 100%;
}

.emoji-toggle {
  align-self: flex-start;
  background: transparent;
  border: none;
  color: var(--text-secondary, #6b7280);
  font-size: 0.8rem;
  cursor: pointer;
  padding: 0.2rem 0.4rem;
  font-family: inherit;
}

.emoji-toggle:hover {
  color: var(--text-primary, #111827);
  text-decoration: underline;
}

/* ===== 字符计数 ===== */

.prompt-meta {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 0.3rem;
  font-size: 0.78rem;
  color: var(--text-muted, #9ca3af);
}

.prompt-meta span.is-warn {
  color: #d97706;
}

/* ===== 按钮 ===== */

.btn {
  padding: 0.6rem 1.25rem;
  border-radius: 10px;
  font-size: 0.9rem;
  font-weight: 500;
  cursor: pointer;
  font-family: inherit;
  transition: all 0.15s ease;
  border: none;
}

.btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.btn-primary {
  background: linear-gradient(135deg, #18181b 0%, #3f3f46 100%);
  color: #ffffff;
}

.btn-primary:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(24, 24, 27, 0.25);
}

.btn-secondary {
  background: var(--input-bg, #f3f4f6);
  color: var(--text-primary, #111827);
}

.btn-secondary:hover:not(:disabled) {
  background: var(--card-bg, #e5e7eb);
}

/* ===== 过渡动画 ===== */

.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.2s ease;
}

.modal-enter-active .custom-scenario-modal,
.modal-leave-active .custom-scenario-modal {
  transition: transform 0.2s ease;
}

.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}

.modal-enter-from .custom-scenario-modal,
.modal-leave-to .custom-scenario-modal {
  transform: scale(0.96);
}

/* ===== 深色模式 ===== */

:global(html[data-theme="dark"]) .custom-scenario-modal,
:global(.dark) .custom-scenario-modal {
  background: #1e293b;
  border-color: rgba(255, 255, 255, 0.08);
}

:global(html[data-theme="dark"]) .custom-scenario-modal-title,
:global(.dark) .custom-scenario-modal-title {
  color: #f1f5f9;
}

:global(html[data-theme="dark"]) .form-input,
:global(html[data-theme="dark"]) .form-textarea,
:global(.dark) .form-input,
:global(.dark) .form-textarea {
  background: #0f172a;
  border-color: rgba(255, 255, 255, 0.12);
  color: #f1f5f9;
}

:global(html[data-theme="dark"]) .form-label,
:global(.dark) .form-label {
  color: #cbd5e1;
}

:global(html[data-theme="dark"]) .emoji-grid,
:global(.dark) .emoji-grid {
  background: #0f172a;
}

:global(html[data-theme="dark"]) .emoji-option,
:global(.dark) .emoji-option {
  color: #f1f5f9;
}

:global(html[data-theme="dark"]) .emoji-option.is-selected,
:global(.dark) .emoji-option.is-selected {
  background: #334155;
  border-color: #cbd5e1;
}

:global(html[data-theme="dark"]) .modal-close,
:global(.dark) .modal-close {
  color: #94a3b8;
}

:global(html[data-theme="dark"]) .modal-close:hover,
:global(.dark) .modal-close:hover {
  background: #334155;
  color: #f1f5f9;
}

:global(html[data-theme="dark"]) .btn-secondary,
:global(.dark) .btn-secondary {
  background: #334155;
  color: #f1f5f9;
}

:global(html[data-theme="dark"]) .btn-secondary:hover:not(:disabled),
:global(.dark) .btn-secondary:hover:not(:disabled) {
  background: #475569;
}

/* ===== 响应式 ===== */

@media (max-width: 640px) {
  .custom-scenario-modal {
    max-width: 100%;
    max-height: 95vh;
    border-radius: 20px 20px 0 0;
    align-self: flex-end;
  }

  .custom-scenario-modal-overlay {
    align-items: flex-end;
    padding: 0;
  }

  .emoji-grid {
    grid-template-columns: repeat(6, 1fr);
  }
}
</style>
