<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import type { Character } from '@/types'
import { useCharacterStore } from '@/stores/character'
import { useAuthStore } from '@/stores/auth'
import { charactersApi } from '@/api/characters'

interface Props {
  show: boolean
  mode?: 'create' | 'edit'
  character?: Character | null
}

const props = withDefaults(defineProps<Props>(), {
  mode: 'create',
  character: null
})

const emit = defineEmits<{
  close: []
  created: [character: Character]
  updated: [character: Character]
}>()

const characterStore = useCharacterStore()
const authStore = useAuthStore()

const isEditMode = computed(() => props.mode === 'edit')

// Form state
interface CharacterForm {
  name: string
  description: string
  avatarUrl: string
  prompt: string
}

const form = ref<CharacterForm>({
  name: '',
  description: '',
  avatarUrl: '',
  prompt: ''
})
const loading = ref(false)
const generatingPrompt = ref(false)
const error = ref<string | null>(null)
const uploadingAvatar = ref(false)
const avatarPreview = ref<string | null>(null)
const fileInputRef = ref<HTMLInputElement | null>(null)

// Reset form when shown
watch(() => props.show, (newShow) => {
  if (newShow) {
    error.value = null
    // In edit mode, initialize form with character data
    if (isEditMode.value && props.character) {
      console.log('[edit modal] initializing with character:', props.character)
      form.value = {
        name: props.character.name || '',
        description: props.character.description || '',
        avatarUrl: props.character.avatarUrl || '',
        prompt: props.character.prompt || ''
      }
      avatarPreview.value = props.character.avatarUrl || null
    } else {
      form.value = {
        name: '',
        description: '',
        avatarUrl: '',
        prompt: ''
      }
      avatarPreview.value = null
    }
  }
}, { immediate: true })

async function handleSubmit() {
  if (!form.value.name.trim()) {
    error.value = '请输入角色名称'
    return
  }

  if (!authStore.user && !isEditMode.value) {
    error.value = '请先登录'
    return
  }

  // Check for duplicate name (only in create mode)
  if (!isEditMode.value && authStore.user && characterStore.hasDuplicateName(authStore.user.id, form.value.name.trim(), undefined)) {
    error.value = '你已经创建过这个角色了'
    return
  }

  loading.value = true
  error.value = null

  try {
    let result: Character | null = null

    if (isEditMode.value && props.character) {
      // Update existing character
      result = await characterStore.updateCharacter(props.character.id, {
        name: form.value.name.trim(),
        description: form.value.description.trim(),
        avatarUrl: form.value.avatarUrl,
        prompt: form.value.prompt.trim()
      })
      if (result) {
        emit('updated', result)
        emit('close')
      } else {
        error.value = characterStore.error || '更新角色失败'
      }
    } else {
      // Create new character
      if (!authStore.user) {
        error.value = '请先登录'
        return
      }
      result = await characterStore.createCharacter({
        name: form.value.name.trim(),
        description: form.value.description.trim(),
        avatarUrl: form.value.avatarUrl,
        prompt: form.value.prompt.trim(),
        ownerId: authStore.user.id
      })
      if (result) {
        emit('created', result)
        emit('close')
      } else {
        error.value = characterStore.error || '创建角色失败'
      }
    }
  } catch (e: any) {
    console.error('[CreateCharacterModal] Error:', e)
    error.value = e.response?.data?.message || e.message || '操作失败'
  } finally {
    loading.value = false
  }
}

async function handleGeneratePrompt() {
  if (!form.value.name.trim() && !form.value.description.trim()) {
    error.value = '请输入角色名称或描述'
    return
  }

  generatingPrompt.value = true
  error.value = null

  try {
    const response = await charactersApi.generatePrompt({
      name: form.value.name.trim() || undefined,
      description: form.value.description.trim() || undefined
    })
    form.value.prompt = response.data.prompt
  } catch (e: any) {
    error.value = e.response?.data?.message || '生成提示词失败'
    console.error('[DEBUG] handleGeneratePrompt failed:', e)
  } finally {
    generatingPrompt.value = false
  }
}

function handleClose() {
  emit('close')
}

function triggerAvatarUpload() {
  fileInputRef.value?.click()
}

async function handleAvatarFileChange(event: Event) {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  if (!file) return

  // Validate file type
  const allowedTypes = ['image/jpeg', 'image/png', 'image/gif', 'image/webp']
  if (!allowedTypes.includes(file.type)) {
    error.value = '只支持 JPEG、PNG、GIF、WebP 格式的图片'
    return
  }

  // Validate file size (5MB)
  if (file.size > 5 * 1024 * 1024) {
    error.value = '图片大小不能超过 5MB'
    return
  }

  uploadingAvatar.value = true
  error.value = null

  try {
    const url = await characterStore.uploadAvatar(file)
    if (url) {
      form.value.avatarUrl = url
      avatarPreview.value = url
    } else {
      error.value = '头像上传失败'
    }
  } finally {
    uploadingAvatar.value = false
    if (fileInputRef.value) {
      fileInputRef.value.value = ''
    }
  }
}
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div
        v-if="show"
        class="fixed inset-0 z-50 flex items-center justify-center"
      >
        <!-- Backdrop -->
        <div
          class="absolute inset-0 bg-black/40 backdrop-blur-sm"
          @click="handleClose"
        ></div>

        <!-- Modal Content -->
        <div class="relative bg-gradient-to-b from-[var(--color-ivory)] to-[var(--color-cream)] rounded-2xl shadow-2xl w-full max-w-lg mx-4 max-h-[90vh] overflow-hidden flex flex-col">
          <!-- Header -->
          <div class="flex items-center justify-between p-6 border-b border-[var(--color-border)]">
            <h2 class="text-xl font-semibold text-[var(--color-navy)] font-['Playfair_Display']">
              {{ isEditMode ? '编辑角色' : '创建角色' }}
            </h2>
            <button
              @click="handleClose"
              class="p-2 rounded-lg hover:bg-[var(--color-parchment)] transition-colors"
            >
              <svg class="w-5 h-5 text-[var(--color-text-secondary)]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>

          <!-- Form -->
          <div class="flex-1 overflow-y-auto p-6 space-y-5">
            <!-- Name -->
            <div>
              <label class="block text-sm font-medium text-[var(--color-navy)] mb-2">
                角色名称 <span class="text-[var(--color-destructive)]">*</span>
              </label>
              <input
                v-model="form.name"
                type="text"
                placeholder="请输入角色名称"
                class="w-full px-4 py-2.5 text-sm border border-[var(--color-border)] rounded-lg focus:outline-none focus:ring-2 focus:ring-[var(--color-gold)] focus:border-transparent bg-[var(--color-ivory)] text-[var(--color-text-primary)] transition-all"
              />
            </div>

            <!-- Description -->
            <div>
              <label class="block text-sm font-medium text-[var(--color-navy)] mb-2">
                角色描述
              </label>
              <textarea
                v-model="form.description"
                rows="3"
                placeholder="请输入角色描述"
                class="w-full px-4 py-2.5 text-sm border border-[var(--color-border)] rounded-lg focus:outline-none focus:ring-2 focus:ring-[var(--color-gold)] focus:border-transparent resize-none bg-[var(--color-ivory)] text-[var(--color-text-primary)] transition-all"
              ></textarea>
            </div>

            <!-- Avatar -->
            <div>
              <label class="block text-sm font-medium text-[var(--color-navy)] mb-2">
                头像
              </label>
              <input
                ref="fileInputRef"
                type="file"
                accept="image/jpeg,image/png,image/gif,image/webp"
                class="hidden"
                @change="handleAvatarFileChange"
              />
              <div class="flex items-center gap-4">
                <div
                  class="w-20 h-20 rounded-full border-2 border-dashed border-[var(--color-border)] overflow-hidden bg-[var(--color-parchment)] flex items-center justify-center cursor-pointer hover:border-[var(--color-gold)] transition-colors"
                  :class="{ 'border-[var(--color-gold)]': avatarPreview }"
                  @click="triggerAvatarUpload"
                >
                  <img
                    v-if="avatarPreview"
                    :src="avatarPreview"
                    alt="Avatar preview"
                    class="w-full h-full object-cover"
                  />
                  <svg v-else class="w-8 h-8 text-[var(--color-text-muted)]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z" />
                  </svg>
                </div>
                <div class="flex-1">
                  <button
                    type="button"
                    @click="triggerAvatarUpload"
                    :disabled="uploadingAvatar"
                    class="px-4 py-2 text-sm font-medium text-[var(--color-gold-dark)] bg-[var(--color-gold)]/10 border border-[var(--color-gold)]/30 rounded-lg hover:bg-[var(--color-gold)]/20 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
                  >
                    {{ uploadingAvatar ? '上传中...' : '上传头像' }}
                  </button>
                  <p class="mt-1 text-xs text-[var(--color-text-muted)]">
                    支持 JPEG、PNG、GIF、WebP，不超过 5MB
                  </p>
                </div>
              </div>
            </div>

            <!-- Prompt -->
            <div>
              <label class="block text-sm font-medium text-[var(--color-navy)] mb-2">
                角色设定 (Prompt)
              </label>
              <textarea
                v-model="form.prompt"
                rows="4"
                placeholder="输入角色设定，用于定义 AI 角色的行为和风格"
                class="w-full px-4 py-2.5 text-sm border border-[var(--color-border)] rounded-lg focus:outline-none focus:ring-2 focus:ring-[var(--color-gold)] focus:border-transparent resize-none bg-[var(--color-ivory)] text-[var(--color-text-primary)] transition-all"
              ></textarea>
              <button
                type="button"
                @click="handleGeneratePrompt"
                :disabled="generatingPrompt"
                class="mt-3 px-4 py-2 text-sm font-medium text-[var(--color-gold-dark)] bg-[var(--color-gold)]/10 border border-[var(--color-gold)]/30 rounded-lg hover:bg-[var(--color-gold)]/20 disabled:opacity-50 disabled:cursor-not-allowed transition-colors flex items-center gap-2"
              >
                <svg v-if="generatingPrompt" class="w-4 h-4 animate-spin" fill="none" viewBox="0 0 24 24">
                  <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
                  <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
                </svg>
                {{ generatingPrompt ? '生成中...' : 'AI 生成提示词' }}
              </button>
              <p class="mt-2 text-xs text-[var(--color-text-muted)]">
                根据角色名称联网生成，或根据描述内容智能生成
              </p>
            </div>

            <!-- Error -->
            <p v-if="error" class="text-sm text-[var(--color-destructive)]">
              {{ error }}
            </p>
          </div>

          <!-- Footer -->
          <div class="p-6 border-t border-[var(--color-border)] flex items-center justify-end gap-3">
            <button
              @click="handleClose"
              class="px-5 py-2.5 text-sm font-medium text-[var(--color-text-secondary)] hover:text-[var(--color-navy)] transition-colors"
            >
              取消
            </button>
            <button
              @click="handleSubmit"
              :disabled="loading"
              class="px-5 py-2.5 text-sm font-medium rounded-xl transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
              style="background: var(--color-navy); color: white;"
            >
              {{ loading ? '处理中...' : (isEditMode ? '保存修改' : '创建角色') }}
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
  transition: opacity 0.3s ease;
}

.modal-enter-active .relative,
.modal-leave-active .relative {
  transition: transform 0.3s ease, opacity 0.3s ease;
}

.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}

.modal-enter-from .relative,
.modal-leave-to .relative {
  transform: scale(0.95) translateY(10px);
  opacity: 0;
}
</style>
