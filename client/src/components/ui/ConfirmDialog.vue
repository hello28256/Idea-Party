<script setup lang="ts">
/**
 * 通用确认弹窗：用于"危险操作前最后确认"场景（删除、清空等）。
 * 替代 window.confirm()，风格与项目深色主题一致。
 *
 * 契约（事件式，非 Promise）：
 *   - 父组件持有 show 状态，受控开关
 *   - 用户点确认 → emit('confirm')，由父组件执行异步操作并自行管理 loading
 *   - 用户点取消 / 点遮罩 → emit('cancel')
 *   - 父组件应在异步完成后手动将 show 置 false（这是有意为之，便于在请求失败时仍保持弹窗打开）
 *
 * 之所以不返回 Promise：与 Vue 受控组件范式一致，避免父组件在 await 中途被
 * 弹窗关闭打断造成状态错乱；loading 由调用方控制可让错误处理更直观（toast + 保留弹窗）。
 */
// 通过 Teleport 挂到 body，避免父容器 transform/overflow 影响定位；
// 由调用方控制 show/danger/loading，本组件只负责 UI 与事件转发（不内置异步请求）。
import { watch, onUnmounted } from 'vue'

interface Props {
  // 受控开关：父组件持有 show 状态，便于在请求返回后异步关闭
  show: boolean
  title?: string
  message: string
  confirmText?: string
  cancelText?: string
  /** danger 模式：确定按钮变红色（用于删除等不可逆操作） */
  danger?: boolean
  // 提交中态：触发后由父组件置 true，期间禁用按钮并阻止重复 emit
  loading?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  title: '确认操作',
  confirmText: '确定',
  cancelText: '取消',
  danger: false,
  loading: false
})

const emit = defineEmits<{
  (e: 'confirm'): void
  (e: 'cancel'): void
}>()

// 守卫 loading：防止用户在异步请求未完成时多次点击，导致重复删除/重复请求
function handleConfirm() {
  if (props.loading) return
  emit('confirm')
}

function handleCancel() {
  if (props.loading) return
  emit('cancel')
}

// 打开时锁住背景滚动，关闭时恢复
// 原因：弹窗出现时长内容滚动会显得页面"穿透"，锁定可强化模态感；
// 通过监听 show 切换而非挂载时锁定，是为了支持"调用方控制显隐"的受控模式。
watch(() => props.show, (visible) => {
  document.body.style.overflow = visible ? 'hidden' : ''
})

// 兜底清理：组件被卸载（如父级 v-if 销毁）时若 show 仍为 true，需恢复 body 滚动，
// 否则会污染其他页面（这是一个历史踩过的坑）。
onUnmounted(() => {
  document.body.style.overflow = ''
})
</script>

<template>
  <Teleport to="body">
    <Transition name="confirm-fade">
      <!-- @click.self 仅在遮罩自身点击时关闭，避免点击 modal 内容冒泡误关闭 -->
      <div v-if="show" class="confirm-overlay" @click.self="handleCancel">
        <div class="confirm-modal" role="dialog" aria-modal="true">
          <header class="confirm-header">
            <h3 class="confirm-title">{{ title }}</h3>
          </header>
          <div class="confirm-body">
            <p class="confirm-message">{{ message }}</p>
          </div>
          <footer class="confirm-footer">
            <button
              type="button"
              class="confirm-btn confirm-btn-cancel"
              :disabled="loading"
              @click="handleCancel"
            >{{ cancelText }}</button>
            <button
              type="button"
              class="confirm-btn"
              :class="danger ? 'confirm-btn-danger' : 'confirm-btn-primary'"
              :disabled="loading"
              @click="handleConfirm"
            >{{ loading ? '处理中...' : confirmText }}</button>
          </footer>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
/* 颜色变量 —— 与项目 CreateCharacterModal / CharacterAddPanel 弹窗完全对齐 */
.confirm-overlay {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px;
  background: transparent;
}

.confirm-modal {
  /* 通过 CSS 变量集中管理配色，便于 :global(.dark) 主题切换时一次性覆盖 */
  --modal-bg: #ffffff;
  --modal-border: rgba(226, 232, 240, 0.95);
  --modal-shadow: 0 28px 90px rgba(15, 23, 42, 0.28);
  --text-primary: #0f172a;
  --text-secondary: rgba(71, 85, 105, 0.85);
  --text-muted: rgba(100, 116, 139, 0.8);
  --footer-border: rgba(226, 232, 240, 0.9);
  --header-border: rgba(226, 232, 240, 0.9);
  --btn-primary-bg: #0f172a;
  --btn-primary-text: #ffffff;
  --btn-secondary-bg: rgba(248, 250, 252, 0.72);
  --btn-secondary-border: rgba(203, 213, 225, 0.55);
  --btn-secondary-text: #334155;

  position: relative;
  width: min(440px, calc(100vw - 48px));
  max-height: min(560px, calc(100vh - 64px));
  display: flex;
  flex-direction: column;
  background: var(--modal-bg);
  border: 1px solid var(--modal-border);
  border-radius: 24px;
  box-shadow: var(--modal-shadow);
  overflow: hidden;
}

:global(.dark) .confirm-modal {
  --modal-bg: #0f172a;
  --modal-border: rgba(71, 85, 105, 0.85);
  --modal-shadow: 0 28px 90px rgba(0, 0, 0, 0.55);
  --text-primary: #f8fafc;
  --text-secondary: rgba(203, 213, 225, 0.72);
  --text-muted: rgba(148, 163, 184, 0.68);
  --footer-border: rgba(71, 85, 105, 0.85);
  --header-border: rgba(71, 85, 105, 0.85);
  --btn-primary-bg: #f8fafc;
  --btn-primary-text: #0f172a;
  --btn-secondary-bg: #1e293b;
  --btn-secondary-border: rgba(71, 85, 105, 0.95);
  --btn-secondary-text: #f8fafc;
}

/*** Header ***/
.confirm-header {
  padding: 28px 32px 8px;
}

.confirm-title {
  margin: 0;
  font-size: 24px;
  font-weight: 700;
  color: var(--text-primary);
}

/*** Body ***/
.confirm-body {
  padding: 0 32px 24px;
}

.confirm-message {
  margin: 0;
  font-size: 14px;
  line-height: 1.6;
  color: var(--text-secondary);
  white-space: pre-wrap;
  word-break: break-word;
}

/*** Footer ***/
.confirm-footer {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 12px;
  padding: 18px 32px;
  border-top: 1px solid var(--footer-border);
}

.confirm-btn {
  height: 42px;
  padding: 0 18px;
  border-radius: 14px;
  font-size: 14px;
  font-weight: 600;
  border: 1px solid transparent;
  cursor: pointer;
  transition: all 0.15s ease;
}

.confirm-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.confirm-btn-cancel {
  background: var(--btn-secondary-bg);
  border-color: var(--btn-secondary-border);
  color: var(--btn-secondary-text);
}
.confirm-btn-cancel:hover:not(:disabled) {
  border-color: var(--text-muted);
  color: var(--text-primary);
}

.confirm-btn-primary {
  background: var(--btn-primary-bg);
  color: var(--btn-primary-text);
  font-weight: 700;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.18);
}
.confirm-btn-primary:hover:not(:disabled) {
  opacity: 0.92;
}

/* 危险模式仍用主色（保持项目一致），如需红可加，但项目无此先例 */
/* 说明：danger 状态下复用 primary 样式（而不是使用红色）——
   保持与项目其它 Modal（CreateCharacterModal 等）的视觉语言一致；
   真实"危险感"由文案与按钮 loading 态传达，而非颜色，刻意克制以避免视觉噪音。 */

/* 过渡 */
.confirm-fade-enter-active,
.confirm-fade-leave-active {
  transition: opacity 0.18s ease;
}
.confirm-fade-enter-from,
.confirm-fade-leave-to {
  opacity: 0;
}
</style>
