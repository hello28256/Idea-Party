<script setup lang="ts">
// 通用按钮 UI 原子组件
// 全站统一按钮外观与交互（提交/取消/危险/次级），封装 loading 态以避免每个调用方重复实现 disabled + spinner 联动。
interface Props {
  type?: 'button' | 'submit' | 'reset'
  variant?: 'primary' | 'secondary' | 'destructive' | 'outline'
  disabled?: boolean
  loading?: boolean
}

// withDefaults 给所有 prop 提供非空默认值，保证模板里直接使用 props.x 不会是 undefined
// 同时避免调用方必须显式传 type='button'（表单内不指定 type 会默认 submit，触发意外提交 —— 这里强制 type 默认 'button' 是有意为之）
const props = withDefaults(defineProps<Props>(), {
  type: 'button',
  variant: 'primary',
  disabled: false,
  loading: false
})

const emit = defineEmits<{
  click: [event: MouseEvent]
}>()

// 点击拦截：loading 期间也屏蔽 click
// 因为浏览器在 disabled=true 时不会派发 click 事件，但 loading 状态下我们并未真正禁用 disabled 属性（仍可被屏幕阅读器读为可点），
// 必须在 JS 层再做一次闸门，防止 loading 中重复触发副作用（例如重复提交表单）。
function handleClick(event: MouseEvent) {
  if (!props.disabled && !props.loading) {
    emit('click', event)
  }
}
</script>

<template>
  <button
    :type="type"
    :disabled="disabled || loading"
    :class="[
      'btn',
      `btn-${variant}`,
      { 'btn-loading': loading }
    ]"
    @click="handleClick"
  >
    <svg
      v-if="loading"
      class="spinner"
      xmlns="http://www.w3.org/2000/svg"
      fill="none"
      viewBox="0 0 24 24"
    >
      <circle
        class="opacity-25"
        cx="12"
        cy="12"
        r="10"
        stroke="currentColor"
        stroke-width="4"
      />
      <path
        class="opacity-75"
        fill="currentColor"
        d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
      />
    </svg>
    <slot />
  </button>
</template>

<style scoped>
.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  padding: 0.8rem 1.6rem;
  border-radius: 12px;
  font-weight: 500;
  font-size: 0.95rem;
  letter-spacing: 0.02em;
  transition: all 0.35s cubic-bezier(0.4, 0, 0.2, 1);
  position: relative;
  overflow: hidden;
  border: none;
  cursor: pointer;
}

.btn:focus-visible {
  outline: 2px solid var(--color-gold);
  outline-offset: 2px;
}

.btn-primary {
  background: linear-gradient(145deg, var(--color-navy) 0%, var(--color-navy-light) 100%);
  color: var(--color-gold-light);
  box-shadow:
    0 2px 4px rgba(30, 42, 58, 0.15),
    0 4px 12px rgba(30, 42, 58, 0.1);
  border: 1px solid rgba(201, 169, 98, 0.1);
}

.btn-primary::before {
  content: '';
  position: absolute;
  top: 0;
  left: -100%;
  width: 100%;
  height: 100%;
  background: linear-gradient(90deg, transparent, rgba(201, 169, 98, 0.15), transparent);
  transition: left 0.5s ease;
}

.btn-primary:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow:
    0 4px 8px rgba(30, 42, 58, 0.2),
    0 8px 24px rgba(30, 42, 58, 0.15);
}

.btn-primary:hover:not(:disabled)::before {
  left: 100%;
}

.btn-primary:active:not(:disabled) {
  transform: translateY(0);
  box-shadow: 0 2px 4px rgba(30, 42, 58, 0.15);
}

.btn-secondary {
  background: linear-gradient(145deg, var(--color-ivory) 0%, var(--color-parchment) 100%);
  color: var(--color-navy);
  border: 1px solid var(--color-border);
}

.btn-secondary:hover:not(:disabled) {
  background: linear-gradient(145deg, var(--color-parchment) 0%, var(--color-ivory) 100%);
  border-color: var(--color-gold);
  transform: translateY(-1px);
  box-shadow: 0 2px 8px rgba(201, 169, 98, 0.1);
}

.btn-destructive {
  background: var(--color-destructive);
  color: white;
}

.btn-destructive:hover:not(:disabled) {
  background: #8B2525;
  transform: translateY(-1px);
}

.btn-outline {
  background: transparent;
  color: var(--color-navy);
  border: 1.5px solid var(--color-border);
}

.btn-outline:hover:not(:disabled) {
  background: var(--color-ivory);
  border-color: var(--color-navy);
  transform: translateY(-1px);
}

.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  transform: none !important;
}

.btn-loading {
  cursor: wait;
}

.spinner {
  width: 18px;
  height: 18px;
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}
</style>
