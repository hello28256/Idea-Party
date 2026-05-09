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
  <div class="min-h-screen">
    <!-- Header -->
    <header class="header">
      <div class="max-w-4xl mx-auto px-4 py-6 flex items-center justify-between">
        <div class="animate-fade-in-up">
          <h1 class="text-display mb-1">我的聊天室</h1>
          <p class="text-[var(--color-text-secondary)]">
            <span class="text-[var(--color-gold)]">✦</span>
            {{ authStore.user?.name || '用户' }} · 智慧的殿堂
          </p>
        </div>
        <!-- Desktop actions -->
        <div class="flex items-center gap-3">
          <button
            @click="router.push('/settings')"
            class="hidden lg:flex p-2 rounded-lg hover:bg-[var(--color-parchment)] text-[var(--color-text-secondary)] transition-colors"
            title="设置"
          >
            <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" />
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
            </svg>
          </button>
          <Button
            variant="primary"
            class="hidden lg:inline-flex"
            @click="showCreateModal = true"
          >
            <svg class="w-4 h-4 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
            </svg>
            创建房间
          </Button>
        </div>
      </div>
    </header>

    <!-- Content -->
    <main class="max-w-4xl mx-auto px-4 py-8 pb-24 lg:pb-8">
      <!-- Decorative divider -->
      <div class="flex items-center justify-center gap-4 mb-10">
        <div class="h-px w-16 bg-gradient-to-r from-transparent to-[var(--color-gold)]"></div>
        <span class="text-[var(--color-gold)] text-sm">✦</span>
        <div class="h-px w-16 bg-gradient-to-l from-transparent to-[var(--color-gold)]"></div>
      </div>

      <!-- Loading State -->
      <div v-if="roomStore.loading && roomStore.rooms.length === 0" class="flex justify-center py-16">
        <div class="loading-spinner">
          <svg class="animate-spin h-10 w-10 text-[var(--color-gold)]" fill="none" viewBox="0 0 24 24">
            <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="3" />
            <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
          </svg>
        </div>
      </div>

      <!-- Empty State -->
      <div
        v-else-if="roomStore.rooms.length === 0"
        class="text-center py-20 animate-fade-in-up"
      >
        <div class="empty-icon">
          <svg class="w-24 h-24 mx-auto text-[var(--color-gold)] opacity-40" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1" d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0z" />
          </svg>
        </div>
        <h2 class="text-heading mt-6 mb-3">开启你的智慧之旅</h2>
        <p class="text-[var(--color-text-secondary)] mb-8 max-w-md mx-auto">
          创建一个聊天室，邀请历史上的伟大思想家，就任何话题展开深入对话
        </p>
        <Button variant="primary" @click="showCreateModal = true">
          <svg class="w-4 h-4 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
          </svg>
          创建第一个聊天室
        </Button>
      </div>

      <!-- Room List Grid -->
      <div v-else class="grid grid-cols-1 md:grid-cols-2 gap-6">
        <div
          v-for="(room, index) in roomStore.sortedRooms"
          :key="room.id"
          class="room-card animate-fade-in-up"
          :style="{ animationDelay: `${index * 0.1}s` }"
          @click="router.push(`/chat/${room.id}`)"
        >
          <!-- Decorative corner -->
          <div class="corner-accent top-left"></div>
          <div class="corner-accent top-right"></div>

          <div class="flex items-start justify-between gap-4">
            <div class="flex-1 min-w-0">
              <div class="flex items-center gap-2 mb-2">
                <div class="w-2 h-2 rounded-full bg-[var(--color-gold)]"></div>
                <h3 class="text-lg font-semibold text-[var(--color-navy)] truncate font-['Playfair_Display']">
                  {{ room.name }}
                </h3>
              </div>
              <p v-if="room.topic" class="text-sm text-[var(--color-text-secondary)] mt-2 line-clamp-2 leading-relaxed">
                {{ room.topic }}
              </p>
              <div class="flex items-center gap-4 mt-4 text-xs text-[var(--color-text-muted)]">
                <span class="flex items-center gap-1.5">
                  <svg class="w-3.5 h-3.5 text-[var(--color-gold)]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0z" />
                  </svg>
                  {{ room.characterCount }} 位思想家
                </span>
                <span class="flex items-center gap-1.5">
                  <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
                  </svg>
                  {{ formatDate(room.updatedAt) }}
                </span>
              </div>
            </div>

            <!-- Action buttons -->
            <div class="flex flex-col gap-2 shrink-0" @click.stop>
              <button
                class="p-2 rounded-lg hover:bg-[var(--color-parchment)] text-[var(--color-text-muted)] hover:text-[var(--color-destructive)] transition-all"
                @click="handleDelete(room.id, room.name)"
                title="删除房间"
              >
                <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
                </svg>
              </button>
            </div>
          </div>

          <!-- Enter indicator -->
          <div class="enter-hint">
            <span>进入讨论</span>
            <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 8l4 4m0 0l-4 4m4-4H3" />
            </svg>
          </div>
        </div>
      </div>
    </main>

    <!-- Mobile FAB -->
    <button
      class="lg:hidden fixed bottom-8 right-8 w-16 h-16 bg-gradient-to-br from-[var(--color-navy)] to-[var(--color-navy-light)] text-[var(--color-gold)] rounded-full shadow-lg flex items-center justify-center hover:scale-105 active:scale-95 transition-all z-10"
      @click="showCreateModal = true"
      aria-label="创建聊天室"
    >
      <svg class="w-7 h-7" fill="none" stroke="currentColor" viewBox="0 0 24 24">
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

<style scoped>
.header {
  background: linear-gradient(180deg, var(--color-ivory) 0%, var(--color-cream) 100%);
  border-bottom: 1px solid var(--color-border);
  position: relative;
}

.header::after {
  content: '';
  position: absolute;
  bottom: 0;
  left: 50%;
  transform: translateX(-50%);
  width: 120px;
  height: 2px;
  background: linear-gradient(90deg, transparent, var(--color-gold), transparent);
}

.room-card {
  position: relative;
  background: linear-gradient(145deg, var(--color-ivory), var(--color-parchment));
  border: 1px solid var(--color-border);
  border-radius: 16px;
  padding: 1.5rem;
  cursor: pointer;
  transition: all 0.4s cubic-bezier(0.4, 0, 0.2, 1);
  overflow: hidden;
}

.room-card::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 3px;
  background: linear-gradient(90deg, var(--color-gold-dark), var(--color-gold), var(--color-gold-dark));
  opacity: 0;
  transition: opacity 0.3s ease;
}

.room-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 12px 40px rgba(201, 169, 98, 0.2);
  border-color: var(--color-gold-light);
}

.room-card:hover::before {
  opacity: 1;
}

.corner-accent {
  position: absolute;
  width: 20px;
  height: 20px;
  opacity: 0;
  transition: opacity 0.3s ease;
}

.corner-accent.top-left {
  top: 8px;
  left: 8px;
  border-top: 2px solid var(--color-gold);
  border-left: 2px solid var(--color-gold);
}

.corner-accent.top-right {
  top: 8px;
  right: 8px;
  border-top: 2px solid var(--color-gold);
  border-right: 2px solid var(--color-gold);
}

.room-card:hover .corner-accent {
  opacity: 1;
}

.enter-hint {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-top: 1rem;
  padding-top: 1rem;
  border-top: 1px solid var(--color-border);
  font-size: 0.85rem;
  color: var(--color-text-muted);
  opacity: 0;
  transform: translateY(5px);
  transition: all 0.3s ease;
}

.room-card:hover .enter-hint {
  opacity: 1;
  transform: translateY(0);
  color: var(--color-gold);
}

.empty-icon {
  animation: float 4s ease-in-out infinite;
}

@keyframes float {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-10px); }
}
</style>
