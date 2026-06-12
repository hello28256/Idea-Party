<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useRoomStore } from '@/stores/room'
import { useMessageStore } from '@/stores/message'

const props = defineProps<{
  show: boolean
  roomId: string
}>()

const emit = defineEmits<{
  close: []
}>()

const roomStore = useRoomStore()
const messageStore = useMessageStore()
const router = useRouter()

// Danger-zone confirm dialog state
type DangerAction = 'clear' | 'delete' | null
const confirmAction = ref<DangerAction>(null)
const dangerLoading = ref(false)
const dangerError = ref<string | null>(null)

// Reset confirm state every time the modal opens
watch(() => props.show, (isOpen) => {
  if (isOpen) {
    confirmAction.value = null
    dangerError.value = null
  }
})

function askConfirm(action: DangerAction) {
  confirmAction.value = action
}

function cancelConfirm() {
  if (dangerLoading.value) return
  confirmAction.value = null
}

async function confirmDangerous() {
  const action = confirmAction.value
  if (!action) return
  dangerLoading.value = true
  dangerError.value = null
  try {
    if (action === 'clear') {
      // Frontend-only: clear in-memory messages; DB rows remain
      messageStore.clearMessages(props.roomId)
    } else if (action === 'delete') {
      await roomStore.deleteRoom(props.roomId)
      emit('close')
      router.push('/rooms')
      return
    }
    confirmAction.value = null
  } catch (e) {
    dangerError.value = e instanceof Error ? e.message : '操作失败'
  } finally {
    dangerLoading.value = false
  }
}
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div
        v-if="show"
        class="fixed inset-0 z-50 flex items-center justify-center px-4"
        @click.self="emit('close')"
      >
        <!-- Backdrop -->
        <div class="rsm-backdrop" @click="emit('close')" />

        <!-- Modal -->
        <div class="rsm-modal">
          <!-- Header (aligned with CreateCharacterModal) -->
          <header class="rsm-header">
            <div>
              <h2 class="rsm-title">房间设置</h2>
              <p class="rsm-subtitle">删除聊天记录或聊天室，操作不可撤销</p>
            </div>
            <button
              class="rsm-close"
              @click="emit('close')"
              aria-label="关闭"
            >
              <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </header>

          <!-- Body -->
          <div class="rsm-body">
            <!-- Error -->
            <div v-if="dangerError" class="rsm-error">{{ dangerError }}</div>

            <!-- Danger Zone -->
            <section class="rsm-danger">
              <h3 class="rsm-danger-title">危险操作</h3>
              <div class="rsm-action-list">
                <!-- Clear chat history -->
                <button
                  type="button"
                  class="rsm-action-card"
                  :disabled="dangerLoading"
                  @click="askConfirm('clear')"
                >
                  <div class="rsm-action-card-icon">
                    <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6M1 7h22M9 7V4a2 2 0 012-2h2a2 2 0 012 2v3" />
                    </svg>
                  </div>
                  <div class="rsm-action-card-body">
                    <div class="rsm-action-card-title">删除聊天记录</div>
                    <div class="rsm-action-card-desc">清空当前聊天室的所有消息</div>
                  </div>
                </button>

                <!-- Delete room (destructive) -->
                <button
                  type="button"
                  class="rsm-action-card rsm-action-card-danger"
                  :disabled="dangerLoading"
                  @click="askConfirm('delete')"
                >
                  <div class="rsm-action-card-icon">
                    <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 6h18M8 6V4a2 2 0 012-2h4a2 2 0 012 2v2m3 0v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6" />
                    </svg>
                  </div>
                  <div class="rsm-action-card-body">
                    <div class="rsm-action-card-title">删除聊天室</div>
                    <div class="rsm-action-card-desc">永久删除聊天室及配置 · 不可恢复</div>
                  </div>
                </button>
              </div>
            </section>
          </div>

          <!-- Confirm danger dialog (overlay inside modal) -->
          <div
            v-if="confirmAction"
            class="confirm-overlay"
            @click.self="cancelConfirm"
          >
            <div
              class="confirm-dialog"
              :class="{ 'confirm-dialog-danger': confirmAction === 'delete' }"
              role="alertdialog"
              :aria-labelledby="`confirm-title-${confirmAction}`"
            >
              <!-- Header -->
              <header class="confirm-header">
                <div class="confirm-icon-wrap">
                  <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path
                      stroke-linecap="round"
                      stroke-linejoin="round"
                      stroke-width="2"
                      d="M12 9v2m0 4h.01M5.07 19h13.86c1.54 0 2.5-1.67 1.73-3L13.73 4a2 2 0 00-3.46 0L3.34 16c-.77 1.33.19 3 1.73 3z"
                    />
                  </svg>
                </div>
                <h4 :id="`confirm-title-${confirmAction}`" class="confirm-title">
                  {{ confirmAction === 'clear' ? '清空聊天记录？' : '永久删除聊天室？' }}
                </h4>
              </header>

              <!-- Body -->
              <div class="confirm-body">
                <p class="confirm-message">
                  <template v-if="confirmAction === 'clear'">
                    此操作只会清空当前显示的消息。刷新或重新进入后会从服务器恢复，请放心。
                  </template>
                  <template v-else>
                    此操作会<strong class="confirm-emphasis">永久删除该聊天室及所有消息</strong>，且不可恢复。请确认是否继续。
                  </template>
                </p>
              </div>

              <!-- Footer -->
              <footer class="confirm-footer">
                <button
                  type="button"
                  class="confirm-btn confirm-btn-cancel"
                  :disabled="dangerLoading"
                  @click="cancelConfirm"
                >
                  取消
                </button>
                <button
                  type="button"
                  class="confirm-btn"
                  :class="confirmAction === 'delete' ? 'confirm-btn-danger' : 'confirm-btn-primary'"
                  :disabled="dangerLoading"
                  @click="confirmDangerous"
                >
                  {{ dangerLoading ? '处理中…' : (confirmAction === 'delete' ? '永久删除' : '确认清空') }}
                </button>
              </footer>
            </div>
          </div>

          <!-- Footer -->
          <footer class="rsm-footer">
            <button
              type="button"
              class="rsm-btn rsm-btn-cancel"
              @click="emit('close')"
            >
              关闭
            </button>
          </footer>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.3s ease;
}

.modal-enter-active .rsm-modal,
.modal-leave-active .rsm-modal {
  transition: transform 0.3s ease, opacity 0.3s ease;
}

.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}

.modal-enter-from .rsm-modal,
.modal-leave-to .rsm-modal {
  transform: scale(0.95) translateY(10px);
  opacity: 0;
}

/* ====== Confirm danger dialog (overlay inside modal) ====== */
.confirm-overlay {
  --overlay-bg: rgba(15, 23, 42, 0.45);
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
  --btn-primary-bg: #0f172a;
  --btn-primary-text: #ffffff;
  --btn-secondary-bg: #F1F5F9;
  --btn-secondary-border: #E2E8F0;
  --btn-secondary-text: #64748B;
  --icon-bg: rgba(234, 179, 8, 0.14);
  --icon-color: #b45309;
  --error-color: #dc2626;
  --emphasis: #dc2626;

  position: absolute;
  inset: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1.5rem;
  background: var(--overlay-bg) !important;
  backdrop-filter: none !important;
  -webkit-backdrop-filter: none !important;
  animation: confirm-fade 0.18s ease-out;
}

:deep(.dark) .confirm-overlay {
  --overlay-bg: rgba(15, 23, 42, 0.65);
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
  --btn-primary-bg: #f8fafc;
  --btn-primary-text: #0f172a;
  --btn-secondary-bg: #1e293b;
  --btn-secondary-border: rgba(71, 85, 105, 0.95);
  --btn-secondary-text: #f8fafc;
  --icon-bg: rgba(234, 179, 8, 0.20);
  --icon-color: #fbbf24;
  --error-color: #fca5a5;
  --emphasis: #fca5a5;
}

.confirm-dialog {
  width: min(420px, calc(100vw - 48px));
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
  animation: confirm-pop 0.22s cubic-bezier(0.16, 1, 0.3, 1);
}

.confirm-dialog-danger {
  border-color: rgba(220, 38, 38, 0.45) !important;
}

.confirm-header {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
  padding: 28px 32px 16px;
  background: var(--header-bg) !important;
  border-bottom: 1px solid var(--header-border) !important;
  text-align: center;
}

.confirm-icon-wrap {
  width: 52px;
  height: 52px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--icon-bg);
  color: var(--icon-color);
  border-radius: 999px;
}

.confirm-dialog-danger .confirm-icon-wrap {
  background: rgba(220, 38, 38, 0.12);
  color: var(--error-color);
}

:deep(.dark) .confirm-dialog-danger .confirm-icon-wrap {
  background: rgba(220, 38, 38, 0.22);
}

.confirm-title {
  font-size: 1.25rem;
  font-weight: 700;
  margin: 0;
  line-height: 1.35;
  color: var(--text-primary);
  letter-spacing: -0.01em;
}

.confirm-body {
  padding: 18px 32px 24px;
  background: var(--body-bg) !important;
  text-align: center;
}

.confirm-message {
  font-size: 0.9rem;
  line-height: 1.65;
  margin: 0;
  color: var(--text-secondary);
}

.confirm-emphasis {
  color: var(--emphasis);
  font-weight: 600;
}

.confirm-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  padding: 18px 32px;
  background: var(--footer-bg) !important;
  border-top: 1px solid var(--footer-border) !important;
}

.confirm-btn {
  height: 42px;
  padding: 0 20px;
  border-radius: 14px;
  font-size: 0.875rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s ease;
  border: 1px solid transparent;
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
  border-color: var(--btn-primary-bg);
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.18);
  font-weight: 700;
}

:deep(.dark) .confirm-btn-primary {
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.45);
}

.confirm-btn-primary:hover:not(:disabled) {
  opacity: 0.92;
}

.confirm-btn-danger {
  background: linear-gradient(135deg, #dc2626 0%, #b91c1c 100%);
  color: #ffffff;
  border-color: transparent;
  box-shadow: 0 8px 24px rgba(220, 38, 38, 0.30);
}

.confirm-btn-danger:hover:not(:disabled) {
  opacity: 0.92;
  transform: translateY(-1px);
  box-shadow: 0 12px 32px rgba(220, 38, 38, 0.40);
}

@keyframes confirm-fade {
  from { opacity: 0; }
  to { opacity: 1; }
}

@keyframes confirm-pop {
  from { opacity: 0; transform: scale(0.94) translateY(4px); }
  to { opacity: 1; transform: scale(1) translateY(0); }
}

/* ====== Main modal (aligned with CreateRoomModal) ====== */
.rsm-overlay {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: flex !important;
  align-items: center;
  justify-content: center;
  padding: 32px;
}

.rsm-backdrop {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.rsm-modal {
  /* CSS variables — moved here from .rsm-overlay so descendants (ActionCard, etc.)
     can resolve var(--tab-inactive-bg) and friends without needing a wrapper class. */
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
  --tab-active-bg: #0f172a;
  --tab-active-text: #ffffff;
  --tab-inactive-text: #64748b;
  --tab-inactive-bg: #f1f5f9;
  --btn-primary-bg: #0f172a;
  --btn-primary-text: #ffffff;
  --btn-secondary-bg: #F1F5F9;
  --btn-secondary-border: #E2E8F0;
  --btn-secondary-text: #64748B;
  --error-bg: rgba(254, 226, 226, 0.8);
  --error-border: #fecaca;
  --error-color: #dc2626;
  --danger-btn-bg: #f8fafc;
  --danger-btn-border: rgba(220, 38, 38, 0.30);
  --danger-btn-text: #dc2626;
  --danger-btn-hover-bg: rgba(220, 38, 38, 0.06);
  --danger-strong-bg: rgba(254, 242, 242, 0.8);
  --danger-strong-border: #fecaca;
  --danger-strong-text: #b91c1c;
  --danger-strong-hover-bg: rgba(220, 38, 38, 0.08);
  --danger-title-color: #dc2626;
  --close-hover-bg: rgba(148, 163, 184, 0.18);

  position: relative;
  width: min(520px, calc(100vw - 48px));
  max-height: min(640px, calc(100vh - 64px));
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
  animation: modal-pop 0.22s cubic-bezier(0.16, 1, 0.3, 1);
}

:deep(.dark) .rsm-modal {
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
  --tab-active-bg: #f8fafc;
  --tab-active-text: #0f172a;
  --tab-inactive-text: #94a3b8;
  --tab-inactive-bg: #1e293b;
  --btn-primary-bg: #f8fafc;
  --btn-primary-text: #0f172a;
  --btn-secondary-bg: #1e293b;
  --btn-secondary-border: rgba(71, 85, 105, 0.95);
  --btn-secondary-text: #f8fafc;
  --error-bg: rgba(127, 29, 29, 0.30);
  --error-border: rgba(220, 38, 38, 0.40);
  --error-color: #fca5a5;
  --danger-btn-bg: #1e293b;
  --danger-btn-border: rgba(220, 38, 38, 0.40);
  --danger-btn-text: #fca5a5;
  --danger-btn-hover-bg: rgba(220, 38, 38, 0.15);
  --danger-strong-bg: rgba(127, 29, 29, 0.20);
  --danger-strong-border: rgba(220, 38, 38, 0.40);
  --danger-strong-text: #fca5a5;
  --danger-strong-hover-bg: rgba(220, 38, 38, 0.25);
  --danger-title-color: #fca5a5;
  --close-hover-bg: rgba(255, 255, 255, 0.12);
}

.rsm-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 28px 32px 20px;
  background: var(--header-bg) !important;
  border-bottom: 1px solid var(--header-border) !important;
  flex-shrink: 0;
}

.rsm-title {
  font-size: 24px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0;
}

.rsm-subtitle {
  margin-top: 6px;
  font-size: 14px;
  color: var(--text-muted);
}

.rsm-close {
  width: 36px;
  height: 36px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 12px;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  transition: all 0.15s ease;
  flex-shrink: 0;
}

.rsm-close:hover {
  background: var(--close-hover-bg, rgba(15, 23, 42, 0.06));
  color: var(--text-primary);
}

:deep(.dark) .rsm-close:hover {
  background: var(--close-hover-bg, rgba(248, 250, 252, 0.10));
  color: #f1f5f9;
}

.rsm-body {
  flex: 1;
  overflow-y: auto;
  padding: 24px 32px 28px;
  background: var(--body-bg) !important;
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.rsm-error {
  padding: 12px 14px;
  background: var(--error-bg);
  border: 1px solid var(--error-border);
  border-radius: 12px;
  color: var(--error-color);
  font-size: 13px;
}

/* Danger Zone */
.rsm-danger {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.rsm-danger-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-muted);
  margin: 0;
  letter-spacing: 0.02em;
}

.rsm-action-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.rsm-action-card {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 14px 16px;
  background: var(--tab-inactive-bg);
  border: 1px solid #E2E8F0;
  border-radius: 12px;
  color: var(--text-primary);
  cursor: pointer;
  transition: all 0.15s ease;
  text-align: left;
  font-family: inherit;
}

:deep(.dark) .rsm-action-card {
  background: var(--input-bg);
  border-color: rgba(71, 85, 105, 0.85);
}

.rsm-action-card:hover:not(:disabled) {
  background: #E2E8F0;
  border-color: var(--text-muted);
}

:deep(.dark) .rsm-action-card:hover:not(:disabled) {
  background: #334155;
  border-color: var(--text-muted);
}

.rsm-action-card:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.rsm-action-card-danger {
  background: rgba(254, 226, 226, 0.45);
  border-color: rgba(220, 38, 38, 0.40);
}

.rsm-action-card-danger:hover:not(:disabled) {
  background: rgba(254, 226, 226, 0.85);
  border-color: rgba(220, 38, 38, 0.65);
}

:deep(.dark) .rsm-action-card-danger {
  background: rgba(127, 29, 29, 0.20);
  border-color: rgba(220, 38, 38, 0.50);
}

:deep(.dark) .rsm-action-card-danger:hover:not(:disabled) {
  background: rgba(127, 29, 29, 0.35);
  border-color: rgba(220, 38, 38, 0.75);
}

.rsm-action-card-icon {
  width: 36px;
  height: 36px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 10px;
  background: var(--tab-inactive-bg);
  color: var(--text-muted);
  flex-shrink: 0;
}

.rsm-action-card-danger .rsm-action-card-icon {
  background: rgba(220, 38, 38, 0.08);
  color: var(--error-color);
}

:deep(.dark) .rsm-action-card-danger .rsm-action-card-icon {
  background: rgba(220, 38, 38, 0.15);
}

.rsm-action-card-body {
  flex: 1;
  min-width: 0;
}

.rsm-action-card-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  line-height: 1.4;
}

.rsm-action-card-desc {
  margin-top: 2px;
  font-size: 12px;
  color: var(--text-muted);
  line-height: 1.5;
}

.rsm-action-card-danger .rsm-action-card-title {
  color: var(--error-color);
}

.rsm-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  padding: 18px 32px;
  background: var(--footer-bg) !important;
  border-top: 1px solid var(--footer-border) !important;
  flex-shrink: 0;
}

.rsm-btn {
  height: 42px;
  padding: 0 20px;
  border-radius: 14px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s ease;
  border: 1px solid transparent;
}

.rsm-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.rsm-btn-cancel {
  background: var(--btn-secondary-bg);
  border-color: var(--btn-secondary-border);
  color: var(--btn-secondary-text);
}

.rsm-btn-cancel:hover:not(:disabled) {
  background: #E2E8F0;
  border-color: #E2E8F0;
  color: var(--text-primary);
}

@keyframes modal-pop {
  from { opacity: 0; transform: scale(0.96) translateY(4px); }
  to { opacity: 1; transform: scale(1) translateY(0); }
}

@media (max-width: 640px) {
  .rsm-overlay {
    padding: 16px;
  }
  .rsm-header,
  .rsm-body,
  .rsm-footer {
    padding-left: 20px;
    padding-right: 20px;
  }
  .rsm-title {
    font-size: 20px;
  }
}
</style>
