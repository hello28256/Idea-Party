<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoomStore } from '@/stores/room'

interface Props {
  show: boolean
}

interface Emits {
  close: []
  created: [roomId: string]
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()

const roomStore = useRoomStore()

const name = ref('')
const topic = ref('')
const loading = ref(false)
const error = ref<string | null>(null)

watch(() => props.show, (newShow) => {
  if (!newShow) {
    name.value = ''
    topic.value = ''
    error.value = null
  }
})

async function handleSubmit() {
  if (!name.value.trim()) {
    error.value = '请输入聊天室名称'
    return
  }

  loading.value = true
  error.value = null

  try {
    const room = await roomStore.createRoom(name.value.trim(), topic.value.trim() || undefined)
    emit('created', room.id)
    emit('close')
  } catch (e) {
    error.value = e instanceof Error ? e.message : '创建失败'
  } finally {
    loading.value = false
  }
}

function handleClose() {
  emit('close')
}
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div
        v-if="show"
        class="room-modal-overlay"
        @click.self="handleClose"
      >
        <!-- Modal Container -->
        <div class="room-modal">
          <!-- Header -->
          <header class="room-modal-header">
            <div>
              <h2 class="room-modal-title">创建聊天室</h2>
              <p class="room-modal-subtitle">设置聊天室名称和主题，快速发起多角色讨论</p>
            </div>
            <button class="modal-close" @click="handleClose">
              <svg width="20" height="20" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </header>

          <!-- Body -->
          <div class="room-modal-body">
            <div class="room-form">
              <!-- Name -->
              <div class="form-group">
                <label class="form-label">
                  聊天室名称 <span class="required">*</span>
                </label>
                <input
                  v-model="name"
                  type="text"
                  placeholder="例如：哲学讨论群"
                  class="form-input"
                />
              </div>

              <!-- Topic -->
              <div class="form-group">
                <label class="form-label">主题（可选）</label>
                <textarea
                  v-model="topic"
                  rows="3"
                  placeholder="讨论什么话题？"
                  class="form-textarea"
                ></textarea>
              </div>

              <!-- Error -->
              <p v-if="error" class="form-error">{{ error }}</p>
            </div>
          </div>

          <!-- Footer -->
          <footer class="room-modal-footer">
            <div class="footer-actions">
              <button
                type="button"
                class="footer-cancel-btn"
                @click="handleClose"
                :disabled="loading"
              >
                取消
              </button>
              <button
                type="button"
                class="footer-submit-btn"
                @click="handleSubmit"
                :disabled="loading || !name.trim()"
              >
                {{ loading ? '创建中...' : '创建' }}
              </button>
            </div>
          </footer>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
/*** Light Mode Variables ***/
.room-modal-overlay {
  --overlay-bg: rgba(15, 23, 42, 0.06);
  --modal-bg: #ffffff;
  --modal-border: rgba(226, 232, 240, 0.95);
  --modal-shadow: 0 28px 90px rgba(15, 23, 42, 0.28);
  --header-bg: #ffffff;
  --header-border: rgba(226, 232, 240, 0.9);
  --footer-bg: #ffffff;
  --footer-border: rgba(226, 232, 240, 0.9);
  --body-bg: #ffffff;
  --text-primary: #0f172a;
  --text-secondary: rgba(71, 85, 105, 0.85);
  --text-muted: rgba(100, 116, 139, 0.8);
  --input-bg: #f8fafc;
  --input-border: rgba(203, 213, 225, 0.95);
  --input-focus-border: #0f172a;
  --input-shadow: 0 0 0 3px rgba(15, 23, 42, 0.08);
  --btn-primary-bg: #0f172a;
  --btn-primary-text: #ffffff;
  --btn-secondary-bg: rgba(248, 250, 252, 0.72);
  --btn-secondary-border: rgba(203, 213, 225, 0.55);
  --btn-secondary-text: #334155;
  --error-color: #dc2626;
  --close-hover-bg: rgba(148, 163, 184, 0.18);
}

/*** Dark Mode Variables ***/
.dark .room-modal-overlay {
  --overlay-bg: transparent;
  --modal-bg: #0f172a;
  --modal-border: rgba(71, 85, 105, 0.85);
  --modal-shadow: 0 28px 90px rgba(0, 0, 0, 0.55);
  --header-bg: #0f172a;
  --header-border: rgba(71, 85, 105, 0.85);
  --footer-bg: #0f172a;
  --footer-border: rgba(71, 85, 105, 0.85);
  --body-bg: #0f172a;
  --text-primary: #f8fafc;
  --text-secondary: rgba(203, 213, 225, 0.72);
  --text-muted: rgba(148, 163, 184, 0.68);
  --input-bg: #1e293b;
  --input-border: rgba(71, 85, 105, 0.95);
  --input-focus-border: #94a3b8;
  --input-shadow: 0 0 0 3px rgba(148, 163, 184, 0.16);
  --btn-primary-bg: #f8fafc;
  --btn-primary-text: #0f172a;
  --btn-secondary-bg: #1e293b;
  --btn-secondary-border: rgba(71, 85, 105, 0.95);
  --btn-secondary-text: #f8fafc;
  --error-color: #fca5a5;
  --close-hover-bg: rgba(255, 255, 255, 0.12);
}

/*** Overlay ***/
.room-modal-overlay {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px;
  background: rgba(0, 0, 0, 0.04) !important;
  backdrop-filter: none !important;
  -webkit-backdrop-filter: none !important;
}

/*** Modal Container ***/
.room-modal {
  position: relative;
  width: min(520px, calc(100vw - 48px));
  max-height: min(600px, calc(100vh - 64px));
  display: flex;
  flex-direction: column;
  background: var(--modal-bg) !important;
  color: var(--text-primary) !important;
  border: 1px solid var(--modal-border) !important;
  border-radius: 24px;
  box-shadow: var(--modal-shadow) !important;
  backdrop-filter: none !important;
  -webkit-backdrop-filter: none !important;
  overflow: hidden;
}

.room-modal::before,
.room-modal::after {
  display: none !important;
}

.room-modal-header,
.room-modal-body,
.room-modal-footer {
  position: relative;
  z-index: 1;
}

/*** Header ***/
.room-modal-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 28px 32px 20px;
  background: var(--header-bg) !important;
  border-bottom: 1px solid var(--header-border) !important;
}

.room-modal-title {
  font-size: 24px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0;
}

.room-modal-subtitle {
  margin-top: 6px;
  font-size: 14px;
  color: var(--text-muted);
}

.modal-close {
  width: 36px;
  height: 36px;
  border-radius: 12px;
  border: none;
  background: transparent;
  color: var(--text-muted);
  display: grid;
  place-items: center;
  cursor: pointer;
  transition: all 0.15s;
}

.modal-close:hover {
  background: var(--close-hover-bg);
  color: var(--text-primary);
}

/*** Body ***/
.room-modal-body {
  flex: 1;
  overflow-y: auto;
  padding: 24px 32px 28px;
  background: var(--body-bg) !important;
}

.room-form {
  display: flex;
  flex-direction: column;
  gap: 22px;
}

/*** Form Groups ***/
.form-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.form-label {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}

.form-label .required {
  color: var(--error-color);
  margin-left: 2px;
}

.form-error {
  font-size: 13px;
  color: var(--error-color);
}

/*** Inputs & Textareas ***/
.form-input,
.form-textarea {
  width: 100%;
  border-radius: 14px;
  border: 1px solid var(--input-border) !important;
  background: var(--input-bg) !important;
  color: var(--text-primary) !important;
  padding: 12px 14px;
  font-size: 14px;
  outline: none;
  transition: border-color 0.15s ease, box-shadow 0.15s ease, background 0.15s ease;
  backdrop-filter: none !important;
  -webkit-backdrop-filter: none !important;
}

.form-input::placeholder,
.form-textarea::placeholder {
  color: var(--text-muted);
}

.form-input:focus,
.form-textarea:focus {
  border-color: var(--input-focus-border) !important;
  box-shadow: var(--input-shadow) !important;
}

.form-textarea {
  min-height: 100px;
  resize: vertical;
  line-height: 1.6;
}

/*** Footer ***/
.room-modal-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 16px;
  padding: 18px 32px;
  background: var(--footer-bg) !important;
  border-top: 1px solid var(--footer-border) !important;
}

.footer-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.footer-cancel-btn {
  height: 42px;
  padding: 0 18px;
  border-radius: 14px;
  border: 1px solid var(--btn-secondary-border);
  background: var(--btn-secondary-bg);
  color: var(--btn-secondary-text);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s;
}

.footer-cancel-btn:hover {
  border-color: var(--text-muted);
  color: var(--text-primary);
}

.footer-cancel-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.footer-submit-btn {
  height: 42px;
  padding: 0 20px;
  border-radius: 14px;
  border: none;
  background: var(--btn-primary-bg);
  color: var(--btn-primary-text);
  font-size: 14px;
  font-weight: 700;
  cursor: pointer;
  transition: all 0.15s;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.18);
}

.footer-submit-btn:hover:not(:disabled) {
  opacity: 0.92;
}

.footer-submit-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/*** Transitions ***/
.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.3s ease;
}

.modal-enter-active .room-modal,
.modal-leave-active .room-modal {
  transition: transform 0.3s ease, opacity 0.3s ease;
}

.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}

.modal-enter-from .room-modal,
.modal-leave-to .room-modal {
  transform: scale(0.95) translateY(10px);
  opacity: 0;
}

/*** Responsive ***/
@media (max-width: 640px) {
  .room-modal-overlay {
    padding: 0;
    align-items: flex-end;
  }

  .room-modal {
    width: 100vw;
    max-height: 92vh;
    border-radius: 24px 24px 0 0;
  }

  .room-modal-header,
  .room-modal-body,
  .room-modal-footer {
    padding-left: 20px;
    padding-right: 20px;
  }
}

/*** Dark mode explicit overrides (force opaque card) ***/
.dark .room-modal-overlay {
  background: rgba(0, 0, 0, 0.08) !important;
  backdrop-filter: none !important;
  -webkit-backdrop-filter: none !important;
}

.dark .room-modal {
  background: #0f172a !important;
  color: #f8fafc !important;
  border-color: rgba(71, 85, 105, 0.85) !important;
  box-shadow: 0 28px 90px rgba(0, 0, 0, 0.55) !important;
  backdrop-filter: none !important;
  -webkit-backdrop-filter: none !important;
}

.dark .room-modal-header {
  background: #0f172a !important;
  border-bottom-color: rgba(71, 85, 105, 0.85) !important;
}

.dark .room-modal-body {
  background: #0f172a !important;
}

.dark .room-modal-footer {
  background: #0f172a !important;
  border-top-color: rgba(71, 85, 105, 0.85) !important;
}

.dark .form-input,
.dark .form-textarea {
  background: #1e293b !important;
  border-color: rgba(71, 85, 105, 0.95) !important;
  color: #f8fafc !important;
  backdrop-filter: none !important;
  -webkit-backdrop-filter: none !important;
}

.dark .form-input:focus,
.dark .form-textarea:focus {
  border-color: #94a3b8 !important;
  box-shadow: 0 0 0 3px rgba(148, 163, 184, 0.16) !important;
}

.dark .footer-submit-btn {
  background: #f8fafc !important;
  color: #0f172a !important;
}

.dark .footer-cancel-btn {
  background: #1e293b !important;
  border-color: rgba(71, 85, 105, 0.95) !important;
  color: #f8fafc !important;
}
</style>
