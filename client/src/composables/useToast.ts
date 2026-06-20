// 全局 Toast composable —— 模块级单例，跨组件共享一份消息队列。
// 调用方：任何业务组件可调 useToast().success/error/info；渲染由根组件 <ToastHost> 订阅 useToasts() 完成。

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

// 联合字面量类型：渲染层根据 type 决定配色/图标，避免在多处组件中散落 'success' 字符串导致拼写错误。
type ToastType = 'success' | 'error' | 'info'

// 单条 toast 的最小契约：id 用于定时器精准移除（防止并发移除错位），type 驱动样式，message 即展示文本。
interface ToastItem {
  id: number
  type: ToastType
  message: string
}

// 放在模块顶层而非 inject/provide：保证整个 App 共享一份队列，任何组件 push 都立刻被 <ToastHost> 看到。
const toasts = ref<ToastItem[]>([])
// 用模块级单调递增 id 替代随机数/时间戳：避免极端情况下（如同一毫秒内多次 push）出现 id 碰撞导致定时器互相误删。
let nextId = 0

// 契约：push 后立即渲染，duration ms 后自动从队列移除；外部调用方无需关心清理。
// 副作用：通过 setTimeout 修改响应式数组，依赖 Vue 响应式追踪驱动 ToastHost 重渲染。
// 注意：定时器句柄未保留——HMR/页面销毁时遗留的 timeout 不会取消，但因 toasts ref 是模块级且
// 单次执行，结果是无害的空 filter。设计上不引入清理表以保持 composable 极轻量。
function push(type: ToastType, message: string, duration = 2500) {
  const id = ++nextId
  toasts.value.push({ id, type, message })
  setTimeout(() => {
    toasts.value = toasts.value.filter(t => t.id !== id)
  }, duration)
}

// 暴露原始 ref：供 <ToastHost> 这类需要直接遍历列表的渲染组件订阅，避免被 useToast 的方法包一层导致响应式丢失。
// 调用方：App.vue 挂载的 <ToastHost>，遍历 toasts 渲染列表。
export function useToasts() {
  return toasts
}

// 业务侧最常用的入口：返回 success/error/info 三个便捷方法，自动套用各类型默认 duration。
// 调用方：任意组件中 `const toast = useToast()`，如 CharacterList、RoomListView 的删除/创建反馈。
export function useToast() {
  return {
    success: (msg: string) => push('success', msg),
    // 错误提示停留更久：给用户留出阅读错误信息的窗口，避免一闪而过看不清原因。
    error: (msg: string) => push('error', msg, 3500),
    info: (msg: string) => push('info', msg)
  }
}
