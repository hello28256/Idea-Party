<script setup lang="ts">
import { ref, watch } from 'vue'

interface Props {
  show: boolean
}

interface Emits {
  (e: 'close'): void
  (e: 'confirm'): void
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()

const isLoading = ref(false)

watch(() => props.show, (newVal) => {
  if (!newVal) {
    isLoading.value = false
  }
})

function handleClose() {
  if (!isLoading.value) {
    emit('close')
  }
}

async function handleConfirm() {
  isLoading.value = true
  // Simulate a brief delay for better UX
  await new Promise(resolve => setTimeout(resolve, 300))
  emit('confirm')
}
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="show" class="modal-overlay" @click.self="handleClose">
        <div class="modal-container">
          <!-- Close Button -->
          <button class="close-btn" @click="handleClose" :disabled="isLoading">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="18" y1="6" x2="6" y2="18"/>
              <line x1="6" y1="6" x2="18" y2="18"/>
            </svg>
          </button>

          <!-- Icon -->
          <div class="modal-icon">
            <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <path d="M9 21H5a2 2 0 01-2-2V5a2 2 0 012-2h4"/>
              <polyline points="16,17 21,12 16,7"/>
              <line x1="21" y1="12" x2="9" y2="12"/>
            </svg>
          </div>

          <!-- Content -->
          <h2 class="modal-title">确认退出登录？</h2>
          <p class="modal-desc">退出后需要重新登录才能继续参与聊天室。</p>

          <!-- Actions -->
          <div class="modal-actions">
            <button class="btn-cancel" @click="handleClose" :disabled="isLoading">
              取消
            </button>
            <button class="btn-confirm" @click="handleConfirm" :disabled="isLoading">
              <span v-if="isLoading" class="loading-spinner"></span>
              <span v-else>退出登录</span>
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
  max-width: 420px;
  box-shadow: 0 28px 90px rgba(15, 23, 42, 0.28);
  text-align: center;
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
  width: 64px;
  height: 64px;
  margin: 0 auto 1.25rem;
  border-radius: 50%;
  background: #FEF2F2;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #EF4444;
}

.modal-title {
  font-size: 1.5rem;
  font-weight: 700;
  color: #0f172a;
  margin-bottom: 0.5rem;
}

.modal-desc {
  font-size: 0.9rem;
  color: #64748B;
  line-height: 1.5;
  margin-bottom: 1.5rem;
}

.modal-actions {
  display: flex;
  gap: 0.75rem;
}

.btn-cancel,
.btn-confirm {
  flex: 1;
  height: 42px;
  padding: 0 18px;
  border-radius: 14px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s ease;
  display: flex;
  align-items: center;
  justify-content: center;
}

.btn-cancel {
  background: #F1F5F9;
  border: 1px solid #E2E8F0;
  color: #64748B;
}

.btn-cancel:hover:not(:disabled) {
  background: #E2E8F0;
  color: #1E293B;
}

.btn-confirm {
  background: #0f172a;
  border: none;
  color: #ffffff;
  font-weight: 700;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.18);
}

.btn-confirm:hover:not(:disabled) {
  opacity: 0.92;
}

.btn-cancel:disabled,
.btn-confirm:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  transform: none;
}

.loading-spinner {
  width: 18px;
  height: 18px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-top-color: white;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* Transitions */
.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.3s ease;
}

.modal-enter-active .modal-container,
.modal-leave-active .modal-container {
  transition: transform 0.3s ease, opacity 0.3s ease;
}

.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}

.modal-enter-from .modal-container,
.modal-leave-to .modal-container {
  transform: scale(0.95) translateY(10px);
  opacity: 0;
}
</style>
