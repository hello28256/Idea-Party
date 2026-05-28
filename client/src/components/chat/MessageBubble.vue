<script setup lang="ts">
import { computed } from 'vue'
import Avatar from '@/components/ui/Avatar.vue'
import { useAuthStore } from '@/stores/auth'
import type { ChatMessage } from '@/composables/useSocket'

const authStore = useAuthStore()

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

const showStreamingIndicator = computed(() => {
  return props.isStreaming || props.message.content.endsWith('...')
})

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
</script>

<template>
  <!--
    IM 风格消息行布局：
    - AI 消息：头像在左，名字在上，气泡在下左
    - 用户消息：名字在上右，气泡在下右，头像在右
  -->
  <div
    class="message-row"
    :class="{
      'own': isOwn,
      'no-avatar': !showAvatar,
      'first-of-group': isFirstOfGroup,
      'last-of-group': isLastOfGroup,
    }"
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

      <!-- 时间戳（仅最后一条显示） -->
      <div v-if="showName && isLastOfGroup" class="time-stamp">
        {{ formattedTime }}
      </div>
    </div>
  </div>
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
   时间戳
   ================================ */
.time-stamp {
  font-size: 0.65rem;
  color: var(--color-text-muted);
  margin-top: 4px;
  padding-left: 4px;
}

.message-row.own .time-stamp {
  padding-left: 0;
  padding-right: 4px;
  text-align: right;
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
