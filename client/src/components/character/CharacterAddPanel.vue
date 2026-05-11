<script setup lang="ts">
import { ref, watch } from 'vue'
import type { Character } from '@/types'
import { useCharacterStore } from '@/stores/character'
import { charactersApi } from '@/api/characters'
import CharacterCard from './CharacterCard.vue'
import Button from '@/components/ui/Button.vue'
import Input from '@/components/ui/Input.vue'

interface Props {
  show: boolean
  editingCharacter?: Character | null
}

const props = withDefaults(defineProps<Props>(), {
  editingCharacter: null
})

const emit = defineEmits<{
  close: []
  characterAdded: [character: Character]
}>()

const characterStore = useCharacterStore()

// Form state
interface CharacterForm {
  name: string
  description: string
  avatarUrl: string
  prompt: string
}

const mode = ref<'create' | 'edit'>('create')
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

// Reset form when props change
watch(() => props.show, (newShow) => {
  if (newShow) {
    if (props.editingCharacter) {
      mode.value = 'edit'
      form.value = {
        name: props.editingCharacter.name,
        description: props.editingCharacter.description || '',
        avatarUrl: props.editingCharacter.avatarUrl || '',
        prompt: props.editingCharacter.prompt || ''
      }
      avatarPreview.value = props.editingCharacter.avatarUrl || null
    } else {
      mode.value = 'create'
      form.value = { name: '', description: '', avatarUrl: '', prompt: '' }
      avatarPreview.value = null
    }
    error.value = null
    // Fetch presets if not loaded
    if (characterStore.presets.length === 0) {
      characterStore.fetchPresets()
    }
  }
})

async function handleSave() {
  if (!form.value.name.trim()) {
    error.value = '请输入角色名称'
    return
  }

  loading.value = true
  error.value = null

  try {
    let result: Character | null = null
    if (mode.value === 'edit' && props.editingCharacter) {
      result = await characterStore.updateCharacter(props.editingCharacter.id, form.value)
    } else {
      result = await characterStore.createCharacter(form.value)
    }

    if (result) {
      emit('characterAdded', result)
      emit('close')
    } else {
      error.value = characterStore.error || '操作失败'
    }
  } finally {
    loading.value = false
  }
}

function handlePresetSelect(character: Character) {
  emit('characterAdded', character)
  emit('close')
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
    // Reset file input
    if (fileInputRef.value) {
      fileInputRef.value.value = ''
    }
  }
}
</script>

<template>
  <Teleport to="body">
    <Transition name="panel">
      <div
        v-if="show"
        class="fixed inset-y-0 right-0 w-80 bg-gradient-to-b from-[var(--color-ivory)] to-[var(--color-cream)] shadow-2xl z-50 flex flex-col"
      >
        <!-- Header -->
        <div class="flex items-center justify-between p-5 border-b border-[var(--color-border)]">
          <h2 class="text-lg font-semibold text-[var(--color-navy)] font-['Playfair_Display']">
            {{ mode === 'create' ? '创建角色' : '编辑角色' }}
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

        <!-- Tabs -->
        <div class="flex border-b border-[var(--color-border)]">
          <button
            class="flex-1 py-3 text-sm font-medium text-[var(--color-gold)] border-b-2 border-[var(--color-gold)] bg-[var(--color-parchment)]/50"
          >
            创建角色
          </button>
          <button
            class="flex-1 py-3 text-sm font-medium text-[var(--color-text-secondary)] hover:text-[var(--color-navy)] transition-colors"
          >
            角色库
          </button>
        </div>

        <!-- Content -->
        <div class="flex-1 overflow-y-auto p-5">
          <!-- Create Tab -->
          <div class="space-y-5">
            <div>
              <label class="block text-sm font-medium text-[var(--color-navy)] mb-2">
                角色名称 <span class="text-[var(--color-destructive)]">*</span>
              </label>
              <Input
                v-model="form.name"
                placeholder="请输入角色名称"
              />
            </div>

            <div>
              <label class="block text-sm font-medium text-[var(--color-navy)] mb-2">
                角色描述
              </label>
              <textarea
                v-model="form.description"
                rows="3"
                placeholder="请输入角色描述"
                class="w-full px-3 py-2.5 text-sm border border-[var(--color-border)] rounded-lg focus:outline-none focus:ring-2 focus:ring-[var(--color-gold)] focus:border-transparent resize-none bg-[var(--color-ivory)] text-[var(--color-text-primary)] transition-all"
              ></textarea>
            </div>

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
                    class="px-3 py-2 text-sm font-medium text-[var(--color-gold-dark)] bg-[var(--color-gold)]/10 border border-[var(--color-gold)]/30 rounded-lg hover:bg-[var(--color-gold)]/20 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
                  >
                    {{ uploadingAvatar ? '上传中...' : '上传头像' }}
                  </button>
                  <p class="mt-1 text-xs text-[var(--color-text-muted)]">
                    支持 JPEG、PNG、GIF、WebP，不超过 5MB
                  </p>
                </div>
              </div>
              <input
                v-model="form.avatarUrl"
                type="hidden"
              />
            </div>

            <div>
              <label class="block text-sm font-medium text-[var(--color-navy)] mb-2">
                角色设定 (Prompt)
              </label>
              <textarea
                v-model="form.prompt"
                rows="4"
                placeholder="输入角色设定，用于定义 AI 角色的行为和风格"
                class="w-full px-3 py-2.5 text-sm border border-[var(--color-border)] rounded-lg focus:outline-none focus:ring-2 focus:ring-[var(--color-gold)] focus:border-transparent resize-none bg-[var(--color-ivory)] text-[var(--color-text-primary)] transition-all"
              ></textarea>
              <div class="mt-3 flex gap-2">
                <button
                  type="button"
                  @click="handleGeneratePrompt"
                  :disabled="generatingPrompt"
                  class="flex-1 px-3 py-2 text-sm font-medium text-[var(--color-gold-dark)] bg-[var(--color-gold)]/10 border border-[var(--color-gold)]/30 rounded-lg hover:bg-[var(--color-gold)]/20 disabled:opacity-50 disabled:cursor-not-allowed transition-colors flex items-center justify-center gap-1.5"
                >
                  <svg v-if="generatingPrompt" class="w-4 h-4 animate-spin" fill="none" viewBox="0 0 24 24">
                    <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
                    <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
                  </svg>
                  <span>{{ generatingPrompt ? '生成中...' : 'AI 生成提示词' }}</span>
                </button>
              </div>
              <p class="mt-2 text-xs text-[var(--color-text-muted)]">
                根据角色名称联网生成，或根据描述内容智能生成
              </p>
            </div>

            <p v-if="error" class="text-sm text-[var(--color-destructive)]">
              {{ error }}
            </p>
          </div>

          <!-- Library Tab (Preset Characters) -->
          <div v-if="characterStore.presets.length > 0" class="space-y-3 mt-6">
            <p class="text-sm text-[var(--color-text-secondary)] mb-3">点击选择预设角色：</p>
            <CharacterCard
              v-for="preset in characterStore.presets"
              :key="preset.id"
              :character="preset"
              @select="handlePresetSelect"
            />
          </div>
        </div>

        <!-- Footer -->
        <div class="p-5 border-t border-[var(--color-border)]">
          <Button
            @click="handleSave"
            :loading="loading"
            variant="primary"
            class="w-full"
          >
            {{ mode === 'create' ? '创建' : '保存修改' }}
          </Button>
        </div>
      </div>
    </Transition>

    <!-- Backdrop -->
    <Transition name="fade">
      <div
        v-if="show"
        class="fixed inset-0 bg-black/25 backdrop-blur-sm z-40"
        @click="handleClose"
      ></div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.panel-enter-active,
.panel-leave-active {
  transition: transform 0.35s cubic-bezier(0.4, 0, 0.2, 1);
}

.panel-enter-from,
.panel-leave-to {
  transform: translateX(100%);
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.3s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
