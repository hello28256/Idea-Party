<script setup lang="ts">
/**
 * 全局 Toast 组件：底部居中显示提示，2.5 秒后自动消失。
 * 状态由 useToast() composable 管理，本组件只负责渲染。
 */
import { useToasts } from '@/composables/useToast'

const toasts = useToasts()

function dismiss(id: number) {
  toasts.value = toasts.value.filter(t => t.id !== id)
}
</script>

<template>
  <Teleport to="body">
    <div class="toast-container">
      <TransitionGroup name="toast">
        <div
          v-for="t in toasts"
          :key="t.id"
          class="toast"
          :class="`toast-${t.type}`"
          @click="dismiss(t.id)"
        >
          <span class="toast-icon">
            <template v-if="t.type === 'success'">✓</template>
            <template v-else-if="t.type === 'error'">✕</template>
            <template v-else>ⓘ</template>
          </span>
          <span class="toast-message">{{ t.message }}</span>
        </div>
      </TransitionGroup>
    </div>
  </Teleport>
</template>

<style scoped>
.toast-container {
  position: fixed;
  bottom: 40px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 10000;
  display: flex;
  flex-direction: column;
  gap: 8px;
  pointer-events: none;
}

.toast {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 18px;
  border-radius: 12px;
  font-size: 14px;
  font-weight: 500;
  min-width: 200px;
  max-width: 480px;
  box-shadow: 0 12px 40px rgba(15, 23, 42, 0.25);
  pointer-events: auto;
  cursor: pointer;
  border: 1px solid;
  backdrop-filter: blur(8px);
}

.toast-success {
  background: rgba(16, 185, 129, 0.95);
  color: #ffffff;
  border-color: rgba(5, 150, 105, 0.6);
}

.toast-error {
  background: rgba(220, 38, 38, 0.95);
  color: #ffffff;
  border-color: rgba(185, 28, 28, 0.6);
}

.toast-info {
  background: rgba(15, 23, 42, 0.95);
  color: #f1f5f9;
  border-color: rgba(71, 85, 105, 0.6);
}

.toast-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.2);
  font-size: 13px;
  font-weight: 700;
  flex-shrink: 0;
}

.toast-message {
  flex: 1;
  line-height: 1.4;
}

/* 进入/离开动画 */
.toast-enter-active,
.toast-leave-active {
  transition: all 0.25s ease;
}
.toast-enter-from {
  opacity: 0;
  transform: translateY(20px);
}
.toast-leave-to {
  opacity: 0;
  transform: translateY(20px);
}
</style>
