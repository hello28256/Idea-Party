<script setup lang="ts">
// 角色添加/编辑抽屉面板
// 同一组件承担"创建角色"和"编辑现有角色"两种模式，由 editingCharacter 是否传入决定。
// 与 characterStore（持久化）、authStore（ownerId 注入）、charactersApi（AI 生成 prompt）配合，
// 完成角色 CRUD 的前端编排；通过 emit('characterAdded') 把结果回传给父组件（通常是房间配置面板）。
import { ref, watch } from 'vue'
import type { Character } from '@/types'
import { useCharacterStore } from '@/stores/character'
import { useAuthStore } from '@/stores/auth'
import { charactersApi } from '@/api/characters'
import CharacterCard from './CharacterCard.vue'
import Button from '@/components/ui/Button.vue'
import Input from '@/components/ui/Input.vue'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'
import { useToast } from '@/composables/useToast'

// show：父组件通过 v-model 控制抽屉显隐
// editingCharacter：传入非空时进入编辑模式，null/undefined 时进入创建模式
interface Props {
  show: boolean
  editingCharacter?: Character | null
}

const props = withDefaults(defineProps<Props>(), {
  editingCharacter: null
})

// close：父组件收到后置 show=false；characterAdded 携带写入后的角色（含 server id / avatarUrl）
const emit = defineEmits<{
  close: []
  characterAdded: [character: Character]
}>()

const characterStore = useCharacterStore()
const authStore = useAuthStore()
const toast = useToast()

// 表单字段统一在一个 ref 对象里，便于整体重置和 watch 批量更新
interface CharacterForm {
  name: string
  description: string
  avatarUrl: string
  prompt: string
}

// 模式状态：用单一字段区分，避免组件被父级拆成两份导致表单/校验/布局重复维护
const mode = ref<'create' | 'edit'>('create')
const form = ref<CharacterForm>({
  name: '',
  description: '',
  avatarUrl: '',
  prompt: ''
})
const loading = ref(false)
const generatingPrompt = ref(false)
const showDeleteConfirm = ref(false)
const error = ref<string | null>(null)
const uploadingAvatar = ref(false)
const avatarPreview = ref<string | null>(null)
const fileInputRef = ref<HTMLInputElement | null>(null)

// 面板打开时同步外部状态：模式、表单字段、预览图。
// 必须在这里重置，不能依赖 v-model，否则上次编辑的残留会污染新一次"创建"。
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
    // 仅在首次打开时拉取预设，避免每次开关抽屉都重复请求
    if (characterStore.presets.length === 0) {
      characterStore.fetchPresets()
    }
  }
})

// 保存：根据 mode 走 create/update；store 返回 null 时表示业务失败，error 兜底来自 store.error
// 成功路径同时触发 characterAdded + close，让父级列表能立即拿到新对象（无需再 fetch 一次）。
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
      result = await characterStore.updateCharacter(props.editingCharacter.id, {
        name: form.value.name,
        description: form.value.description,
        avatarUrl: form.value.avatarUrl,
        prompt: form.value.prompt
      })
    } else {
      result = await characterStore.createCharacter({
        name: form.value.name,
        description: form.value.description,
        avatarUrl: form.value.avatarUrl,
        prompt: form.value.prompt,
        ownerId: authStore.user?.id
      })
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

// 调用后端 LLM 生成角色 prompt：name/description 任一非空即可触发
// 失败时优先展示后端业务 message（更友好），再退回通用文案
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

async function handleDelete() {
  if (!props.editingCharacter) return
  showDeleteConfirm.value = true
}

// 二次确认后的实际删除：store 失败时直接复用其 error（已在 store 层捕获网络/业务异常）
// 成功路径主动 toast 通知，因为面板关闭后用户可能已经看不到上下文
async function confirmDelete() {
  if (!props.editingCharacter) return
  const name = props.editingCharacter?.name || ''
  loading.value = true
  error.value = null

  try {
    const success = await characterStore.deleteCharacter(props.editingCharacter.id)
    if (success) {
      showDeleteConfirm.value = false
      toast.success(`已删除角色「${name}」`)
      emit('close')
    } else {
      // store 内部已捕获错误：从 characterStore.error 取
      const msg = characterStore.error || '删除失败'
      error.value = msg
      toast.error(msg)
      showDeleteConfirm.value = false
    }
  } finally {
    loading.value = false
  }
}

function triggerAvatarUpload() {
  fileInputRef.value?.click()
}

// 本地校验 + 上传：白名单 mime + 5MB 体积限制，目的是在请求前拦截明显非法文件
// 清空 fileInput.value 是关键，否则同一张图无法触发第二次 change 事件
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
          <div class="flex items-center justify-between">
            <button
              v-if="mode === 'edit'"
              @click="handleDelete"
              class="px-4 py-2.5 text-sm font-medium rounded-xl transition-colors"
              style="background: #111; color: white;"
              onmouseover="this.style.background='#333'"
              onmouseout="this.style.background='#111'"
            >
              删除角色
            </button>
            <div class="flex gap-3" :class="{ 'ml-auto': mode === 'create' }">
              <button
                @click="handleClose"
                class="px-4 py-2.5 text-sm font-medium text-[var(--color-text-secondary)] hover:text-[var(--color-navy)] transition-colors"
              >
                取消
              </button>
              <Button
                @click="handleSave"
                :loading="loading"
                variant="primary"
              >
                {{ mode === 'create' ? '创建' : '保存修改' }}
              </Button>
            </div>
          </div>
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

  <ConfirmDialog
    :show="showDeleteConfirm"
    title="删除角色"
    :message="`确定要删除角色「${props.editingCharacter?.name || ''}」吗？此操作不可恢复。`"
    confirm-text="删除"
    cancel-text="取消"
    :loading="loading"
    @confirm="confirmDelete"
    @cancel="showDeleteConfirm = false"
  />
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
