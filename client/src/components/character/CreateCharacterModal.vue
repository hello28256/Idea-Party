<script setup lang="ts">
// CreateCharacterModal：统一处理角色创建、编辑、删除，以及在聊天室上下文下从角色库挑选已存在角色的弹窗。
// 同一份组件通过 mode + context 双维度切换行为，避免在多个入口处重复实现表单与上传逻辑。
import { ref, watch, computed } from 'vue'
import type { Character } from '@/types'
import { useCharacterStore } from '@/stores/character'
import { useAuthStore } from '@/stores/auth'
import { charactersApi } from '@/api/characters'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'
import { useToast } from '@/composables/useToast'

// Props：show 控制显隐；mode 决定新建还是编辑；context 决定标题文案、是否出现 Tab、回调事件名。
// 在 room 上下文下从角色库选角色时复用同一组件，通过 addedToRoom 事件把已有角色交给父页面挂入聊天室。

// show：父组件 v-model 控制显隐
// mode：create=新建表单，edit=编辑现有角色（编辑模式隐藏 Tab）
// character：编辑模式下携带要回填的角色；创建模式忽略
// context：character-library（角色库内嵌） vs room（房间内添加角色）—— 影响标题文案和 Tab
// roomId：context=room 时由父组件传入，用于语义/校验
interface Props {
  show: boolean
  mode?: 'create' | 'edit'
  character?: Character | null
  context?: 'character-library' | 'room'
  roomId?: string | null
}

const props = withDefaults(defineProps<Props>(), {
  mode: 'create',
  character: null,
  context: 'character-library',
  roomId: null
})

// close：遮罩 / 关闭按钮触发
// created：创建模式提交成功后抛出，携带服务端返回的 Character（含 id / avatarUrl）
// updated：编辑模式保存成功后抛出
// deleted：编辑模式删除成功后抛出
// addedToRoom：仅在 room 上下文从角色库挑选时抛出，父页面将其挂入聊天室
const emit = defineEmits<{
  close: []
  created: [character: Character]
  updated: [character: Character]
  deleted: [characterId: string]
  addedToRoom: [character: Character]
}>()

const characterStore = useCharacterStore()
const authStore = useAuthStore()
const toast = useToast()

const isEditMode = computed(() => props.mode === 'edit')

// Form state：与后端 Character 字段对齐的最小子集，仅承载用户可编辑部分。
// avatarUrl 与后端真实上传后的 URL 解耦，本地另存 avatarPreview 用于即时回显未提交的本地预览。
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
const deleting = ref(false)
const error = ref<string | null>(null)
const uploadingAvatar = ref(false)
const showDeleteConfirm = ref(false)
const avatarPreview = ref<string | null>(null)
const fileInputRef = ref<HTMLInputElement | null>(null)

// Tab state for room context
const activeTab = ref<'create' | 'library'>('create')

// 弹窗每次重新显示时重置表单：避免上次编辑残留。
// 编辑模式下从 props.character 回填，新建模式下清空；avatarPreview 跟随 avatarUrl 同步，便于提交后立刻看到新头像。
watch(() => props.show, (newShow) => {
  if (newShow) {
    error.value = null
    activeTab.value = 'create'
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

// handleSubmit：表单提交入口。根据 isEditMode 走 update / create 分支，统一通过 characterStore 调用后端。
// store 返回 null + error 表示失败已被捕获（不抛错），因此同时回填 error 字段并把后端 message 透传给用户。
async function handleSubmit() {
  if (!form.value.name.trim()) {
    error.value = '请输入角色名称'
    return
  }

  if (!authStore.user && !isEditMode.value) {
    error.value = '请先登录'
    return
  }

  // 仅在创建模式下做重名校验：编辑模式允许同名保存（场景是用户改其他字段时不必先改名字）。
  // 第三个参数传 undefined 表示忽略当前 id，store 内部据此判定是「新增校验」还是「编辑校验」。
  if (!isEditMode.value && authStore.user && characterStore.hasDuplicateName(authStore.user.id, form.value.name.trim(), undefined)) {
    error.value = '你已经创建过这个角色了'
    return
  }

  loading.value = true
  error.value = null

  try {
    let result: Character | null = null

    if (isEditMode.value && props.character) {
      // 编辑路径：传更新后的字段到 store，由 store 负责同步本地列表与服务端调用。
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
      // 创建路径：ownerId 必须从 authStore 取，不能让前端伪造；这是后端做权限校验的关键依据。
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

// handleGeneratePrompt：调用后端的 AI 联网检索 / 描述生成接口，用返回的 prompt 覆盖当前表单的 prompt。
// 仅校验「名字或描述至少有一个」，避免在两者都空时让 AI 端做无效推理；网络错误统一落到 error 字段，由 UI 顶部展示。
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

async function handleDeleteCharacter() {
  if (!isEditMode.value || !props.character?.id) return
  showDeleteConfirm.value = true
}

// handleDeleteCharacter 与 confirmDelete 拆成两步：先打开 ConfirmDialog 让用户二次确认，确认后才真正调用删除 API。
// store 的 deleteCharacter 返回 boolean：false 表示后端返回了错误且被 store 内部捕获（非 throw），因此要主动读取 characterStore.error。
async function confirmDelete() {
  if (!props.character?.id) return
  const name = props.character?.name || ''
  try {
    deleting.value = true
    error.value = null
    // 必须接 store 返回值：false 表示后端返回了错误（不 throw 但被 store 捕获）
    const success = await characterStore.deleteCharacter(props.character.id)
    if (success) {
      showDeleteConfirm.value = false
      toast.success(`已删除角色「${name}」`)
      emit('deleted', props.character.id)
      emit('close')
    } else {
      // store 内部已捕获，错误信息在 characterStore.error
      const msg = characterStore.error || '删除失败'
      error.value = msg
      toast.error(msg)
      showDeleteConfirm.value = false
    }
  } catch (e: any) {
    console.error('[DEBUG] handleDeleteCharacter failed:', e)
    const msg = e.response?.data?.message || e.message || '删除角色失败'
    error.value = msg
    toast.error(msg)
  } finally {
    deleting.value = false
  }
}

// handleSelectFromLibrary：仅在 room 上下文下有意义——从用户已有角色库中挑一个直接挂入当前聊天室。
// 该函数不会创建任何新角色，所以走 addedToRoom 事件而非 created；其他上下文调用此函数是 no-op，避免误触发。
function handleSelectFromLibrary(character: Character) {
  if (props.context === 'room' && props.roomId) {
    emit('addedToRoom', character)
    emit('close')
  }
}

function triggerAvatarUpload() {
  fileInputRef.value?.click()
}

// handleAvatarFileChange：客户端先做 MIME 与体积两道校验，失败时不发请求，减轻后端 / OSS 压力。
// 后端返回的图片 URL 同时回写到 form.avatarUrl（用于提交）和 avatarPreview（用于即时预览），保持两者一致避免视觉错位。
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
        class="character-modal-overlay"
        @click.self="handleClose"
      >
        <!-- Modal Container -->
        <div class="character-modal">
          <!-- Header -->
          <header class="character-modal-header">
            <div>
              <h2 class="character-modal-title">
                {{ isEditMode ? '编辑角色' : (context === 'room' ? '添加角色' : '创建角色') }}
              </h2>
              <p class="character-modal-subtitle">
                {{ isEditMode ? '完善角色资料、头像和提示词设定' : (context === 'room' ? '创建新角色或从角色库选择' : '完善角色资料、头像和提示词设定') }}
              </p>
            </div>
            <button class="modal-close" @click="handleClose">
              <svg width="20" height="20" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </header>

          <!-- Tabs (only in room context) -->
          <div v-if="context === 'room' && !isEditMode" class="modal-tabs">
            <button
              class="modal-tab"
              :class="{ active: activeTab === 'create' }"
              @click="activeTab = 'create'"
            >
              创建角色
            </button>
            <button
              class="modal-tab"
              :class="{ active: activeTab === 'library' }"
              @click="activeTab = 'library'"
            >
              角色库
            </button>
          </div>

          <!-- Body -->
          <div class="character-modal-body">
            <!-- Create Tab / Edit Mode -->
            <div v-if="activeTab === 'create' || isEditMode" class="character-form">
              <!-- Hidden file input -->
              <input
                ref="fileInputRef"
                type="file"
                accept="image/jpeg,image/png,image/gif,image/webp"
                class="hidden"
                @change="handleAvatarFileChange"
              />

              <!-- Name -->
              <div class="form-group">
                <label class="form-label">
                  角色名称 <span class="required">*</span>
                </label>
                <input
                  v-model="form.name"
                  type="text"
                  placeholder="请输入角色名称"
                  class="form-input"
                />
              </div>

              <!-- Description -->
              <div class="form-group">
                <label class="form-label">角色描述</label>
                <textarea
                  v-model="form.description"
                  rows="3"
                  placeholder="请输入角色描述"
                  class="form-textarea"
                ></textarea>
              </div>

              <!-- Avatar Upload -->
              <div class="form-group">
                <label class="form-label">角色头像</label>
                <div class="avatar-upload-row">
                  <div
                    class="avatar-preview-circle"
                    @click="triggerAvatarUpload"
                  >
                    <img
                      v-if="avatarPreview"
                      :src="avatarPreview"
                      alt="Avatar preview"
                    />
                    <svg v-else width="28" height="28" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z" />
                    </svg>
                  </div>
                  <div class="avatar-upload-info">
                    <span class="avatar-upload-title">点击上传头像</span>
                    <span class="avatar-upload-desc">支持 JPEG、PNG、GIF、WebP，不超过 5MB</span>
                    <button
                      type="button"
                      class="secondary-button"
                      @click="triggerAvatarUpload"
                      :disabled="uploadingAvatar"
                    >
                      <svg v-if="uploadingAvatar" class="w-4 h-4 spinning" fill="none" viewBox="0 0 24 24">
                        <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
                        <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
                      </svg>
                      {{ uploadingAvatar ? '上传中...' : '上传头像' }}
                    </button>
                  </div>
                </div>
              </div>

              <!-- Prompt -->
              <div class="form-group">
                <label class="form-label">角色设定 (Prompt)</label>
                <textarea
                  v-model="form.prompt"
                  rows="4"
                  placeholder="输入角色设定，用于定义 AI 角色的行为和风格"
                  class="form-textarea prompt-textarea"
                ></textarea>
                <button
                  type="button"
                  class="ai-generate-button"
                  :class="{ spinning: generatingPrompt }"
                  @click="handleGeneratePrompt"
                  :disabled="generatingPrompt"
                >
                  <svg v-if="generatingPrompt" class="w-4 h-4" fill="none" viewBox="0 0 24 24">
                    <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
                    <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
                  </svg>
                  {{ generatingPrompt ? '生成中...' : 'AI 生成提示词' }}
                </button>
                <p class="form-hint">根据角色名称联网生成，或根据描述内容智能生成</p>
              </div>

              <!-- Error -->
              <p v-if="error" class="form-error">{{ error }}</p>
            </div>

            <!-- Library Tab (for room context) -->
            <div v-if="context === 'room' && activeTab === 'library' && !isEditMode" class="character-library">
              <p class="library-hint">选择一个已有角色添加到聊天室：</p>
              <div class="library-list">
                <div
                  v-for="char in characterStore.characters.filter(c => c.ownerId === authStore.user?.id)"
                  :key="char.id"
                  class="library-character-card"
                  @click="handleSelectFromLibrary(char)"
                >
                  <img v-if="char.avatarUrl" :src="char.avatarUrl" :alt="char.name" class="library-char-avatar" />
                  <div v-else class="library-char-avatar-placeholder">{{ char.name?.charAt(0) }}</div>
                  <div class="library-char-info">
                    <strong>{{ char.name }}</strong>
                    <span>{{ char.description || '暂无描述' }}</span>
                  </div>
                </div>
                <div v-if="characterStore.characters.filter(c => c.ownerId === authStore.user?.id).length === 0" class="library-empty">
                  还没有创建过角色，请先创建角色
                </div>
              </div>
            </div>
          </div>

          <!-- Footer -->
          <footer v-if="activeTab === 'create' || isEditMode" class="character-modal-footer">
            <div class="footer-left">
              <button
                v-if="isEditMode"
                type="button"
                class="delete-character-button"
                @click="handleDeleteCharacter"
                :disabled="deleting"
              >
                {{ deleting ? '删除中...' : '删除角色' }}
              </button>
            </div>

            <div class="footer-actions">
              <button
                type="button"
                class="footer-cancel-btn"
                @click="handleClose"
              >
                取消
              </button>
              <button
                type="button"
                class="footer-submit-btn"
                @click="handleSubmit"
                :disabled="loading"
              >
                {{ loading ? '处理中...' : (isEditMode ? '保存修改' : '创建角色') }}
              </button>
            </div>
          </footer>
        </div>
      </div>
    </Transition>
  </Teleport>

  <ConfirmDialog
    :show="showDeleteConfirm"
    title="删除角色"
    :message="`确定要删除角色「${props.character?.name || ''}」吗？此操作不可恢复。`"
    confirm-text="删除"
    cancel-text="取消"
    :loading="deleting"
    @confirm="confirmDelete"
    @cancel="showDeleteConfirm = false"
  />
</template>

<style scoped>
/*** Light Mode Variables ***/
.character-modal-overlay {
  --overlay-bg: rgba(15, 23, 42, 0.06);
  --modal-bg: #ffffff;
  --modal-border: rgba(226, 232, 240, 0.95);
  --modal-shadow: 0 28px 90px rgba(15, 23, 42, 0.28);
  --header-bg: #ffffff;
  --header-border: rgba(226, 232, 240, 0.9);
  --footer-bg: #ffffff;
  --footer-border: rgba(226, 232, 240, 0.9);
  --body-bg: #ffffff;
  --text-primary: #0f172a;
  --text-secondary: rgba(71, 85, 105, 0.85);
  --text-muted: rgba(100, 116, 139, 0.8);
  --input-bg: #f8fafc;
  --input-border: rgba(203, 213, 225, 0.95);
  --input-focus-border: #0f172a;
  --input-shadow: 0 0 0 3px rgba(15, 23, 42, 0.08);
  --avatar-box-bg: #f8fafc;
  --avatar-box-border: rgba(148, 163, 184, 0.9);
  --avatar-preview-bg: #f1f5f9;
  --avatar-preview-color: #64748b;
  --avatar-preview-border: rgba(226, 232, 240, 0.9);
  --btn-primary-bg: #0f172a;
  --btn-primary-text: #ffffff;
  --btn-secondary-bg: rgba(248, 250, 252, 0.72);
  --btn-secondary-border: rgba(203, 213, 225, 0.55);
  --btn-secondary-text: #334155;
  --btn-ai-bg: rgba(234, 179, 8, 0.14);
  --btn-ai-border: rgba(202, 138, 4, 0.32);
  --btn-ai-text: #92400e;
  --focus-ring: rgba(148, 163, 184, 0.12);
  --error-color: #dc2626;
  --close-hover-bg: rgba(148, 163, 184, 0.18);
}

/*** Dark Mode Variables ***/
.dark .character-modal-overlay {
  --overlay-bg: transparent;
  --modal-bg: #0f172a;
  --modal-border: rgba(71, 85, 105, 0.85);
  --modal-shadow: 0 28px 90px rgba(0, 0, 0, 0.55);
  --header-bg: #0f172a;
  --header-border: rgba(71, 85, 105, 0.85);
  --footer-bg: #0f172a;
  --footer-border: rgba(71, 85, 105, 0.85);
  --body-bg: #0f172a;
  --text-primary: #f8fafc;
  --text-secondary: rgba(203, 213, 225, 0.72);
  --text-muted: rgba(148, 163, 184, 0.68);
  --input-bg: #1e293b;
  --input-border: rgba(71, 85, 105, 0.95);
  --input-focus-border: #94a3b8;
  --input-shadow: 0 0 0 3px rgba(148, 163, 184, 0.16);
  --avatar-box-bg: #1e293b;
  --avatar-box-border: rgba(100, 116, 139, 0.95);
  --avatar-preview-bg: #1e293b;
  --avatar-preview-color: #94a3b8;
  --avatar-preview-border: rgba(100, 116, 139, 0.95);
  --btn-primary-bg: #f8fafc;
  --btn-primary-text: #0f172a;
  --btn-secondary-bg: #1e293b;
  --btn-secondary-border: rgba(71, 85, 105, 0.95);
  --btn-secondary-text: #f8fafc;
  --btn-ai-bg: rgba(234, 179, 8, 0.16);
  --btn-ai-border: rgba(234, 179, 8, 0.35);
  --btn-ai-text: #facc15;
  --focus-ring: rgba(148, 163, 184, 0.15);
  --error-color: #fca5a5;
  --close-hover-bg: rgba(255, 255, 255, 0.12);
}

/*** Overlay ***/
.character-modal-overlay {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px;
  background: transparent !important;
  backdrop-filter: none !important;
  -webkit-backdrop-filter: none !important;
}

/*** Modal Container ***/
.character-modal {
  position: relative;
  width: min(760px, calc(100vw - 48px));
  max-height: min(860px, calc(100vh - 64px));
  display: flex;
  flex-direction: column;
  background: #ffffff !important;
  color: #0f172a !important;
  border: 1px solid rgba(226, 232, 240, 0.95) !important;
  border-radius: 24px;
  box-shadow: 0 28px 90px rgba(15, 23, 42, 0.28) !important;
  backdrop-filter: none !important;
  -webkit-backdrop-filter: none !important;
  overflow: hidden;
}

.character-modal::before,
.character-modal::after {
  display: none !important;
}

.character-modal-header,
.character-modal-body,
.character-modal-footer {
  position: relative;
  z-index: 1;
}

/*** Header ***/
.character-modal-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 28px 32px 20px;
  background: #ffffff !important;
  border-bottom: 1px solid rgba(226, 232, 240, 0.9) !important;
}

.character-modal-title {
  font-size: 24px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0;
}

.character-modal-subtitle {
  margin-top: 6px;
  font-size: 14px;
  color: var(--text-muted);
}

.modal-close {
  width: 36px;
  height: 36px;
  border-radius: 12px;
  border: none;
  background: transparent;
  color: var(--text-muted);
  display: grid;
  place-items: center;
  cursor: pointer;
  transition: all 0.15s;
}

.modal-close:hover {
  background: var(--close-hover-bg);
  color: var(--text-primary);
}

/* Tabs */
.modal-tabs {
  display: flex;
  border-bottom: 1px solid rgba(226, 232, 240, 0.9);
  padding: 0 32px;
  background: var(--header-bg);
}

.modal-tab {
  padding: 14px 20px;
  font-size: 14px;
  font-weight: 500;
  color: var(--text-muted);
  background: transparent;
  border: none;
  border-bottom: 2px solid transparent;
  cursor: pointer;
  transition: all 0.15s;
  margin-bottom: -1px;
}

.modal-tab:hover {
  color: var(--text-primary);
}

.modal-tab.active {
  color: var(--text-primary);
  border-bottom-color: var(--text-primary);
}

/* Library */
.character-library {
  padding: 8px 0;
}

.library-hint {
  font-size: 14px;
  color: var(--text-muted);
  margin-bottom: 16px;
}

.library-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: 400px;
  overflow-y: auto;
}

.library-character-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px;
  border-radius: 14px;
  background: var(--input-bg);
  border: 1px solid var(--input-border);
  cursor: pointer;
  transition: all 0.15s;
}

.library-character-card:hover {
  border-color: var(--text-muted);
  transform: translateY(-1px);
}

.library-char-avatar {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  object-fit: cover;
  flex-shrink: 0;
}

.library-char-avatar-placeholder {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background: var(--avatar-preview-bg);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  font-weight: 600;
  color: var(--text-muted);
  flex-shrink: 0;
}

.library-char-info {
  flex: 1;
  min-width: 0;
}

.library-char-info strong {
  display: block;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 2px;
}

.library-char-info span {
  display: block;
  font-size: 12px;
  color: var(--text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.library-empty {
  text-align: center;
  padding: 32px;
  color: var(--text-muted);
  font-size: 14px;
}

/*** Body ***/
.character-modal-body {
  flex: 1;
  overflow-y: auto;
  padding: 24px 32px 28px;
  background: #ffffff !important;
}

.character-form {
  display: flex;
  flex-direction: column;
  gap: 22px;
}

/*** Form Groups ***/
.form-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.form-label {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}

.form-label .required {
  color: var(--error-color);
  margin-left: 2px;
}

.form-hint {
  font-size: 12px;
  color: var(--text-muted);
}

.form-error {
  font-size: 13px;
  color: var(--error-color);
}

/*** Inputs & Textareas ***/
.form-input,
.form-textarea {
  width: 100%;
  border-radius: 14px;
  border: 1px solid rgba(203, 213, 225, 0.95) !important;
  background: #f8fafc !important;
  color: #0f172a !important;
  padding: 12px 14px;
  font-size: 14px;
  outline: none;
  transition: border-color 0.15s ease, box-shadow 0.15s ease, background 0.15s ease;
  backdrop-filter: none !important;
  -webkit-backdrop-filter: none !important;
}

.form-input::placeholder,
.form-textarea::placeholder {
  color: var(--text-muted);
}

.form-input:focus,
.form-textarea:focus {
  border-color: #0f172a !important;
  box-shadow: 0 0 0 3px rgba(15, 23, 42, 0.08) !important;
}

.form-textarea {
  min-height: 110px;
  resize: vertical;
  line-height: 1.6;
}

.prompt-textarea {
  min-height: 220px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 13px;
}

/*** Avatar Upload Row ***/
.avatar-upload-row {
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 16px;
  border: 1px dashed rgba(148, 163, 184, 0.9) !important;
  border-radius: 18px;
  background: #f8fafc !important;
  backdrop-filter: none !important;
  -webkit-backdrop-filter: none !important;
}

.avatar-preview-circle {
  width: 88px;
  height: 88px;
  border-radius: 50%;
  overflow: hidden;
  display: grid;
  place-items: center;
  background: #f1f5f9 !important;
  color: var(--avatar-preview-color);
  font-size: 12px;
  border: 2px solid rgba(226, 232, 240, 0.9) !important;
  flex-shrink: 0;
  cursor: pointer;
  transition: border-color 0.15s;
}

.avatar-preview-circle:hover {
  border-color: var(--text-muted);
}

.avatar-preview-circle img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.avatar-upload-info {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.avatar-upload-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}

.avatar-upload-desc {
  font-size: 12px;
  color: var(--text-muted);
}

/*** Buttons ***/
.secondary-button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 38px;
  padding: 0 14px;
  border-radius: 12px;
  border: 1px solid var(--btn-secondary-border);
  background: var(--btn-secondary-bg);
  color: var(--btn-secondary-text);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s;
}

.secondary-button:hover:not(:disabled) {
  border-color: var(--text-muted);
}

.secondary-button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.ai-generate-button {
  align-self: flex-start;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 38px;
  padding: 0 14px;
  border-radius: 12px;
  border: 1px solid var(--btn-ai-border);
  background: var(--btn-ai-bg);
  color: var(--btn-ai-text);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s;
}

.ai-generate-button:hover:not(:disabled) {
  transform: translateY(-1px);
  opacity: 0.9;
}

.ai-generate-button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.ai-generate-button svg {
  width: 16px;
  height: 16px;
}

.ai-generate-button.spinning svg {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/*** Footer ***/
.character-modal-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 32px;
  background: #ffffff !important;
  border-top: 1px solid rgba(226, 232, 240, 0.9) !important;
}

.footer-left {
  display: flex;
  align-items: center;
}

.footer-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.footer-cancel-btn {
  height: 42px;
  padding: 0 18px;
  border-radius: 14px;
  border: 1px solid var(--btn-secondary-border);
  background: var(--btn-secondary-bg);
  color: var(--btn-secondary-text);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s;
}

.footer-cancel-btn:hover {
  border-color: var(--text-muted);
  color: var(--text-primary);
}

.footer-submit-btn {
  height: 42px;
  padding: 0 20px;
  border-radius: 14px;
  border: none;
  background: var(--btn-primary-bg);
  color: var(--btn-primary-text);
  font-size: 14px;
  font-weight: 700;
  cursor: pointer;
  transition: all 0.15s;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.18);
}

.footer-submit-btn:hover:not(:disabled) {
  opacity: 0.92;
}

.footer-submit-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.delete-character-button {
  height: 42px;
  padding: 0 18px;
  border-radius: 14px;
  border: none;
  background: #000000;
  color: #ffffff;
  font-size: 14px;
  font-weight: 700;
  cursor: pointer;
  transition: transform 0.15s ease, box-shadow 0.15s ease, background 0.15s ease;
}

.delete-character-button:hover:not(:disabled) {
  background: #111827;
  transform: translateY(-1px);
  box-shadow: 0 10px 22px rgba(0, 0, 0, 0.18);
}

.delete-character-button:active:not(:disabled) {
  transform: translateY(0);
  box-shadow: none;
}

.delete-character-button:disabled {
  opacity: 0.55;
  cursor: not-allowed;
  transform: none;
  box-shadow: none;
}

/*** Transitions ***/
.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.3s ease;
}

.modal-enter-active .character-modal,
.modal-leave-active .character-modal {
  transition: transform 0.3s ease, opacity 0.3s ease;
}

.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}

.modal-enter-from .character-modal,
.modal-leave-to .character-modal {
  transform: scale(0.95) translateY(10px);
  opacity: 0;
}

/*** Responsive ***/
@media (max-width: 640px) {
  .character-modal-overlay {
    padding: 0;
    align-items: flex-end;
  }

  .character-modal {
    width: 100vw;
    max-height: 92vh;
    border-radius: 24px 24px 0 0;
  }

  .character-modal-header,
  .character-modal-body,
  .character-modal-footer {
    padding-left: 20px;
    padding-right: 20px;
  }
}

/*** Dark mode explicit overrides (force opaque card) ***/
.dark .character-modal-overlay {
  background: transparent !important;
  backdrop-filter: none !important;
  -webkit-backdrop-filter: none !important;
}

.dark .character-modal {
  background: #0f172a !important;
  color: #f8fafc !important;
  border-color: rgba(71, 85, 105, 0.85) !important;
  box-shadow: 0 28px 90px rgba(0, 0, 0, 0.55) !important;
  backdrop-filter: none !important;
  -webkit-backdrop-filter: none !important;
}

.dark .character-modal::before,
.dark .character-modal::after {
  display: none !important;
}

.dark .character-modal-header {
  background: #0f172a !important;
  border-bottom-color: rgba(71, 85, 105, 0.85) !important;
}

.dark .character-modal-body {
  background: #0f172a !important;
}

.dark .character-modal-footer {
  background: #0f172a !important;
  border-top-color: rgba(71, 85, 105, 0.85) !important;
}

.dark .form-input,
.dark .form-textarea {
  background: #1e293b !important;
  border-color: rgba(71, 85, 105, 0.95) !important;
  color: #f8fafc !important;
  backdrop-filter: none !important;
  -webkit-backdrop-filter: none !important;
}

.dark .form-input:focus,
.dark .form-textarea:focus {
  border-color: #94a3b8 !important;
  box-shadow: 0 0 0 3px rgba(148, 163, 184, 0.16) !important;
}

.dark .avatar-upload-row {
  background: #1e293b !important;
  border-color: rgba(100, 116, 139, 0.95) !important;
  backdrop-filter: none !important;
  -webkit-backdrop-filter: none !important;
}

.dark .avatar-preview-circle {
  background: #1e293b !important;
  border-color: rgba(100, 116, 139, 0.95) !important;
  color: #94a3b8 !important;
}

.dark .secondary-button {
  background: #1e293b !important;
  border-color: rgba(71, 85, 105, 0.95) !important;
  color: #f8fafc !important;
}

.dark .footer-submit-btn {
  background: #f8fafc !important;
  color: #0f172a !important;
}

.dark .delete-character-button {
  background: #000000 !important;
  color: #ffffff !important;
  border: 1px solid rgba(255, 255, 255, 0.12) !important;
}

.dark .delete-character-button:hover:not(:disabled) {
  background: #18181b !important;
}
</style>
