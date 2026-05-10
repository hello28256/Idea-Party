<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { useRoomStore } from '@/stores/room'

const props = defineProps<{
  show: boolean
  roomId: string
}>()

const emit = defineEmits<{
  close: []
}>()

const roomStore = useRoomStore()

const chatMode = ref<'dialogue' | 'discussion'>('dialogue')
const maxDiscussionRounds = ref(5)
const saving = ref(false)
const error = ref<string | null>(null)

const currentRoom = computed(() => roomStore.currentRoom)

// Sync local state with room when modal opens
watch(() => props.show, (isOpen) => {
  if (isOpen && currentRoom.value) {
    chatMode.value = currentRoom.value.chatMode || 'dialogue'
    maxDiscussionRounds.value = currentRoom.value.maxDiscussionRounds || 5
    error.value = null
  }
})

async function handleSave() {
  saving.value = true
  error.value = null
  try {
    await roomStore.updateRoomMode(props.roomId, {
      chatMode: chatMode.value,
      maxDiscussionRounds: chatMode.value === 'discussion' ? maxDiscussionRounds.value : undefined
    })
    emit('close')
  } catch (e) {
    error.value = e instanceof Error ? e.message : '保存失败'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <Teleport to="body">
    <div
      v-if="show"
      class="fixed inset-0 z-50 flex items-center justify-center"
      @click.self="emit('close')"
    >
      <!-- Backdrop -->
      <div class="absolute inset-0 bg-black/40 backdrop-blur-sm" @click="emit('close')" />

      <!-- Modal -->
      <div class="relative bg-white rounded-2xl shadow-2xl w-full max-w-md mx-4 overflow-hidden">
        <!-- Header -->
        <div class="flex items-center justify-between px-6 py-4 border-b border-gray-100">
          <h2 class="text-lg font-semibold text-gray-900">房间设置</h2>
          <button
            class="p-1 rounded-lg hover:bg-gray-100 text-gray-400 hover:text-gray-600 transition-colors"
            @click="emit('close')"
          >
            <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        <!-- Content -->
        <div class="px-6 py-5 space-y-6">
          <!-- Error -->
          <div v-if="error" class="p-3 bg-red-50 border border-red-200 rounded-lg text-red-600 text-sm">
            {{ error }}
          </div>

          <!-- Chat Mode -->
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-2">聊天模式</label>
            <div class="grid grid-cols-2 gap-3">
              <button
                class="p-3 rounded-xl border-2 transition-all text-left"
                :class="chatMode === 'dialogue'
                  ? 'border-[var(--color-navy)] bg-[var(--color-navy)]/5 text-[var(--color-navy)]'
                  : 'border-gray-200 hover:border-gray-300 text-gray-600'"
                @click="chatMode = 'dialogue'"
              >
                <div class="font-medium">对话模式</div>
                <div class="text-xs mt-1 opacity-70">角色响应一次后结束</div>
              </button>
              <button
                class="p-3 rounded-xl border-2 transition-all text-left"
                :class="chatMode === 'discussion'
                  ? 'border-[var(--color-navy)] bg-[var(--color-navy)]/5 text-[var(--color-navy)]'
                  : 'border-gray-200 hover:border-gray-300 text-gray-600'"
                @click="chatMode = 'discussion'"
              >
                <div class="font-medium">讨论模式</div>
                <div class="text-xs mt-1 opacity-70">角色持续讨论多轮</div>
              </button>
            </div>
          </div>

          <!-- Discussion Rounds (only for discussion mode) -->
          <div v-if="chatMode === 'discussion'">
            <label class="block text-sm font-medium text-gray-700 mb-2">
              讨论轮数
              <span class="text-gray-400 font-normal ml-1">{{ maxDiscussionRounds }} 轮</span>
            </label>
            <input
              v-model="maxDiscussionRounds"
              type="range"
              min="2"
              max="10"
              step="1"
              class="w-full h-2 bg-gray-200 rounded-lg appearance-none cursor-pointer accent-[var(--color-navy)]"
            />
            <div class="flex justify-between text-xs text-gray-400 mt-1">
              <span>2 轮</span>
              <span>10 轮</span>
            </div>
          </div>

          <!-- Mode Description -->
          <div class="p-3 bg-gray-50 rounded-lg text-sm text-gray-600">
            <template v-if="chatMode === 'dialogue'">
              <strong>对话模式：</strong>用户发送一条消息后，所有角色会并行响应一次，然后讨论结束。用户需要再次发送消息才会触发新的响应。
            </template>
            <template v-else>
              <strong>讨论模式：</strong>用户发送一条消息后，角色们会持续多轮讨论，互相评论和辩论。用户也可以随时插入新话题。
            </template>
          </div>
        </div>

        <!-- Footer -->
        <div class="px-6 py-4 bg-gray-50 border-t border-gray-100 flex justify-end gap-3">
          <button
            class="px-4 py-2 text-sm font-medium text-gray-600 hover:text-gray-800 hover:bg-gray-100 rounded-lg transition-colors"
            @click="emit('close')"
          >
            取消
          </button>
          <button
            class="px-4 py-2 text-sm font-medium text-white bg-[var(--color-navy)] hover:bg-[var(--color-navy-dark)] rounded-lg transition-colors disabled:opacity-50"
            :disabled="saving"
            @click="handleSave"
          >
            {{ saving ? '保存中...' : '保存设置' }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>
