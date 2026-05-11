<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoomStore } from '@/stores/room'
import Button from '@/components/ui/Button.vue'
import Input from '@/components/ui/Input.vue'

interface Props {
  show: boolean
}

interface Emits {
  close: []
  created: [roomId: string]
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()

const roomStore = useRoomStore()

const name = ref('')
const topic = ref('')
const loading = ref(false)
const error = ref<string | null>(null)

watch(() => props.show, (newShow) => {
  if (!newShow) {
    name.value = ''
    topic.value = ''
    error.value = null
  }
})

async function handleSubmit() {
  if (!name.value.trim()) {
    error.value = '请输入聊天室名称'
    return
  }

  loading.value = true
  error.value = null

  try {
    const room = await roomStore.createRoom(name.value.trim(), topic.value.trim() || undefined)
    emit('created', room.id)
    emit('close')
  } catch (e) {
    error.value = e instanceof Error ? e.message : '创建失败'
  } finally {
    loading.value = false
  }
}

function handleClose() {
  emit('close')
}
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div
        v-if="show"
        class="fixed inset-0 z-50 flex items-center justify-center px-4"
        @click.self="handleClose"
      >
        <div class="absolute inset-0 bg-black/40 backdrop-blur-sm"></div>
        <div class="relative w-full max-w-md bg-gradient-to-b from-[var(--color-ivory)] to-[var(--color-cream)] rounded-2xl shadow-2xl overflow-hidden">
          <!-- Decorative top border -->
          <div class="absolute top-0 left-0 right-0 h-1 bg-gradient-to-r from-[var(--color-gold-dark)] via-[var(--color-gold)] to-[var(--color-gold-dark)]"></div>

          <!-- Header -->
          <div class="flex items-center justify-between p-5 border-b border-[var(--color-border)]">
            <div class="flex items-center gap-3">
              <div class="w-10 h-10 rounded-xl bg-gradient-to-br from-[var(--color-navy)] to-[var(--color-navy-light)] flex items-center justify-center">
                <svg class="w-5 h-5 text-[var(--color-gold)]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
                </svg>
              </div>
              <h2 class="text-lg font-semibold text-[var(--color-navy)] font-['Playfair_Display']">创建聊天室</h2>
            </div>
            <button
              @click="handleClose"
              class="p-2 rounded-lg hover:bg-[var(--color-parchment)] transition-colors"
            >
              <svg class="w-5 h-5 text-[var(--color-text-secondary)]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>

          <!-- Content -->
          <form @submit.prevent="handleSubmit" class="p-5 space-y-5">
            <Input
              v-model="name"
              label="聊天室名称"
              placeholder="例如：哲学讨论群"
              :error="error && !name.trim() ? '请输入聊天室名称' : undefined"
              required
            />

            <div class="flex flex-col gap-2">
              <label class="text-sm font-medium text-[var(--color-navy)]">主题（可选）</label>
              <textarea
                v-model="topic"
                class="input min-h-[80px] resize-none"
                placeholder="讨论什么话题？"
                rows="3"
              />
            </div>

            <p v-if="error && name.trim()" class="text-sm text-[var(--color-destructive)]">{{ error }}</p>

            <!-- Action Buttons -->
            <div class="flex items-center justify-end gap-3 pt-3">
              <Button
                type="button"
                variant="secondary"
                @click="handleClose"
                :disabled="loading"
              >
                取消
              </Button>
              <Button
                type="submit"
                variant="primary"
                :loading="loading"
                :disabled="!name.trim()"
              >
                <svg v-if="!loading" class="w-4 h-4 mr-1.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
                </svg>
                创建
              </Button>
            </div>
          </form>
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

.modal-enter-from .relative,
.modal-leave-to .relative {
  transform: scale(0.95) translateY(10px);
}
</style>
