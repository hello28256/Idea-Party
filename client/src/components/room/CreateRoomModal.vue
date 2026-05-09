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
    <div
      v-if="show"
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
      @click.self="handleClose"
    >
      <div class="bg-white rounded-lg p-6 w-full max-w-md shadow-xl">
        <h2 class="text-xl font-semibold text-text-primary mb-4">创建聊天室</h2>

        <form @submit.prevent="handleSubmit" class="space-y-4">
          <Input
            v-model="name"
            label="聊天室名称"
            placeholder="例如：哲学讨论群"
            :error="error && !name.trim() ? '请输入聊天室名称' : undefined"
            required
          />

          <div class="flex flex-col gap-1">
            <label class="text-label text-text-secondary">主题（可选）</label>
            <textarea
              v-model="topic"
              class="input min-h-[80px] resize-none"
              placeholder="讨论什么话题？"
              rows="3"
            />
          </div>

          <p v-if="error && name.trim()" class="text-sm text-destructive">{{ error }}</p>

          <div class="flex justify-end gap-3 pt-2">
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
              创建
            </Button>
          </div>
        </form>
      </div>
    </div>
  </Teleport>
</template>
