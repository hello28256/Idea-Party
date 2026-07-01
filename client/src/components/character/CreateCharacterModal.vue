<script setup lang="ts">
// CreateCharacterModal：统一处理角色创建、编辑、删除，以及在聊天室上下文下从角色库挑选已存在角色的弹窗。
// 同一份组件通过 mode + context 双维度切换行为，避免在多个入口处重复实现表单与上传逻辑。
import { ref, watch, computed } from 'vue'
import { useRouter } from 'vue-router'
import type { Character } from '@/types'
import { useCharacterStore } from '@/stores/character'
import { useAuthStore } from '@/stores/auth'
import { useSettingsStore } from '@/stores/settings'
import { useRoomStore } from '@/stores/room'
import { charactersApi } from '@/api/characters'
import type { CharacterReferences } from '@/api/characters'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'
import CascadeDeleteDialog from './CascadeDeleteDialog.vue'
import { useToast } from '@/composables/useToast'
import { resolveImageUrl } from '@/utils/avatarUrl'

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
const roomStore = useRoomStore()
const settingsStore = useSettingsStore()
const router = useRouter()
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
const startingChat = ref(false)
const error = ref<string | null>(null)
// 未配置 API Key 时弹窗提示用户，与 ChatRoomPanel 行为一致
const showApiKeyPrompt = ref(false)
const uploadingAvatar = ref(false)
const showDeleteConfirm = ref(false)
// 级联删除弹窗状态：先查 /references 拿到受影响房间，再决定走原 ConfirmDialog 还是新级联弹窗
const showCascadeDialog = ref(false)
const references = ref<CharacterReferences | null>(null)
const referencesLoading = ref(false)
const avatarPreview = ref<string | null>(null)
const fileInputRef = ref<HTMLInputElement | null>(null)

// 聊天室内上下文中的 tab 状态
const activeTab = ref<'create' | 'library'>('create')

// 弹窗每次重新显示时重置表单：避免上次编辑残留。
// 编辑模式下从 props.character 回填，新建模式下清空；avatarPreview 跟随 avatarUrl 同步，便于提交后立刻看到新头像。
watch(() => props.show, (newShow) => {
  if (newShow) {
    error.value = null
    showApiKeyPrompt.value = false
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
// 前置两步校验：1) 名字或描述至少有一个；2) 用户必须已配置 DeepSeek API Key（避免向后端发注定失败的请求）。
// 后端 catch 兜底保留：兜后端未重启 / 真 AI 失败等场景。
async function handleGeneratePrompt() {
  if (!form.value.name.trim() && !form.value.description.trim()) {
    error.value = '请输入角色名称或描述'
    return
  }

  // 前置校验：未配置 key 时直接弹提示模态框（与 ChatRoomPanel 的 MISSING_API_KEY 弹窗一致），
  // 用户点「去设置」才打开全局设置弹窗。不发注定失败的请求。
  if (!settingsStore.hasApiKey) {
    showApiKeyPrompt.value = true
    return
  }

  generatingPrompt.value = true
  error.value = null
  showApiKeyPrompt.value = false

  try {
    const response = await charactersApi.generatePrompt({
      name: form.value.name.trim() || undefined,
      description: form.value.description.trim() || undefined
    })
    form.value.prompt = response.data.prompt
  } catch (e: any) {
    const msg = e.response?.data?.message || '生成提示词失败'
    error.value = msg
    toast.error(msg)
    // 后端兜底：未重启场景下若仍拿到 fallback 假字符串无效，这里只透出真实错误。
    // 真实"未填 key"已被前端前置校验拦截，后端这条提示主要是兜 401/网络异常。
    if (msg.includes('API Key')) {
      showApiKeyPrompt.value = true
    }
    console.error('[DEBUG] handleGeneratePrompt failed:', e)
  } finally {
    generatingPrompt.value = false
  }
}

// 用户在 API Key 弹窗里点「去设置」：关掉当前弹窗，唤起全局设置弹窗到 api-key tab
function goToSettings() {
  showApiKeyPrompt.value = false
  settingsStore.openSettings('api-key')
}

function handleClose() {
  emit('close')
}

/**
 * 删除入口：先预查询角色被哪些聊天室引用，决定走原 ConfirmDialog 还是级联弹窗。
 *
 * <p>流程：
 * <ol>
 *   <li>referencesLoading 防重入</li>
 *   <li>调 store.fetchReferences(id)：成功拿到 {roomCount, rooms[]}</li>
 *   <li>若 roomCount > 0 → 弹级联弹窗（用户决定全删或全不删）</li>
 *   <li>否则（无引用或 fetch 失败）→ 回退到原 ConfirmDialog，保留旧 400 兜底</li>
 * </ol>
 *
 * <p>为什么 fetch 失败要回退到原 ConfirmDialog：fetch 失败不代表"无引用"，
 * 回退到原流程让用户至少能看到「被 N 个聊天室引用」的提示，不会静默删除。
 */
async function handleDeleteCharacter() {
  if (!isEditMode.value || !props.character?.id) return
  if (referencesLoading.value || deleting.value) return

  referencesLoading.value = true
  const refs = await characterStore.fetchReferences(props.character.id)
  referencesLoading.value = false

  if (refs && refs.roomCount > 0) {
    references.value = refs
    showCascadeDialog.value = true
  } else {
    // 无引用或 fetch 失败：回退原 ConfirmDialog
    showDeleteConfirm.value = true
  }
}

// 原 ConfirmDialog 确认回调（无引用场景）：保持原有行为，被引用则由后端 400 兜底。
async function confirmDelete() {
  if (!props.character?.id) return
  const name = props.character?.name || ''
  try {
    deleting.value = true
    error.value = null
    // 必须接 store 返回值：false 表示后端返回了错误（不 throw 但被 store 捕获）
    const success = await characterStore.deleteCharacter(props.character.id, false)
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
    console.error('[DEBUG] confirmDelete failed:', e)
    const msg = e.response?.data?.message || e.message || '删除角色失败'
    error.value = msg
    toast.error(msg)
  } finally {
    deleting.value = false
  }
}

// 级联弹窗确认回调：走 DELETE ?cascade=true，事务内一并删除引用房间 + 角色。
// 失败时弹窗保持打开（由 CascadeDeleteDialog 内部展示 error），用户可重试或取消。
async function confirmCascadeDelete() {
  if (!props.character?.id) return
  const name = props.character.name
  const count = references.value?.roomCount ?? 0
  try {
    deleting.value = true
    error.value = null
    const success = await characterStore.deleteCharacter(props.character.id, true)
    if (success) {
      showCascadeDialog.value = false
      toast.success(`已删除角色「${name}」及其引用的 ${count} 个聊天室`)
      emit('deleted', props.character.id)
      emit('close')
    } else {
      // 失败不关弹窗，让 CascadeDeleteDialog 在顶部红条展示错误，用户可重试
      const msg = characterStore.error || '删除失败'
      error.value = msg
      toast.error(msg)
    }
  } catch (e: any) {
    console.error('[DEBUG] confirmCascadeDelete failed:', e)
    const msg = e.response?.data?.message || e.message || '级联删除失败'
    error.value = msg
    toast.error(msg)
  } finally {
    deleting.value = false
  }
}

// startChatFromEdit：编辑模式下「开始对话」入口。
// 语义：跳到「我的聊天」tab 下名字等于当前角色的那个房间；找不到才创建新房间。
// 为什么跳 /rooms?tab=my-rooms&roomId=... 而不是独立 /chat/:roomId：
// 项目当前架构把聊天面板内嵌在「我的聊天」tab（RoomListView 的三栏布局），
// 独立 /chat 路由与侧栏上下文脱节，跳过去会让用户失去"我的聊天"导航上下文。
// 与 enterRoom(room.id) 在 RoomListView 里的目标完全一致。
// 如果表单里的 name 与落库的不同，提示用户先保存，避免房间名与服务端实际角色名脱钩。
async function startChatFromEdit() {
  if (!isEditMode.value || !props.character?.id) return
  if (startingChat.value) return

  const trimmedName = form.value.name.trim()
  if (!trimmedName) {
    error.value = '请先填写角色名称再开始对话'
    return
  }

  // 名称未保存提示：避免房间名与后端角色名脱钩
  if (trimmedName !== (props.character.name || '')) {
    error.value = '角色名称已修改，请先点击「保存修改」再开始对话'
    return
  }

  startingChat.value = true
  error.value = null
  try {
    // 优先复用：先确保我的聊天列表已加载，再按名字匹配最近一个同名房间。
    // fetchMyRooms 失败时静默降级：仍可走创建新房间的路径，避免点击完全无响应。
    if (roomStore.myRooms.length === 0) {
      try { await roomStore.fetchMyRooms() } catch { /* 静默降级 */ }
    }
    const existing = roomStore.sortedMyRooms.find(r => r.name === props.character.name)
    if (existing) {
      // 命中已有房间：跳到「我的聊天」tab 嵌入式聊天面板，保留侧栏上下文。
      emit('close')
      router.push(`/rooms?tab=my-rooms&roomId=${existing.id}`)
      return
    }

    // 未命中：创建新房间并把当前角色加入，跳到「我的聊天」tab 让用户立即看到。
    const room = await roomStore.createRoom(props.character.name)
    await roomStore.addCharacterToRoom(room.id, props.character.id)
    emit('close')
    router.push(`/rooms?tab=my-rooms&roomId=${room.id}`)
  } catch (e: any) {
    console.error('[CreateCharacterModal] startChat failed:', e)
    error.value = e.response?.data?.message || e.message || '创建对话失败，请重试'
  } finally {
    startingChat.value = false
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

  // 校验文件类型
  const allowedTypes = ['image/jpeg', 'image/png', 'image/gif', 'image/webp']
  if (!allowedTypes.includes(file.type)) {
    error.value = '只支持 JPEG、PNG、GIF、WebP 格式的图片'
    return
  }

  // 校验文件大小（5MB）
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
        v-show="show && !showApiKeyPrompt"
        class="character-modal-overlay"
        @click.self="handleClose"
      >
        <!-- 弹窗容器 -->
        <div class="character-modal">
          <!-- 头部 -->
          <header class="character-modal-header">
            <div>
              <h2 class="character-modal-title">
                {{ isEditMode ? '编辑角色' : (context === 'room' ? '添加角色' : '创建角色') }}
              </h2>
              <p class="character-modal-subtitle">
                {{ isEditMode ? '完善角色资料、头像和提示词设定' : (context === 'room' ? '创建新角色或从角色库选择' : '完善角色资料、头像和提示词设定') }}
              </p>
            </div>
            <div class="character-modal-header-actions">
              <button class="modal-close" @click="handleClose">
                <svg width="20" height="20" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
                </svg>
              </button>
            </div>
          </header>

          <!-- 切换标签（仅聊天室上下文） -->
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

          <!-- 主体 -->
          <div class="character-modal-body">
            <!-- 创建 tab / 编辑模式 -->
            <div v-if="activeTab === 'create' || isEditMode" class="character-form">
              <!-- 隐藏的文件输入 -->
              <input
                ref="fileInputRef"
                type="file"
                accept="image/jpeg,image/png,image/gif,image/webp"
                class="hidden"
                @change="handleAvatarFileChange"
              />

              <!-- 名称 -->
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

              <!-- 描述 -->
              <div class="form-group">
                <label class="form-label">角色描述</label>
                <textarea
                  v-model="form.description"
                  rows="3"
                  placeholder="请输入角色描述"
                  class="form-textarea"
                ></textarea>
              </div>

              <!-- 头像上传 -->
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
                    <div class="avatar-upload-actions">
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
              </div>

              <!-- 角色设定 Prompt -->
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

              <!-- 错误提示 -->
              <p v-if="error" class="form-error">{{ error }}</p>
            </div>

            <!-- 角色库 tab（用于聊天室上下文） -->
            <div v-if="context === 'room' && activeTab === 'library' && !isEditMode" class="character-library">
              <p class="library-hint">选择一个已有角色添加到聊天室：</p>
              <div class="library-list">
                <div
                  v-for="char in characterStore.characters.filter(c => c.ownerId === authStore.user?.id)"
                  :key="char.id"
                  class="library-character-card"
                  @click="handleSelectFromLibrary(char)"
                >
                  <img v-if="char.avatarUrl" :src="resolveImageUrl(char.avatarUrl)" :alt="char.name" class="library-char-avatar" />
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

          <!-- 底部 -->
          <footer v-if="activeTab === 'create' || isEditMode" class="character-modal-footer">
            <div class="footer-left">
              <button
                v-if="isEditMode"
                type="button"
                class="delete-character-button"
                @click="handleDeleteCharacter"
                :disabled="deleting || startingChat"
              >
                {{ deleting ? '删除中...' : '删除角色' }}
              </button>
            </div>

            <div class="footer-actions">
              <button
                v-if="isEditMode"
                type="button"
                class="footer-submit-btn footer-chat-btn"
                @click="startChatFromEdit"
                :disabled="loading || startingChat"
              >
                <svg v-if="startingChat" class="w-4 h-4 spinning" fill="none" viewBox="0 0 24 24">
                  <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
                  <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
                </svg>
                {{ startingChat ? '进入中...' : '开始对话' }}
              </button>
              <button
                type="button"
                class="footer-submit-btn"
                @click="handleSubmit"
                :disabled="loading || startingChat"
              >
                {{ loading ? '处理中...' : (isEditMode ? '保存修改' : '创建角色') }}
              </button>
            </div>
          </footer>
        </div>
      </div>
    </Transition>
  </Teleport>

  <!-- API Key 缺失提示弹窗：与 ChatRoomPanel 的 MISSING_API_KEY 处理一致 -->
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="showApiKeyPrompt" class="api-key-modal-overlay" @click.self="showApiKeyPrompt = false">
        <div class="api-key-modal">
          <div class="api-key-modal-icon">
            <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <circle cx="12" cy="12" r="10"/>
              <line x1="12" y1="8" x2="12" y2="12"/>
              <line x1="12" y1="16" x2="12.01" y2="16"/>
            </svg>
          </div>
          <h3 class="api-key-modal-title">需要配置 API Key</h3>
          <p class="api-key-modal-desc">AI 生成提示词需要 DeepSeek API Key，请先在设置中配置。</p>
          <div class="api-key-modal-actions">
            <button class="api-key-btn-cancel" @click="showApiKeyPrompt = false">取消</button>
            <button class="api-key-btn-confirm" @click="goToSettings">去设置</button>
          </div>
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

  <CascadeDeleteDialog
    :show="showCascadeDialog"
    :character-name="props.character?.name || ''"
    :rooms="references?.rooms ?? []"
    :loading="deleting"
    :error="error"
    @confirm="confirmCascadeDelete"
    @cancel="showCascadeDialog = false"
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

/* header 右侧操作区：仅放关闭按钮，编辑入口统一收到底部 footer */
.character-modal-header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

/* header 已不放置「对话」按钮：原本的主色 CTA 风格迁到底部 footer 的「开始对话」按钮 */

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

/* API Key 缺失提示弹窗（与 ChatRoomPanel 的 MISSING_API_KEY 弹窗保持一致的视觉语言） */
.api-key-modal-overlay {
  position: fixed;
  inset: 0;
  background: transparent;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 11000;
}

.api-key-modal {
  width: min(420px, calc(100vw - 32px));
  background: var(--card-bg, #ffffff);
  border-radius: 16px;
  padding: 28px 24px 20px;
  box-shadow: 0 24px 60px rgba(15, 23, 42, 0.25);
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
}

.api-key-modal-icon {
  width: 52px;
  height: 52px;
  border-radius: 50%;
  background: rgba(245, 158, 11, 0.15);
  color: #d97706;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 12px;
}

.api-key-modal-title {
  font-size: 18px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0 0 8px;
}

.api-key-modal-desc {
  font-size: 14px;
  color: var(--text-secondary);
  margin: 0 0 20px;
  line-height: 1.5;
}

.api-key-modal-actions {
  display: flex;
  gap: 10px;
  width: 100%;
}

.api-key-btn-cancel,
.api-key-btn-confirm {
  flex: 1;
  padding: 10px 16px;
  border-radius: 10px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  border: none;
  transition: opacity 0.15s ease;
}
.api-key-btn-cancel:hover,
.api-key-btn-confirm:hover {
  opacity: 0.85;
}

.api-key-btn-cancel {
  background: var(--bg-secondary, #f4f4f5);
  color: var(--text-primary);
}

.api-key-btn-confirm {
  background: linear-gradient(135deg, #18181b 0%, #3f3f46 100%);
  color: #ffffff;
}

.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.2s ease;
}
.modal-enter-from,
.modal-leave-to {
  opacity: 0;
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

.footer-chat-btn {
  background: transparent;
  color: var(--btn-primary-bg);
  border: 1px solid var(--btn-primary-bg);
  box-shadow: none;
}

.footer-chat-btn:hover:not(:disabled) {
  background: var(--btn-primary-bg);
  color: var(--btn-primary-text);
  opacity: 1;
}

.footer-chat-btn svg {
  width: 16px;
  height: 16px;
}

.footer-chat-btn .spinning {
  animation: spin 1s linear infinite;
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

/* === 上传头像:单按钮 === */
.avatar-upload-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.avatar-upload-actions .secondary-button {
  justify-content: center;
}
</style>
