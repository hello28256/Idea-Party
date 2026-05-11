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
    <Transition name="modal">
      <div
        v-if="show"
        class="fixed inset-0 z-50 flex items-center justify-center px-4"
        @click.self="emit('close')"
      >
        <!-- Backdrop -->
        <div class="absolute inset-0 bg-black/40 backdrop-blur-sm" @click="emit('close')" />

        <!-- Modal -->
        <div class="relative w-full max-w-md bg-gradient-to-b from-[var(--color-ivory)] to-[var(--color-cream)] rounded-2xl shadow-2xl overflow-hidden">
          <!-- Decorative top border -->
          <div class="absolute top-0 left-0 right-0 h-1 bg-gradient-to-r from-[var(--color-gold-dark)] via-[var(--color-gold)] to-[var(--color-gold-dark)]"></div>

          <!-- Header -->
          <div class="flex items-center justify-between px-6 py-5 border-b border-[var(--color-border)]">
            <div class="flex items-center gap-3">
              <div class="w-10 h-10 rounded-xl bg-gradient-to-br from-[var(--color-navy)] to-[var(--color-navy-light)] flex items-center justify-center">
                <svg class="w-5 h-5 text-[var(--color-gold)]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" />
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
                </svg>
              </div>
              <h2 class="text-lg font-semibold text-[var(--color-navy)] font-['Playfair_Display']">房间设置</h2>
            </div>
            <button
              class="p-2 rounded-lg hover:bg-[var(--color-parchment)] text-[var(--color-text-secondary)] transition-colors"
              @click="emit('close')"
            >
              <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>

          <!-- Content -->
          <div class="px-6 py-5 space-y-5">
            <!-- Error -->
            <div v-if="error" class="p-3 bg-red-50/80 border border-red-100 rounded-lg text-[var(--color-destructive)] text-sm">
              {{ error }}
            </div>

            <!-- Chat Mode -->
            <div>
              <label class="block text-sm font-medium text-[var(--color-navy)] mb-3">聊天模式</label>
              <div class="grid grid-cols-2 gap-3">
                <button
                  class="p-4 rounded-xl border-2 transition-all text-left"
                  :class="chatMode === 'dialogue'
                    ? 'border-[var(--color-gold)] bg-[var(--color-gold)]/10 text-[var(--color-navy)]'
                    : 'border-[var(--color-border)] hover:border-[var(--color-gold)]/50 text-[var(--color-text-secondary)]'"
                  @click="chatMode = 'dialogue'"
                >
                  <div class="font-medium text-sm">对话模式</div>
                  <div class="text-xs mt-1.5 opacity-70">角色响应一次后结束</div>
                </button>
                <button
                  class="p-4 rounded-xl border-2 transition-all text-left"
                  :class="chatMode === 'discussion'
                    ? 'border-[var(--color-gold)] bg-[var(--color-gold)]/10 text-[var(--color-navy)]'
                    : 'border-[var(--color-border)] hover:border-[var(--color-gold)]/50 text-[var(--color-text-secondary)]'"
                  @click="chatMode = 'discussion'"
                >
                  <div class="font-medium text-sm">讨论模式</div>
                  <div class="text-xs mt-1.5 opacity-70">角色持续讨论多轮</div>
                </button>
              </div>
            </div>

            <!-- Discussion Rounds (only for discussion mode) -->
            <div v-if="chatMode === 'discussion'">
              <label class="block text-sm font-medium text-[var(--color-navy)] mb-3">
                讨论轮数
                <span class="text-[var(--color-text-muted)] font-normal ml-2">{{ maxDiscussionRounds }} 轮</span>
              </label>
              <input
                v-model="maxDiscussionRounds"
                type="range"
                min="2"
                max="10"
                step="1"
                class="w-full h-2 bg-[var(--color-parchment)] rounded-lg appearance-none cursor-pointer accent-[var(--color-gold)]"
              />
              <div class="flex justify-between text-xs text-[var(--color-text-muted)] mt-2">
                <span>2 轮</span>
                <span>10 轮</span>
              </div>
            </div>

            <!-- Mode Description -->
            <div class="p-4 bg-[var(--color-parchment)]/50 rounded-xl text-sm text-[var(--color-text-secondary)] border border-[var(--color-border)]">
              <template v-if="chatMode === 'dialogue'">
                <strong class="text-[var(--color-navy)]">对话模式：</strong>用户发送一条消息后，所有角色会并行响应一次，然后讨论结束。用户需要再次发送消息才会触发新的响应。
              </template>
              <template v-else>
                <strong class="text-[var(--color-navy)]">讨论模式：</strong>用户发送一条消息后，角色们会持续多轮讨论，互相评论和辩论。用户也可以随时插入新话题。
              </template>
            </div>
          </div>

          <!-- Footer -->
          <div class="px-6 py-5 bg-[var(--color-parchment)]/30 border-t border-[var(--color-border)] flex justify-end gap-3">
            <button
              class="px-5 py-2.5 text-sm font-medium text-[var(--color-navy)] hover:bg-[var(--color-parchment)] rounded-lg transition-colors border border-[var(--color-border)]"
              @click="emit('close')"
            >
              取消
            </button>
            <button
              class="px-5 py-2.5 text-sm font-medium text-[var(--color-gold-light)] bg-gradient-to-r from-[var(--color-navy)] to-[var(--color-navy-light)] hover:opacity-90 rounded-lg transition-opacity disabled:opacity-50 shadow-md"
              :disabled="saving"
              @click="handleSave"
            >
              {{ saving ? '保存中...' : '保存设置' }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.modal-enter-active,
.modal-leave-active {
  transition: all 0.35s cubic-bezier(0.4, 0, 0.2, 1);
}

.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}

.modal-enter-from > div:last-child,
.modal-leave-to > div:last-child {
  transform: scale(0.95) translateY(10px);
}
</style>
