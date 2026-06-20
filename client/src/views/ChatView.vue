<script setup lang="ts">
// ChatView：路由 /chat/:roomId 的入口壳层。
// 真正承担聊天 UI 的是 ChatRoomPanel：这里只做路由参数解析 + 异常回退（无 roomId 时跳回房间列表）。
// 三栏布局（my-rooms tab）已在 RoomListView 内嵌 ChatRoomPanel；本路由保留作为深链接兜底入口，
// 便于外部分享 / 直接打开 / 旧版 URL 兼容。
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ChatRoomPanel from '@/components/chat/ChatRoomPanel.vue'

const route = useRoute()
const router = useRouter()

// route.params.roomId 是动态段，computed 形式让 URL 变化时自动重渲染 ChatRoomPanel
const roomId = computed(() => route.params.roomId as string)

// 生命周期：仅做最小校验——缺失 roomId 时立即跳回，避免渲染一个空的 ChatRoomPanel 导致白屏。
// ChatRoomPanel 内部的 socket 订阅、WebSocket 连接清理由组件自身 onUnmounted 负责（关注点分离）。
onMounted(() => {
  if (!roomId.value) {
    router.push('/rooms')
  }
})
</script>

<template>
  <ChatRoomPanel v-if="roomId" :room-id="roomId" :key="roomId" />
</template>
