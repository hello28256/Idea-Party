/**
 * 全局 Toast composable —— 模块级单例，所有组件共享同一个 ref。
 *
 * 用法：
 *   import { useToast } from '@/composables/useToast'
 *   const toast = useToast()
 *   toast.success('已删除角色')
 *   toast.error('删除失败：' + msg)
 */
import { ref } from 'vue'

type ToastType = 'success' | 'error' | 'info'

interface ToastItem {
  id: number
  type: ToastType
  message: string
}

const toasts = ref<ToastItem[]>([])
let nextId = 0

function push(type: ToastType, message: string, duration = 2500) {
  const id = ++nextId
  toasts.value.push({ id, type, message })
  setTimeout(() => {
    toasts.value = toasts.value.filter(t => t.id !== id)
  }, duration)
}

export function useToasts() {
  return toasts
}

export function useToast() {
  return {
    success: (msg: string) => push('success', msg),
    error: (msg: string) => push('error', msg, 3500),  // 错误提示停留更久
    info: (msg: string) => push('info', msg)
  }
}
