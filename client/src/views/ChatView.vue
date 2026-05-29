<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ChatRoomPanel from '@/components/chat/ChatRoomPanel.vue'

const route = useRoute()
const router = useRouter()

const roomId = computed(() => route.params.roomId as string)

onMounted(() => {
  if (!roomId.value) {
    router.push('/rooms')
  }
})
</script>

<template>
  <ChatRoomPanel v-if="roomId" :room-id="roomId" :key="roomId" />
</template>
