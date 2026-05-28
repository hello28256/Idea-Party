<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import MessageBubble from './MessageBubble.vue'
import ThinkingIndicator from './ThinkingIndicator.vue'
import type { ChatMessage } from '@/composables/useSocket'
import type { Character } from '@/types'

const props = defineProps<{
  messages: ChatMessage[]
  thinkingCharacterId: string | null
  characters: Character[]
  streamingMessages?: Record<string, string>
  currentUserId?: string | null
}>()

// 消息分组：判断是否是连续消息（同一发送者，间隔 < 5分钟）
interface MessageGroup {
  message: ChatMessage
  isFirstOfGroup: boolean
  isLastOfGroup: boolean
  showAvatar: boolean
  showName: boolean
}

const messageGroups = computed<MessageGroup[]>(() => {
  const result: MessageGroup[] = []
  let lastSenderId: string | null = null
  let lastTime: Date | null = null

  props.messages.forEach((msg) => {
    const senderId = msg.senderType === 'USER' ? 'user' : (msg.characterId || msg.characterName)
    const msgTime = new Date(msg.createdAt)

    // 判断是否与上一条是同一发送者的连续消息
    const isSameSender = senderId === lastSenderId
    const isContinuous = lastTime !== null &&
      (msgTime.getTime() - lastTime.getTime()) < 5 * 60 * 1000 // 5分钟内

    const isFirstOfGroup = !isSameSender || !isContinuous
    const isLastOfGroup = true // 简化处理，实际需要看下一条

    // 头像显示：仅在消息组第一条显示
    const showAvatar = isFirstOfGroup
    // 名字显示：仅在消息组第一条且非用户消息时显示
    const showName = isFirstOfGroup && msg.senderType !== 'USER'

    result.push({
      message: msg,
      isFirstOfGroup,
      isLastOfGroup,
      showAvatar,
      showName,
    })

    lastSenderId = senderId
    lastTime = msgTime
  })

  return result
})

// Compute streaming message bubbles
const streamingMessageBubbles = computed(() => {
  if (!props.streamingMessages || Object.keys(props.streamingMessages).length === 0) return []

  const bubbles: ChatMessage[] = []
  Object.entries(props.streamingMessages).forEach(([characterId, content]) => {
    bubbles.push({
      id: `streaming-${characterId}`,
      roomId: '',
      characterId,
      characterName: getCharacterName(characterId),
      senderType: 'CHARACTER',
      userId: null,
      content,
      avatarUrl: null,
      createdAt: new Date().toISOString()
    })
  })
  return bubbles
})

function getCharacterName(characterId: string | null): string {
  if (!characterId) return 'AI'
  const char = props.characters.find(c => c.id === characterId)
  return char?.name || 'AI'
}

// 判断消息是否为当前用户发送
function isOwnMessage(msg: ChatMessage): boolean {
  if (msg.senderType !== 'USER') return false
  return msg.userId === props.currentUserId
}

const hasMessages = () => props.messages.length > 0 || streamingMessageBubbles.value.length > 0

// 滚动层引用
const scrollContainer = ref<HTMLDivElement | null>(null)

// 滚动到底部
function scrollToBottom() {
  nextTick(() => {
    if (!scrollContainer.value) return
    scrollContainer.value.scrollTop = scrollContainer.value.scrollHeight
  })
}

defineExpose({ scrollToBottom })
</script>

<template>
  <div class="message-list">
    <div ref="scrollContainer" class="messages">
      <div v-if="!hasMessages()" class="empty-state">
        <h3 class="empty-title">今天想聊点什么？</h3>
        <p v-if="characters.length > 0" class="empty-subtitle">
          {{ characters.length }} 位角色正在等待加入讨论
        </p>
        <div v-if="characters.length > 0" class="empty-avatars">
          <div
            v-for="char in characters.slice(0, 5)"
            :key="char.id"
            class="empty-avatar-item"
          >
            <img
              :src="char.avatarUrl || '/image.png'"
              :alt="char.name"
              class="empty-avatar"
            />
            <span class="empty-avatar-name">{{ char.name }}</span>
          </div>
        </div>
      </div>

      <template v-else>
        <MessageBubble
          v-for="group in messageGroups"
          :key="group.message.id"
          :message="group.message"
          :is-own="isOwnMessage(group.message)"
          :show-avatar="group.showAvatar"
          :show-name="group.showName"
          :is-first-of-group="group.isFirstOfGroup"
          :is-last-of-group="group.isLastOfGroup"
        />
        <MessageBubble
          v-for="msg in streamingMessageBubbles"
          :key="msg.id"
          :message="msg"
          :is-own="false"
          :is-streaming="true"
          :show-avatar="true"
          :show-name="true"
          :is-first-of-group="true"
          :is-last-of-group="true"
        />
        <div v-if="thinkingCharacterId" class="thinking-area">
          <ThinkingIndicator :character-name="getCharacterName(thinkingCharacterId)" />
        </div>
      </template>

      <div class="bottom-anchor"></div>
    </div>
  </div>
</template>

<style scoped>
.message-list {
  flex: 1;
  height: 100%;
  max-height: 100%;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

/* 关键：height: 0 让 flex: 1 计算出真实剩余高度 */
.messages {
  flex: 1;
  height: 0;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  display: flex;
  flex-direction: column;
  padding: 12px 0;
  scroll-behavior: smooth;
  scrollbar-width: thin;
  scrollbar-color: var(--color-gold) transparent;
}

.messages::-webkit-scrollbar {
  width: 5px;
}

.messages::-webkit-scrollbar-track {
  background: transparent;
}

.messages::-webkit-scrollbar-thumb {
  background: var(--color-gold);
  border-radius: 3px;
  opacity: 0.5;
}

.messages::-webkit-scrollbar-thumb:hover {
  background: var(--color-gold-dark);
  opacity: 1;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 4rem 2rem;
  min-height: 200px;
}

.empty-icon {
  margin-bottom: 1.5rem;
  animation: gentleFloat 4s ease-in-out infinite;
  opacity: 0.6;
}

@keyframes gentleFloat {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-10px); }
}

.empty-title {
  font-family: 'Playfair Display', serif;
  font-size: 1.35rem;
  font-weight: 600;
  color: var(--color-navy);
  margin: 0 0 0.5rem 0;
  letter-spacing: 0.02em;
}

.empty-subtitle {
  font-size: 0.9rem;
  color: var(--color-text-secondary);
  margin: 0 0 1.5rem 0;
}

.empty-avatars {
  display: flex;
  gap: 1rem;
  justify-content: center;
  flex-wrap: wrap;
}

.empty-avatar-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.5rem;
}

.empty-avatar {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  object-fit: cover;
  border: 2px solid var(--color-gold);
  box-shadow: 0 2px 8px rgba(201, 169, 98, 0.3);
}

.empty-avatar-name {
  font-size: 0.75rem;
  color: var(--color-text-secondary);
  text-align: center;
}

.thinking-area {
  padding: 8px 16px 0;
  flex-shrink: 0;
}

.bottom-anchor {
  height: 1px;
  flex-shrink: 0;
}
</style>
