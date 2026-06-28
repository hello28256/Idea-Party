<script setup lang="ts">
/**
 * 角色级联删除确认弹窗：当角色被 N 个聊天室引用时，在删除前展示受影响的房间列表，
 * 由用户决定「全删或全不删」。不可单独多选：业务上"删一半留一半"会让用户陷入状态混乱，
 * 与"原子级联"的初衷冲突，所以本弹窗只暴露"全部一并删除"或"取消"两条路径。
 *
 * 视觉规范完全对齐 ConfirmDialog：
 *   - Teleport to body、Teleport 锁滚动 + 卸载兜底清理
 *   - CSS 变量集中管理、深色主题 :global(.dark) 覆盖
 *   - 危险按钮刻意使用 primary 配色（项目既定设计语言，不引入红色避免视觉噪音）
 *   - loading 时主按钮置 loading 态、副按钮禁用、遮罩点击与 ESC 不触发 cancel
 */
import { watch, onUnmounted } from 'vue'
import type { ReferencedRoom } from '@/api/characters'

interface Props {
  /** 受控开关 */
  show: boolean
  /** 待删除角色名，渲染到主文案中 */
  characterName: string
  /** 引用了该角色的房间列表（id + name） */
  rooms: ReferencedRoom[]
  /** 提交中态：触发后由父组件置 true，期间禁用按钮并阻止重复 emit */
  loading?: boolean
  /** 错误信息（如后端 403 / 网络异常），弹窗顶部展示；为空时不渲染 */
  error?: string | null
}

const props = withDefaults(defineProps<Props>(), {
  loading: false,
  error: null
})

const emit = defineEmits<{
  (e: 'confirm'): void
  (e: 'cancel'): void
}>()

// 列表展示上限：超过 N 个时折叠为「其余 X 个聊天室」，避免长列表视觉冲击
const MAX_VISIBLE = 10

const visibleRooms = (): ReferencedRoom[] => props.rooms.slice(0, MAX_VISIBLE)
const hiddenCount = (): number => Math.max(0, props.rooms.length - MAX_VISIBLE)
const roomCount = (): number => props.rooms.length

function handleConfirm() {
  if (props.loading) return
  emit('confirm')
}

function handleCancel() {
  if (props.loading) return
  emit('cancel')
}

// 空态防护：弹窗打开期间若父组件把 rooms 清空（如其他端并发删除了所有房间），
// 自动 emit cancel 让上层回退到原 ConfirmDialog 兜底，避免在空弹窗里点确认。
watch(
  () => [props.show, props.rooms.length] as const,
  ([visible, len]) => {
    if (visible && len === 0) emit('cancel')
  }
)

// 锁背景滚动 + 卸载兜底：与 ConfirmDialog 一致的范式，避免历史踩坑
watch(() => props.show, (visible) => {
  document.body.style.overflow = visible ? 'hidden' : ''
})
onUnmounted(() => {
  document.body.style.overflow = ''
})
</script>

<template>
  <Teleport to="body">
    <Transition name="cascade-fade">
      <!-- loading 中 @click.self 不触发 cancel，避免误触中断删除 -->
      <div v-if="show" class="cascade-overlay" @click.self="handleCancel">
        <div class="cascade-modal" role="dialog" aria-modal="true">
          <header class="cascade-header">
            <h3 class="cascade-title">删除角色并清理引用</h3>
          </header>

          <div class="cascade-body">
            <!-- 错误条：弹窗顶部展示，不打断主体信息 -->
            <div v-if="error" class="cascade-error">{{ error }}</div>

            <p class="cascade-message">
              删除角色「<strong>{{ characterName }}</strong>」将
              <strong>同时删除</strong>
              <strong>{{ roomCount() }}</strong> 个引用了该角色的聊天室，
              <strong>不可恢复</strong>。被删除的聊天室中的历史消息、成员关系也会一并清理。
            </p>

            <ul v-if="rooms.length > 0" class="cascade-room-list" aria-label="受影响的聊天室列表">
              <li v-for="room in visibleRooms()" :key="room.id" class="cascade-room-item">
                <span class="cascade-room-bullet">•</span>
                <span class="cascade-room-name">{{ room.name }}</span>
                <span class="cascade-room-id">{{ room.id.slice(-8) }}</span>
              </li>
              <li v-if="hiddenCount() > 0" class="cascade-room-overflow">
                …其余 {{ hiddenCount() }} 个聊天室
              </li>
            </ul>
          </div>

          <footer class="cascade-footer">
            <button
              type="button"
              class="cascade-btn cascade-btn-cancel"
              :disabled="loading"
              @click="handleCancel"
            >取消</button>
            <button
              type="button"
              class="cascade-btn cascade-btn-primary"
              :disabled="loading"
              @click="handleConfirm"
            >{{ loading ? '删除中...' : `删除角色与 ${roomCount()} 个聊天室` }}</button>
          </footer>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
/* 视觉规范与 ConfirmDialog 对齐：CSS 变量集中管理，dark 主题 :global(.dark) 覆盖 */
.cascade-overlay {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px;
  background: transparent;
}

.cascade-modal {
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
  --list-bg: rgba(248, 250, 252, 0.72);
  --list-border: rgba(226, 232, 240, 0.85);
  --error-bg: rgba(239, 68, 68, 0.08);
  --error-border: rgba(239, 68, 68, 0.32);
  --error-text: #b91c1c;

  position: relative;
  width: min(520px, calc(100vw - 48px));
  max-height: min(640px, calc(100vh - 64px));
  display: flex;
  flex-direction: column;
  background: var(--modal-bg);
  border: 1px solid var(--modal-border);
  border-radius: 24px;
  box-shadow: var(--modal-shadow);
  overflow: hidden;
}

:global(.dark) .cascade-modal {
  --modal-bg: #0f172a;
  --modal-border: rgba(71, 85, 105, 0.85);
  --modal-shadow: 0 0 0 1px rgba(15, 23, 42, 0.55);
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
  --list-bg: rgba(15, 23, 42, 0.55);
  --list-border: rgba(71, 85, 105, 0.75);
  --error-bg: rgba(239, 68, 68, 0.12);
  --error-border: rgba(239, 68, 68, 0.4);
  --error-text: #fca5a5;
}

/*** Header ***/
.cascade-header {
  padding: 28px 32px 8px;
}
.cascade-title {
  margin: 0;
  font-size: 24px;
  font-weight: 700;
  color: var(--text-primary);
}

/*** Body ***/
.cascade-body {
  padding: 0 32px 24px;
  overflow-y: auto;
  flex: 1 1 auto;
}

.cascade-message {
  margin: 0 0 16px;
  font-size: 14px;
  line-height: 1.6;
  color: var(--text-secondary);
}

.cascade-message strong {
  color: var(--text-primary);
  font-weight: 700;
}

.cascade-error {
  margin: 0 0 16px;
  padding: 10px 14px;
  background: var(--error-bg);
  border: 1px solid var(--error-border);
  border-radius: 12px;
  font-size: 13px;
  color: var(--error-text);
  line-height: 1.5;
}

.cascade-room-list {
  margin: 0;
  padding: 8px 14px;
  list-style: none;
  background: var(--list-bg);
  border: 1px solid var(--list-border);
  border-radius: 14px;
  max-height: 220px;
  overflow-y: auto;
}

.cascade-room-item {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding: 8px 0;
  font-size: 14px;
  color: var(--text-primary);
  border-bottom: 1px dashed var(--list-border);
}

.cascade-room-item:last-child {
  border-bottom: none;
}

.cascade-room-bullet {
  color: var(--text-muted);
  flex-shrink: 0;
}

.cascade-room-name {
  flex: 1 1 auto;
  font-weight: 600;
  word-break: break-word;
}

.cascade-room-id {
  flex-shrink: 0;
  font-size: 12px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  color: var(--text-muted);
}

.cascade-room-overflow {
  padding: 8px 0 0;
  font-size: 13px;
  color: var(--text-muted);
  font-style: italic;
}

/*** Footer ***/
.cascade-footer {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 12px;
  padding: 18px 32px;
  border-top: 1px solid var(--footer-border);
}

.cascade-btn {
  height: 42px;
  padding: 0 18px;
  border-radius: 14px;
  font-size: 14px;
  font-weight: 600;
  border: 1px solid transparent;
  cursor: pointer;
  transition: all 0.15s ease;
}

.cascade-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.cascade-btn-cancel {
  background: var(--btn-secondary-bg);
  border-color: var(--btn-secondary-border);
  color: var(--btn-secondary-text);
}
.cascade-btn-cancel:hover:not(:disabled) {
  border-color: var(--text-muted);
  color: var(--text-primary);
}

/* 主按钮复用 primary 配色：与项目 ConfirmDialog 一致，刻意克制"危险色"。
   真实危险感由文案（不可恢复）+ loading 态传达，避免红色视觉噪音。 */
.cascade-btn-primary {
  background: var(--btn-primary-bg);
  color: var(--btn-primary-text);
  font-weight: 700;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.18);
}
.cascade-btn-primary:hover:not(:disabled) {
  opacity: 0.92;
}

/* 过渡：与 ConfirmDialog 一致 */
.cascade-fade-enter-active,
.cascade-fade-leave-active {
  transition: opacity 0.18s ease;
}
.cascade-fade-enter-from,
.cascade-fade-leave-to {
  opacity: 0;
}
</style>