<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useRoomStore } from '@/stores/room'
import { useAuthStore } from '@/stores/auth'
import CreateRoomModal from '@/components/room/CreateRoomModal.vue'
import Button from '@/components/ui/Button.vue'

const router = useRouter()
const roomStore = useRoomStore()
const authStore = useAuthStore()

const showCreateModal = ref(false)

onMounted(async () => {
  await roomStore.fetchRooms()
})

async function handleDelete(roomId: string, roomName: string) {
  if (!confirm(`确定要删除聊天室 "${roomName}" 吗？`)) {
    return
  }

  try {
    await roomStore.deleteRoom(roomId)
  } catch (e) {
    alert(e instanceof Error ? e.message : '删除失败')
  }
}

function handleRoomCreated(roomId: string) {
  router.push(`/chat/${roomId}`)
}

function formatDate(dateString: string): string {
  const date = new Date(dateString)
  return date.toLocaleDateString('zh-CN', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit'
  })
}
</script>

<template>
  <div class="min-h-screen bg-white">
    <!-- Header -->
    <header class="border-b border-[#E5E7EB] bg-white sticky top-0 z-10">
      <div class="max-w-4xl mx-auto px-4 py-4 flex items-center justify-between">
        <div>
          <h1 class="text-2xl font-bold text-[#1F2937]">我的聊天室</h1>
          <p class="text-sm text-[#6B7280] mt-0.5">
            {{ authStore.user?.name || '用户' }}
          </p>
        </div>
        <!-- Desktop create button - hidden on mobile -->
        <Button
          variant="primary"
          class="hidden lg:inline-flex"
          @click="showCreateModal = true"
        >
          <svg class="w-4 h-4 mr-1.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
          </svg>
          创建房间
        </Button>
      </div>
    </header>

    <!-- Content -->
    <main class="max-w-4xl mx-auto px-4 py-6 pb-24 lg:pb-6">
      <!-- Loading State -->
      <div v-if="roomStore.loading && roomStore.rooms.length === 0" class="flex justify-center py-12">
        <svg class="animate-spin h-8 w-8 text-[#10B981]" fill="none" viewBox="0 0 24 24">
          <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" />
          <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
        </svg>
      </div>

      <!-- Empty State -->
      <div
        v-else-if="roomStore.rooms.length === 0"
        class="text-center py-16"
      >
        <svg class="w-16 h-16 mx-auto text-gray-300 mb-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5"
            d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0z" />
        </svg>
        <h2 class="text-xl font-medium text-[#1F2937] mb-2">还没有聊天室</h2>
        <p class="text-[#6B7280] mb-6">创建你的第一个聊天室，开始与 AI 角色对话</p>
        <Button variant="primary" @click="showCreateModal = true">
          创建第一个聊天室
        </Button>
      </div>

      <!-- Room List Grid -->
      <div v-else class="grid grid-cols-1 md:grid-cols-2 gap-4">
        <div
          v-for="room in roomStore.sortedRooms"
          :key="room.id"
          class="border border-[#E5E7EB] rounded-lg p-4 hover:border-[#10B981] transition-colors bg-white cursor-pointer"
          @click="router.push(`/chat/${room.id}`)"
        >
          <div class="flex items-start justify-between gap-3">
            <div class="flex-1 min-w-0">
              <h3 class="text-lg font-semibold text-[#1F2937] truncate">
                {{ room.name }}
              </h3>
              <p v-if="room.topic" class="text-sm text-[#6B7280] mt-1 line-clamp-2">
                {{ room.topic }}
              </p>
              <div class="flex items-center gap-3 mt-2 text-xs text-[#6B7280]">
                <span class="flex items-center gap-1">
                  <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                      d="M8 10h.01M12 10h.01M16 10h.01M9 16H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v8a2 2 0 01-2 2h-5l-5 5v-5z" />
                  </svg>
                  {{ room.characterCount }} 个角色
                </span>
                <span>{{ formatDate(room.updatedAt) }}</span>
              </div>
            </div>

            <!-- Action buttons with proper touch targets -->
            <div class="flex gap-1 shrink-0" @click.stop>
              <Button
                variant="destructive"
                size="sm"
                class="!min-w-[44px] !min-h-[44px] flex items-center justify-center"
                @click="handleDelete(room.id, room.name)"
              >
                <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                    d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
                </svg>
              </Button>
            </div>
          </div>
        </div>
      </div>
    </main>

    <!-- Mobile FAB (Floating Action Button) - visible only on mobile -->
    <button
      class="lg:hidden fixed bottom-6 right-6 w-14 h-14 bg-[#10B981] text-white rounded-full shadow-lg flex items-center justify-center hover:bg-[#059669] transition-colors z-10"
      @click="showCreateModal = true"
      aria-label="创建聊天室"
    >
      <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
      </svg>
    </button>

    <!-- Create Room Modal -->
    <CreateRoomModal
      :show="showCreateModal"
      @close="showCreateModal = false"
      @created="handleRoomCreated"
    />
  </div>
</template>
