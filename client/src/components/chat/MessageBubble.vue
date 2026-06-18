<script setup lang="ts">
// 单条聊天气泡组件：负责 IM 风格渲染（头像/用户名/气泡/时间戳/反馈按钮），
// 同时挂载 IntersectionObserver 用于"用户看到此消息"的事件埋点。
// 由父级列表（ChatRoom 等）按"消息组"传 props 决定是否合并显示。

import { computed, ref, onMounted, watch, nextTick } from 'vue'
import Avatar from '@/components/ui/Avatar.vue'
import MessageFeedbackButtons from '@/components/feedback/MessageFeedbackButtons.vue'
import MessageFeedbackModal from '@/components/feedback/MessageFeedbackModal.vue'
import { useAuthStore } from '@/stores/auth'
import { useMessageStore } from '@/stores/message'
import { useMessageEvents } from '@/composables/useMessageEvents'
import type { ChatMessage, MessageFeedbackPayload } from '@/composables/useSocket'

const authStore = useAuthStore()
const messageStore = useMessageStore()

// Props 设计：以"消息组"为单位控制展示，避免每条消息都重复渲染头像/用户名；
// 由父组件（消息列表）按发送者+时间窗聚合后再下发 group 标记。
interface Props {
  message: ChatMessage
  isOwn?: boolean
  isStreaming?: boolean
  showAvatar?: boolean      // 控制头像显示（连续消息时隐藏）
  showName?: boolean       // 控制用户名显示（连续消息时隐藏）
  isFirstOfGroup?: boolean // 是否是消息组的第一条
  isLastOfGroup?: boolean  // 是否是消息组的最后一条
}

const props = withDefaults(defineProps<Props>(), {
  isOwn: false,
  isStreaming: false,
  showAvatar: true,
  showName: true,
  isFirstOfGroup: true,
  isLastOfGroup: true,
})

// 只对"已结束流式输出的 AI 消息"做曝光埋点：用户自己发的消息不算，
// 还在打字中的消息在内容稳定前不统计，避免半成品被计入曝光。
const events = useMessageEvents({ messageId: props.message.id })
const bubbleEl = ref<HTMLElement | null>(null)
const trackable = computed(() => !props.isOwn && !props.isStreaming && props.message.senderType === 'CHARACTER')

// 挂载时若已满足 trackable，挂 IntersectionObserver 追踪可见性。
// 用 nextTick 等待 template 渲染完成，保证 bubbleEl 已挂到 DOM。
onMounted(() => {
  if (!trackable.value) return
  nextTick(() => {
    if (bubbleEl.value) events.setupObserver(bubbleEl.value)
  })
})

// 流式结束后（消息落定）需重新挂载观察者，因为初次挂载时 isStreaming=true 跳过了。
// 监听 isStreaming 而非 message.content，是为了让"内容仍可能变化"和"可埋点"解耦。
watch(() => props.isStreaming, (streaming) => {
  if (!streaming && trackable.value && bubbleEl.value) {
    events.setupObserver(bubbleEl.value)
  }
})

const showFeedbackModal = ref(false)
const modalPayload = ref<MessageFeedbackPayload | null>(null)

// 反馈按钮仅对 AI 角色的"已结束流式"消息开放：用户消息无意义、还在打字的消息不能评价。
const showFeedbackButtons = computed(() => {
  return !props.isOwn && !props.isStreaming && props.message.senderType === 'CHARACTER'
})

const formattedTime = computed(() => {
  if (!props.message.createdAt) return ''
  const date = new Date(props.message.createdAt)
  return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
})

const displayName = computed(() => {
  if (props.message.senderType === 'USER') {
    return '你'
  }
  return props.message.characterName || '未知角色'
})

// 双触发条件：父组件显式传 isStreaming（推荐），或服务端刚好以 "..." 结尾作为兜底，
// 保证即使 prop 没传，UI 也能展示打字动画而不是静止的死文本。
const showStreamingIndicator = computed(() => {
  return props.isStreaming || props.message.content.endsWith('...')
})

// 用角色名首字符的 charCode 取模调色板，保证同一角色在不同消息里颜色稳定可识别。
// 用户固定用第一档金色，与系统"你"的徽标视觉一致。
const avatarGradient = computed(() => {
  const gradients = [
    'linear-gradient(135deg, #C9A962 0%, #A68B4B 100%)',
    'linear-gradient(135deg, #722F37 0%, #5D2428 100%)',
    'linear-gradient(135deg, #1E2A3A 0%, #2D3E50 100%)',
    'linear-gradient(135deg, #277568 0%, #1D5A4E 100%)',
    'linear-gradient(135deg, #8B6914 0%, #6B5010 100%)',
  ]
  if (props.message.senderType === 'USER') {
    return gradients[0]
  }
  const hash = props.message.characterName?.charCodeAt(0) || 0
  return gradients[hash % gradients.length]
})

// 行内反馈（点赞/点踩）的写入：失败仅打日志，不弹 toast；
// 因为这是用户长流程里的轻量操作，错误已经包含在 messageStore 内可被上层观测。
async function handleFeedbackChange(payload: MessageFeedbackPayload | null) {
  try {
    await messageStore.setFeedback(props.message.id, payload)
  } catch (e) {
    console.error('[Feedback] change failed:', e)
  }
}

function handleOpenFeedbackModal(current: MessageFeedbackPayload | null) {
  modalPayload.value = current
  showFeedbackModal.value = true
}

// 弹窗提交即代表用户明确选择"踩 + 原因 + 备注"，固定 type=DISLIKE；
// 服务端只用 category/comment 做归因分析，type 字段在此场景是常量。
async function handleModalSubmit(data: { category: string; comment: string | null }) {
  const payload: MessageFeedbackPayload = {
    type: 'DISLIKE',
    category: data.category,
    comment: data.comment,
    createdAt: new Date().toISOString()
  }
  try {
    await messageStore.setFeedback(props.message.id, payload)
    showFeedbackModal.value = false
  } catch (e) {
    console.error('[Feedback] submit failed:', e)
  }
}

// 弹窗内"撤销反馈"路径：与行内 removeFeedback 等价，统一走 setFeedback(null)，
// 让后端只维护一条反馈状态而不是"曾经有过"的删除语义。
async function handleModalRemove() {
  try {
    await messageStore.setFeedback(props.message.id, null)
    showFeedbackModal.value = false
  } catch (e) {
    console.error('[Feedback] remove failed:', e)
  }
}
</script>

<template>
  <!--
    IM 风格消息行布局：
    - AI 消息：头像在左，名字在上，气泡在下左
    - 用户消息：名字在上右，气泡在下右，头像在右
  -->
  <div
    ref="bubbleEl"
    class="message-row"
    :class="{
      'own': isOwn,
      'no-avatar': !showAvatar,
      'first-of-group': isFirstOfGroup,
      'last-of-group': isLastOfGroup,
    }"
    :data-message-id="message.id"
  >
    <!-- 头像区域 -->
    <div class="avatar-area" :class="{ 'own': isOwn }">
      <Avatar
        v-if="showAvatar"
        :src="isOwn ? authStore.user?.avatarUrl : message.avatarUrl"
        :name="displayName"
        size="small"
        :gradient="avatarGradient"
      />
      <!-- 连续消息时的占位，保持对齐 -->
      <div v-else class="avatar-placeholder"></div>
    </div>

    <!-- 消息内容区域 -->
    <div class="content-area">
      <!-- 用户名（仅第一条显示） -->
      <div v-if="showName && !isOwn" class="sender-info">
        <span class="sender-name">{{ displayName }}</span>
      </div>
      <div v-if="showName && isOwn" class="sender-info own">
        <span class="sender-role">我</span>
      </div>

      <!-- 气泡 -->
      <div class="bubble-row">
        <!-- AI 消息气泡：尖角朝左 -->
        <div
          v-if="!isOwn"
          class="bubble bubble-ai"
          :class="{ 'streaming': isStreaming }"
        >
          <span class="bubble-text">{{ message.content }}</span>
          <span v-if="showStreamingIndicator" class="streaming-dots">
            <span class="dot"></span>
            <span class="dot"></span>
            <span class="dot"></span>
          </span>
        </div>

        <!-- 用户消息气泡：尖角朝右 -->
        <div v-else class="bubble bubble-user">
          <span class="bubble-text">{{ message.content }}</span>
        </div>
      </div>

      <!-- 时间戳 + 反馈按钮（仅最后一条显示） -->
      <div v-if="showName && isLastOfGroup" class="meta-row">
        <span class="time-stamp">{{ formattedTime }}</span>
        <MessageFeedbackButtons
          v-if="showFeedbackButtons"
          :feedback="message.feedback ?? null"
          @change="handleFeedbackChange"
          @open-modal="handleOpenFeedbackModal"
        />
      </div>
    </div>
  </div>

  <MessageFeedbackModal
    :show="showFeedbackModal"
    :current="modalPayload"
    @close="showFeedbackModal = false"
    @submit="handleModalSubmit"
    @cancel-feedback="handleModalRemove"
  />
</template>

<style scoped>
/* ================================
   消息行基础布局 - content-based width
   ================================ */
.message-row {
  display: flex;
  gap: 8px;
  padding: 0 16px;
  /* 内容驱动宽度，不要撑满 */
  align-items: flex-start;
  /* 连续消息减少顶部间距 */
  margin-top: 2px;
  animation: fadeIn 0.2s ease-out;
}

.message-row.first-of-group {
  margin-top: 12px;
}

.message-row.last-of-group {
  margin-bottom: 4px;
}

/* 连续消息且不显示头像时，调整间距 */
.message-row.no-avatar.first-of-group {
  margin-top: 2px;
}

/* ================================
   头像区域
   ================================ */
.avatar-area {
  flex-shrink: 0;
  width: 36px; /* 小头像尺寸 */
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding-top: 2px;
}

.avatar-area.own {
  order: 2; /* 用户头像在右侧 */
}

.avatar-placeholder {
  width: 36px;
  height: 36px;
}

/* ================================
   内容区域 - 不再使用 flex: 1 撑满
   ================================ */
.content-area {
  /* 移除 flex: 1，让内容区宽度由内容决定 */
  min-width: 0;
  max-width: 70%; /* 气泡最大宽度 70% */
  display: flex;
  flex-direction: column;
  /* 收缩到内容宽度 */
  width: fit-content;
}

.message-row.own .content-area {
  /* 用户消息靠右对齐 */
  margin-left: auto;
  align-items: flex-end;
}

/* ================================
   发送者信息（用户名）
   ================================ */
.sender-info {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 4px;
  padding-left: 4px;
}

.sender-info.own {
  justify-content: flex-end;
  padding-left: 0;
  padding-right: 4px;
}

.sender-name {
  font-size: 0.75rem;
  font-weight: 500;
  color: var(--color-text-secondary);
}

.sender-role {
  font-size: 0.65rem;
  color: var(--color-gold-dark);
  background: rgba(201, 169, 98, 0.12);
  padding: 1px 6px;
  border-radius: 4px;
}

/* ================================
   气泡行
   ================================ */
.bubble-row {
  display: flex;
  align-items: flex-end;
  gap: 6px;
}

.message-row.own .bubble-row {
  flex-direction: row-reverse; /* 用户消息气泡靠右 */
}

/* ================================
   气泡样式 - 轻量 IM 风格
   ================================ */
.bubble {
  position: relative;
  padding: 8px 12px;
  border-radius: 12px;
  max-width: 100%;
  word-break: break-word;
  line-height: 1.5;
}

/* AI 消息气泡 - 白色/米色背景，左侧小尖角 */
.bubble-ai {
  background: #ffffff;
  border: 1px solid #e8e0d4;
  border-top-left-radius: 4px; /* 微信风格：尖角在上 */
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
}

/* AI 消息小尖角 */
.bubble-ai::before {
  content: '';
  position: absolute;
  left: -6px;
  top: 8px;
  width: 0;
  height: 0;
  border-top: 6px solid transparent;
  border-bottom: 6px solid transparent;
  border-right: 6px solid #ffffff;
}

.bubble-ai::after {
  content: '';
  position: absolute;
  left: -7px;
  top: 8px;
  width: 0;
  height: 0;
  border-top: 6px solid transparent;
  border-bottom: 6px solid transparent;
  border-right: 6px solid #e8e0d4;
}

/* 用户消息气泡 - 蓝色背景，右侧小尖角 */
.bubble-user {
  background: linear-gradient(135deg, #4a90d9 0%, #3b7dd8 100%);
  border-top-right-radius: 4px; /* 微信风格：尖角在上 */
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.08);
}

/* 用户消息小尖角 */
.bubble-user::before {
  content: '';
  position: absolute;
  right: -6px;
  top: 8px;
  width: 0;
  height: 0;
  border-top: 6px solid transparent;
  border-bottom: 6px solid transparent;
  border-left: 6px solid #3b7dd8;
}

.bubble-user::after {
  content: '';
  position: absolute;
  right: -7px;
  top: 8px;
  width: 0;
  height: 0;
  border-top: 6px solid transparent;
  border-bottom: 6px solid transparent;
  border-left: 6px solid transparent;
}

/* 气泡文字 */
.bubble-text {
  font-size: 0.9rem;
  line-height: 1.5;
  color: var(--color-text-primary);
}

.bubble-user .bubble-text {
  color: #ffffff;
}

/* 流式消息动画 */
.bubble.streaming {
  min-width: 60px;
}

/* ================================
   流式指示器
   ================================ */
.streaming-dots {
  display: inline-flex;
  gap: 3px;
  align-items: center;
  margin-left: 4px;
  vertical-align: middle;
}

.streaming-dots .dot {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: var(--color-gold);
  animation: blink 1.2s ease-in-out infinite;
}

.streaming-dots .dot:nth-child(2) {
  animation-delay: 0.2s;
}

.streaming-dots .dot:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes blink {
  0%, 100% { opacity: 0.3; }
  50% { opacity: 1; }
}

/* ================================
   时间戳 + 反馈按钮
   ================================ */
.meta-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
  padding-left: 4px;
  min-height: 18px;
}

.message-row.own .meta-row {
  padding-left: 0;
  padding-right: 4px;
  justify-content: flex-end;
}

.time-stamp {
  font-size: 0.65rem;
  color: var(--color-text-muted);
}

/* ================================
   动画
   ================================ */
@keyframes fadeIn {
  from {
    opacity: 0;
    transform: translateY(4px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* ================================
   暗色模式适配
   ================================ */
@media (prefers-color-scheme: dark) {
  .bubble-ai {
    background: #2a2a35;
    border-color: #3a3a45;
  }

  .bubble-ai::before {
    border-right-color: #2a2a35;
  }

  .bubble-ai::after {
    border-right-color: #3a3a45;
  }

  .bubble-text {
    color: #e8e8e8;
  }

  .sender-name {
    color: #a0a0a8;
  }
}
</style>
