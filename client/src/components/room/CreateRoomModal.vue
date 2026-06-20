<script setup lang="ts">
// 创建聊天室的统一入口弹窗：
// 同时承载「单人对话」与「多人对话」两种模式，由父组件通过 v-model 风格控制 show。
// 由父组件（如 RoomListView）监听 created 事件获取新房间 id 并跳转，不在本组件内做路由跳转，
// 以便复用同一个弹窗组件、避免耦合具体路由路径。

import { ref, watch, computed } from 'vue'
import { useRoomStore } from '@/stores/room'
import { useCharacterStore } from '@/stores/character'
import { useAuthStore } from '@/stores/auth'
import { charactersApi } from '@/api/characters'
import type { Character } from '@/types'

// show：父组件 v-model 控制弹窗显隐
interface Props {
  show: boolean
}

// close：用户取消或主动关闭弹窗
// created：创建/复用房间成功后抛出房间 id；不直接 router.push 是为了把「创建」与「导航」解耦，
// 父组件可决定跳转到 /rooms/:id 还是更新 my-rooms 当前选中项等。
interface Emits {
  close: []
  created: [roomId: string]
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()

const roomStore = useRoomStore()
const characterStore = useCharacterStore()
const authStore = useAuthStore()

// Mode: 'single' (单人对话) or 'group' (多人对话)
const dialogMode = ref<'single' | 'group'>('single')

// Group mode form (多人对话)
const name = ref('')
const topic = ref('')
const selectedCharacterIds = ref<Set<string>>(new Set())

// Single mode: 'select' (选择角色) or 'create' (创建角色)
const singleTab = ref<'select' | 'create'>('select')

// Selected character for single mode
const selectedCharacter = ref<Character | null>(null)

// Duplicate-room confirmation dialog (replaces ugly browser confirm())
const dupDialog = ref<{
  characterName: string
  existingRoomId: string
} | null>(null)
const dupDialogLoading = ref(false)

// 当用户在 single 模式下选了一个角色，但已存在与之相关的 single 房间时，
// 不直接复用也不直接新建，而是弹此 dialog 让用户在「进入已有」/「仍要新建」之间选择，
// 避免静默跳转到旧房间造成困惑，也避免重复创建难以清理。

function openDupDialog(name: string, id: string) {
  dupDialog.value = { characterName: name, existingRoomId: id }
}
function closeDupDialog() {
  // 提交进行中禁止关闭：避免用户在异步创建中途关闭弹窗导致状态不一致
  if (dupDialogLoading.value) return
  dupDialog.value = null
}
function confirmGoExisting() {
  if (!dupDialog.value) return
  dupDialogLoading.value = true
  const id = dupDialog.value.existingRoomId
  dupDialog.value = null
  dupDialogLoading.value = false
  emit('created', id)
  emit('close')
}
function confirmCreateNew() {
  if (!dupDialog.value || !selectedCharacter.value) {
    dupDialog.value = null
    return
  }
  dupDialogLoading.value = true
  const char = selectedCharacter.value
  dupDialog.value = null
  // Fire-and-forget: actually create the new room
  roomStore
    .createRoom(char.name, undefined, [char.id], 'single')
    .then((room) => {
      emit('created', room.id)
      emit('close')
    })
    .catch((e) => {
      error.value = e instanceof Error ? e.message : '创建失败'
    })
    .finally(() => {
      dupDialogLoading.value = false
    })
}

// Create character form (单人对话模式下的创建角色表单)
const createForm = ref({
  name: '',
  description: '',
  avatarUrl: '',
  prompt: ''
})
const creatingCharacter = ref(false)
const generatingPrompt = ref(false)
const avatarPreview = ref<string | null>(null)
const uploadingAvatar = ref(false)
const fileInputRef = ref<HTMLInputElement | null>(null)

const loading = ref(false)
const error = ref<string | null>(null)

// 仅展示「当前用户自己创建 + 非预设」的角色：预设角色由系统统一管理，普通用户不应直接基于其建房间
const myCharacters = computed(() => {
  return characterStore.characters.filter(
    c => c.ownerId === authStore.user?.id && !c.isPreset
  )
})

// 每次 show 切换时同步状态：false 时重置所有表单字段，防止残留上次填写；true 时若角色未加载则拉取
watch(() => props.show, (newShow) => {
  if (!newShow) {
    // Reset to default state
    dialogMode.value = 'single'
    singleTab.value = 'select'
    name.value = ''
    topic.value = ''
    selectedCharacter.value = null
    selectedCharacterIds.value = new Set()
    createForm.value = { name: '', description: '', avatarUrl: '', prompt: '' }
    avatarPreview.value = null
    error.value = null
  } else {
    // Load characters
    if (characterStore.characters.length === 0) {
      characterStore.fetchCharacters()
    }
  }
})

function selectCharacter(character: Character) {
  selectedCharacter.value = character
  error.value = null
}

function toggleGroupCharacter(characterId: string) {
  const next = new Set(selectedCharacterIds.value)
  if (next.has(characterId)) {
    next.delete(characterId)
  } else {
    next.add(characterId)
  }
  selectedCharacterIds.value = next
  error.value = null
}

function switchToSelectTab() {
  singleTab.value = 'select'
  error.value = null
}

function switchToCreateTab() {
  singleTab.value = 'create'
  error.value = null
}

// Trigger avatar upload
function triggerAvatarUpload() {
  fileInputRef.value?.click()
}

// 头像上传：前端先做 MIME + 大小校验，避免无意义请求打到后端；5MB 限制与后端上传接口对齐
async function handleAvatarFileChange(event: Event) {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  if (!file) return

  const allowedTypes = ['image/jpeg', 'image/png', 'image/gif', 'image/webp']
  if (!allowedTypes.includes(file.type)) {
    error.value = '只支持 JPEG、PNG、GIF、WebP 格式的图片'
    return
  }

  if (file.size > 5 * 1024 * 1024) {
    error.value = '图片大小不能超过 5MB'
    return
  }

  uploadingAvatar.value = true
  error.value = null

  try {
    const url = await characterStore.uploadAvatar(file)
    if (url) {
      createForm.value.avatarUrl = url
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

// 调用后端 AI 接口生成角色 prompt：name 和 description 至少有一个非空才有意义，
// 因此两者都为空时直接拒绝请求，避免浪费 AI 调用配额。
async function handleGeneratePrompt() {
  if (!createForm.value.name.trim() && !createForm.value.description.trim()) {
    error.value = '请输入角色名称或描述'
    return
  }

  generatingPrompt.value = true
  error.value = null

  try {
    const response = await charactersApi.generatePrompt({
      name: createForm.value.name.trim() || undefined,
      description: createForm.value.description.trim() || undefined
    })
    createForm.value.prompt = response.data.prompt
  } catch (e: any) {
    error.value = e.response?.data?.message || '生成提示词失败'
  } finally {
    generatingPrompt.value = false
  }
}

// 创建完角色后自动选中并切回「选择角色」tab，让用户无需再点一次即可继续创建房间；
// 这是 UX 上的「一键到位」：创建 → 选中 → 进入下一步 保持最短路径。
async function handleCreateCharacter() {
  if (!createForm.value.name.trim()) {
    error.value = '请输入角色名称'
    return
  }

  creatingCharacter.value = true
  error.value = null

  try {
    const character = await characterStore.createCharacter({
      name: createForm.value.name.trim(),
      description: createForm.value.description.trim(),
      avatarUrl: createForm.value.avatarUrl,
      prompt: createForm.value.prompt,
      ownerId: authStore.user?.id
    })

    if (character) {
      // Select the newly created character
      selectedCharacter.value = character
      // Switch to select tab to show the selected character
      singleTab.value = 'select'
      // Reset create form
      createForm.value = { name: '', description: '', avatarUrl: '', prompt: '' }
      avatarPreview.value = null
    } else {
      error.value = characterStore.error || '创建角色失败'
    }
  } catch (e: any) {
    error.value = e.message || '创建角色失败'
  } finally {
    creatingCharacter.value = false
  }
}

// 总入口：根据 dialogMode 分派到 single / group 两条创建路径。
// single 模式特殊：在创建前会先查「是否已存在与该角色相关的 single 房间」，
// 若已存在则弹出 dupDialog 让用户主动选择复用或新建，避免静默跳转/重复创建。
async function handleSubmit() {
  if (dialogMode.value === 'single') {
    // 单人对话 validation
    if (!selectedCharacter.value) {
      error.value = '请选择一个角色'
      return
    }

    loading.value = true
    error.value = null

    try {
      const charId = selectedCharacter.value.id
      const charName = selectedCharacter.value.name

      // 检查该角色是否已有 single 模式的房间
      // 注意：只在"已经存在"时弹提示 + 复用，避免重复创建
      // 但不偷偷跳转 —— 用户确认后才进入
      await roomStore.fetchMyRooms()
      const existingRoom = roomStore.myRooms.find(room =>
        room.mode === 'single' &&
        room.characters?.some(c => c.id === charId)
      )

      if (existingRoom) {
        loading.value = false
        openDupDialog(charName, existingRoom.id)
        return
      }

      // 没有已有房间 → 创建新房间
      const room = await roomStore.createRoom(
        selectedCharacter.value.name,
        undefined,
        [selectedCharacter.value.id],
        'single'
      )
      emit('created', room.id)
      emit('close')
    } catch (e) {
      error.value = e instanceof Error ? e.message : '创建失败'
    } finally {
      loading.value = false
    }
  } else {
    // 多人对话 validation
    if (!name.value.trim()) {
      error.value = '请输入聊天室名称'
      return
    }
    if (selectedCharacterIds.value.size === 0) {
      error.value = '请至少选择一个角色'
      return
    }

    loading.value = true
    error.value = null

    try {
      const room = await roomStore.createRoom(
        name.value.trim(),
        topic.value.trim() || undefined,
        [...selectedCharacterIds.value],
        'group'
      )
      emit('created', room.id)
      emit('close')
    } catch (e) {
      error.value = e instanceof Error ? e.message : '创建失败'
    } finally {
      loading.value = false
    }
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
        class="room-modal-overlay"
        @click.self="handleClose"
      >
        <!-- Modal Container -->
        <div class="room-modal">
          <!-- Header -->
          <header class="room-modal-header">
            <div class="header-content">
              <h2 class="room-modal-title">创建对话</h2>
              <!-- Mode Tabs -->
              <div class="mode-tabs">
                <button
                  class="mode-tab"
                  :class="{ active: dialogMode === 'single' }"
                  @click="dialogMode = 'single'"
                >
                  单人对话
                </button>
                <button
                  class="mode-tab"
                  :class="{ active: dialogMode === 'group' }"
                  @click="dialogMode = 'group'"
                >
                  多人对话
                </button>
              </div>
            </div>
            <button class="modal-close" @click="handleClose">
              <svg width="20" height="20" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </header>

          <!-- Body -->
          <div class="room-modal-body">
            <!-- Single Mode Form (单人对话) -->
            <div v-if="dialogMode === 'single'" class="room-form">
              <p class="form-description">选择一个角色，发起一对一交流</p>

              <!-- Single Mode: Select/Create Tabs -->
              <div class="single-tabs">
                <button
                  class="single-tab"
                  :class="{ active: singleTab === 'select' }"
                  @click="switchToSelectTab"
                >
                  选择角色
                </button>
                <button
                  class="single-tab"
                  :class="{ active: singleTab === 'create' }"
                  @click="switchToCreateTab"
                >
                  创建角色
                </button>
              </div>

              <!-- Select Tab -->
              <div v-if="singleTab === 'select'" class="select-section">
                <!-- Character List -->
                <div class="character-list">
                  <div
                    v-for="character in myCharacters"
                    :key="character.id"
                    class="character-item"
                    :class="{ selected: selectedCharacter?.id === character.id }"
                    @click="selectCharacter(character)"
                  >
                    <div class="character-avatar">
                      <img v-if="character.avatarUrl" :src="character.avatarUrl" :alt="character.name" />
                      <span v-else>{{ character.name.charAt(0) }}</span>
                    </div>
                    <div class="character-info">
                      <span class="character-item-name">{{ character.name }}</span>
                      <span class="character-item-desc">{{ character.description || '暂无描述' }}</span>
                    </div>
                    <div v-if="selectedCharacter?.id === character.id" class="check-icon">
                      <svg width="18" height="18" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
                      </svg>
                    </div>
                  </div>
                  <div v-if="myCharacters.length === 0" class="character-empty">
                    暂无可用角色，请先创建角色
                  </div>
                </div>
              </div>

              <!-- Create Tab -->
              <div v-if="singleTab === 'create'" class="create-section">
                <!-- Avatar Upload -->
                <div class="form-group">
                  <label class="form-label">头像</label>
                  <input
                    ref="fileInputRef"
                    type="file"
                    accept="image/jpeg,image/png,image/gif,image/webp"
                    class="hidden"
                    @change="handleAvatarFileChange"
                  />
                  <div class="avatar-upload">
                    <div
                      class="avatar-preview"
                      :class="{ 'has-avatar': avatarPreview }"
                      @click="triggerAvatarUpload"
                    >
                      <img v-if="avatarPreview" :src="avatarPreview" alt="avatar" />
                      <svg v-else class="upload-icon" width="24" height="24" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
                      </svg>
                    </div>
                    <div class="avatar-actions">
                      <button
                        type="button"
                        class="upload-btn"
                        @click="triggerAvatarUpload"
                        :disabled="uploadingAvatar"
                      >
                        {{ uploadingAvatar ? '上传中...' : '上传头像' }}
                      </button>
                      <p class="avatar-hint">支持 JPEG、PNG、GIF、WebP，不超过 5MB</p>
                    </div>
                  </div>
                </div>

                <!-- Name -->
                <div class="form-group">
                  <label class="form-label">
                    角色名称 <span class="required">*</span>
                  </label>
                  <input
                    v-model="createForm.name"
                    type="text"
                    placeholder="请输入角色名称"
                    class="form-input"
                  />
                </div>

                <!-- Description -->
                <div class="form-group">
                  <label class="form-label">角色描述</label>
                  <textarea
                    v-model="createForm.description"
                    rows="2"
                    placeholder="请输入角色描述"
                    class="form-textarea"
                  ></textarea>
                </div>

                <!-- Prompt -->
                <div class="form-group">
                  <label class="form-label">角色设定 (Prompt)</label>
                  <textarea
                    v-model="createForm.prompt"
                    rows="3"
                    placeholder="输入角色设定，用于定义 AI 角色的行为和风格"
                    class="form-textarea"
                  ></textarea>
                  <button
                    type="button"
                    class="generate-btn"
                    @click="handleGeneratePrompt"
                    :disabled="generatingPrompt"
                  >
                    <svg v-if="generatingPrompt" class="spin-icon" width="16" height="16" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
                      <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
                    </svg>
                    <span>{{ generatingPrompt ? '生成中...' : 'AI 生成提示词' }}</span>
                  </button>
                </div>

                <!-- Create Character Button -->
                <button
                  type="button"
                  class="create-character-btn"
                  @click="handleCreateCharacter"
                  :disabled="creatingCharacter || !createForm.name.trim()"
                >
                  {{ creatingCharacter ? '创建中...' : '创建角色' }}
                </button>
              </div>

              <!-- Error -->
              <p v-if="error" class="form-error">{{ error }}</p>
            </div>

            <!-- Group Mode Form (多人对话) -->
            <div v-else class="room-form">
              <p class="form-description">设置聊天室名称和主题，选择多个角色发起讨论</p>

              <!-- Name -->
              <div class="form-group">
                <label class="form-label">
                  聊天室名称 <span class="required">*</span>
                </label>
                <input
                  v-model="name"
                  type="text"
                  placeholder="例如：哲学讨论群"
                  class="form-input"
                />
              </div>

              <!-- Topic -->
              <div class="form-group">
                <label class="form-label">主题（可选）</label>
                <textarea
                  v-model="topic"
                  rows="3"
                  placeholder="讨论什么话题？"
                  class="form-textarea"
                ></textarea>
              </div>

              <!-- Character multi-select -->
              <div class="form-group">
                <label class="form-label">
                  选择角色 <span class="required">*</span>
                </label>
                <div class="character-list">
                  <div
                    v-for="character in myCharacters"
                    :key="character.id"
                    class="character-item"
                    :class="{ selected: selectedCharacterIds.has(character.id) }"
                    @click="toggleGroupCharacter(character.id)"
                  >
                    <div class="character-avatar">
                      <img v-if="character.avatarUrl" :src="character.avatarUrl" :alt="character.name" />
                      <span v-else>{{ character.name.charAt(0) }}</span>
                    </div>
                    <div class="character-info">
                      <span class="character-item-name">{{ character.name }}</span>
                      <span class="character-item-desc">{{ character.description || '暂无描述' }}</span>
                    </div>
                    <div v-if="selectedCharacterIds.has(character.id)" class="check-icon">
                      <svg width="18" height="18" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
                      </svg>
                    </div>
                  </div>
                  <div v-if="myCharacters.length === 0" class="character-empty">
                    暂无可用角色，请先创建角色
                  </div>
                </div>
              </div>

              <!-- Error -->
              <p v-if="error" class="form-error">{{ error }}</p>
            </div>
          </div>

          <!-- Footer -->
          <footer class="room-modal-footer">
            <div class="footer-actions">
              <button
                type="button"
                class="footer-cancel-btn"
                @click="handleClose"
                :disabled="loading"
              >
                取消
              </button>
              <button
                type="button"
                class="footer-submit-btn"
                @click="handleSubmit"
                :disabled="loading || (dialogMode === 'single' ? !selectedCharacter : selectedCharacterIds.size === 0)"
              >
                {{ loading ? '创建中...' : (dialogMode === 'single' ? '开始对话' : '创建') }}
              </button>
            </div>
          </footer>
        </div>
      </div>
    </Transition>

    <!-- Duplicate-room confirmation dialog (project-styled, replaces native confirm) -->
    <Transition name="modal">
      <div v-if="dupDialog" class="modal-overlay" @click.self="closeDupDialog">
        <div class="modal-container" role="alertdialog" aria-modal="true" aria-labelledby="dup-title">
          <!-- Close Button -->
          <button class="close-btn" @click="closeDupDialog" :disabled="dupDialogLoading">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="18" y1="6" x2="6" y2="18"/>
              <line x1="6" y1="6" x2="18" y2="18"/>
            </svg>
          </button>

          <!-- Icon -->
          <div class="modal-icon">
            <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
            </svg>
          </div>

          <!-- Content -->
          <h2 id="dup-title" class="modal-title">已存在该对话</h2>
          <p class="modal-desc">
            你已经和「{{ dupDialog.characterName }}」有过对话。<br />
            要进入现有对话，还是发起一个新的？
          </p>

          <!-- Actions -->
          <div class="modal-actions">
            <button class="btn-cancel" @click="closeDupDialog" :disabled="dupDialogLoading">
              取消
            </button>
            <button class="btn-secondary" @click="confirmCreateNew" :disabled="dupDialogLoading">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <line x1="12" y1="5" x2="12" y2="19"/>
                <line x1="5" y1="12" x2="19" y2="12"/>
              </svg>
              创建新对话
            </button>
            <button class="btn-confirm" @click="confirmGoExisting" :disabled="dupDialogLoading">
              <span v-if="dupDialogLoading" class="loading-spinner"></span>
              <span v-else>进入现有对话</span>
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
/*** Light Mode Variables ***/
.room-modal-overlay {
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
  --btn-primary-bg: #0f172a;
  --btn-primary-text: #ffffff;
  --btn-secondary-bg: rgba(248, 250, 252, 0.72);
  --btn-secondary-border: rgba(203, 213, 225, 0.55);
  --btn-secondary-text: #334155;
  --error-color: #dc2626;
  --close-hover-bg: rgba(148, 163, 184, 0.18);
  --tab-active-bg: #0f172a;
  --tab-active-text: #ffffff;
  --tab-inactive-text: #64748b;
  --tab-inactive-bg: #f1f5f9;
  --selected-bg: #f0fdf4;
  --selected-border: #22c55e;
}

/*** Dark Mode Variables ***/
.dark .room-modal-overlay {
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
  --btn-primary-bg: #f8fafc;
  --btn-primary-text: #0f172a;
  --btn-secondary-bg: #1e293b;
  --btn-secondary-border: rgba(71, 85, 105, 0.95);
  --btn-secondary-text: #f8fafc;
  --error-color: #fca5a5;
  --close-hover-bg: rgba(255, 255, 255, 0.12);
  --tab-active-bg: #f8fafc;
  --tab-active-text: #0f172a;
  --tab-inactive-text: #94a3b8;
  --tab-inactive-bg: #1e293b;
  --selected-bg: #14532d;
  --selected-border: #22c55e;
}

/*** Overlay ***/
.room-modal-overlay {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px;
  background: rgba(0, 0, 0, 0.04) !important;
  backdrop-filter: none !important;
  -webkit-backdrop-filter: none !important;
}

/*** Modal Container ***/
.room-modal {
  position: relative;
  width: min(520px, calc(100vw - 48px));
  max-height: min(640px, calc(100vh - 64px));
  display: flex;
  flex-direction: column;
  background: var(--modal-bg) !important;
  color: var(--text-primary) !important;
  border: 1px solid var(--modal-border) !important;
  border-radius: 24px;
  box-shadow: var(--modal-shadow) !important;
  backdrop-filter: none !important;
  -webkit-backdrop-filter: none !important;
  overflow: hidden;
}

.room-modal::before,
.room-modal::after {
  display: none !important;
}

.room-modal-header,
.room-modal-body,
.room-modal-footer {
  position: relative;
  z-index: 1;
}

/*** Header ***/
.room-modal-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 28px 32px 20px;
  background: var(--header-bg) !important;
  border-bottom: 1px solid var(--header-border) !important;
}

.header-content {
  flex: 1;
}

.room-modal-title {
  font-size: 24px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0;
}

/*** Mode Tabs ***/
.mode-tabs {
  display: flex;
  gap: 8px;
  margin-top: 16px;
  background: var(--tab-inactive-bg);
  padding: 4px;
  border-radius: 12px;
  width: 100%;
  box-sizing: border-box;
}

.header-content {
  text-align: center;
}

.mode-tab {
  flex: 1;
  padding: 10px 16px;
  font-size: 14px;
  font-weight: 600;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s ease;
  background: transparent;
  color: var(--tab-inactive-text);
}

.mode-tab:hover:not(.active) {
  color: var(--text-primary);
}

.mode-tab.active {
  background: var(--tab-active-bg);
  color: var(--tab-active-text);
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

/*** Body ***/
.room-modal-body {
  flex: 1;
  overflow-y: auto;
  padding: 24px 32px 28px;
  background: var(--body-bg) !important;
}

.room-form {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.form-description {
  font-size: 14px;
  color: var(--text-muted);
  margin: 0;
}

/*** Single Mode Tabs ***/
.single-tabs {
  display: flex;
  gap: 4px;
  background: var(--tab-inactive-bg);
  padding: 3px;
  border-radius: 10px;
}

.single-tab {
  flex: 1;
  padding: 8px 12px;
  font-size: 13px;
  font-weight: 500;
  border: none;
  border-radius: 7px;
  cursor: pointer;
  transition: all 0.2s ease;
  background: transparent;
  color: var(--tab-inactive-text);
}

.single-tab:hover:not(.active) {
  color: var(--text-primary);
}

.single-tab.active {
  background: var(--tab-active-bg);
  color: var(--tab-active-text);
}

/*** Select Section ***/
.select-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.selected-character {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  background: var(--selected-bg);
  border: 1px solid var(--selected-border);
  border-radius: 14px;
}

.character-avatar-small {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  overflow: hidden;
  background: var(--tab-inactive-bg);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.character-avatar-small img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.character-avatar-small span {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-muted);
}

.selected-character .character-name {
  flex: 1;
  font-size: 14px;
  font-weight: 500;
  color: var(--text-primary);
}

.clear-btn {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  border: none;
  background: transparent;
  color: var(--text-muted);
  display: grid;
  place-items: center;
  cursor: pointer;
  transition: all 0.15s;
}

.clear-btn:hover {
  background: var(--close-hover-bg);
  color: var(--text-primary);
}

.character-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 200px;
  overflow-y: auto;
}

.character-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-radius: 12px;
  cursor: pointer;
  transition: all 0.15s ease;
  border: 1px solid transparent;
}

.character-item:hover {
  background: var(--tab-inactive-bg);
}

.character-item.selected {
  background: var(--selected-bg);
  border-color: var(--selected-border);
}

.character-avatar {
  width: 44px;
  height: 44px;
  border-radius: 10px;
  overflow: hidden;
  background: var(--tab-inactive-bg);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.character-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.character-avatar span {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-muted);
}

.character-info {
  flex: 1;
  min-width: 0;
}

.character-item-name {
  display: block;
  font-size: 14px;
  font-weight: 500;
  color: var(--text-primary);
}

.character-item-desc {
  display: block;
  font-size: 12px;
  color: var(--text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.check-icon {
  color: var(--selected-border);
}

.character-empty {
  padding: 24px;
  text-align: center;
  font-size: 14px;
  color: var(--text-muted);
}

/*** Create Section ***/
.create-section {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.avatar-upload {
  display: flex;
  align-items: center;
  gap: 16px;
}

.avatar-preview {
  width: 72px;
  height: 72px;
  border-radius: 16px;
  border: 2px dashed var(--input-border);
  background: var(--tab-inactive-bg);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.15s ease;
  overflow: hidden;
}

.avatar-preview:hover {
  border-color: var(--input-focus-border);
}

.avatar-preview.has-avatar {
  border-style: solid;
  border-color: var(--selected-border);
}

.avatar-preview img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.upload-icon {
  color: var(--text-muted);
}

.avatar-actions {
  flex: 1;
}

.upload-btn {
  padding: 8px 14px;
  font-size: 13px;
  font-weight: 500;
  border: 1px solid var(--input-border);
  border-radius: 10px;
  background: var(--input-bg);
  color: var(--text-primary);
  cursor: pointer;
  transition: all 0.15s ease;
}

.upload-btn:hover:not(:disabled) {
  border-color: var(--input-focus-border);
}

.upload-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.avatar-hint {
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-muted);
}

.generate-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  width: 100%;
  margin-top: 8px;
  padding: 10px;
  font-size: 13px;
  font-weight: 500;
  border: 1px solid var(--input-border);
  border-radius: 10px;
  background: var(--tab-inactive-bg);
  color: var(--text-secondary);
  cursor: pointer;
  transition: all 0.15s ease;
}

.generate-btn:hover:not(:disabled) {
  background: var(--input-bg);
  color: var(--text-primary);
}

.generate-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.spin-icon {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.create-character-btn {
  width: 100%;
  padding: 12px;
  font-size: 14px;
  font-weight: 600;
  border: none;
  border-radius: 12px;
  background: var(--btn-secondary-bg);
  color: var(--btn-secondary-text);
  cursor: pointer;
  transition: all 0.15s ease;
}

.create-character-btn:hover:not(:disabled) {
  background: var(--input-border);
}

.create-character-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
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

.form-error {
  font-size: 13px;
  color: var(--error-color);
}

/*** Inputs & Textareas ***/
.form-input,
.form-textarea {
  width: 100%;
  border-radius: 12px;
  border: 1px solid var(--input-border) !important;
  background: var(--input-bg) !important;
  color: var(--text-primary) !important;
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
  border-color: var(--input-focus-border) !important;
  box-shadow: var(--input-shadow) !important;
}

.form-textarea {
  min-height: 80px;
  resize: vertical;
  line-height: 1.6;
}

/*** Footer ***/
.room-modal-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 16px;
  padding: 18px 32px;
  background: var(--footer-bg) !important;
  border-top: 1px solid var(--footer-border) !important;
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

.footer-cancel-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
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

/*** Transitions ***/
.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.3s ease;
}

.modal-enter-active .room-modal,
.modal-leave-active .room-modal {
  transition: transform 0.3s ease, opacity 0.3s ease;
}

.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}

.modal-enter-from .room-modal,
.modal-leave-to .room-modal {
  transform: scale(0.95) translateY(10px);
  opacity: 0;
}

/*** Responsive ***/
@media (max-width: 640px) {
  .room-modal-overlay {
    padding: 0;
    align-items: flex-end;
  }

  .room-modal {
    width: 100vw;
    max-height: 92vh;
    border-radius: 24px 24px 0 0;
  }

  .room-modal-header,
  .room-modal-body,
  .room-modal-footer {
    padding-left: 20px;
    padding-right: 20px;
  }
}

/*** Dark mode explicit overrides ***/
.dark .room-modal-overlay {
  background: rgba(0, 0, 0, 0.08) !important;
}

.dark .room-modal {
  background: #0f172a !important;
  color: #f8fafc !important;
  border-color: rgba(71, 85, 105, 0.85) !important;
  box-shadow: 0 28px 90px rgba(0, 0, 0, 0.55) !important;
}

.dark .room-modal-header {
  background: #0f172a !important;
  border-bottom-color: rgba(71, 85, 105, 0.85) !important;
}

.dark .room-modal-body {
  background: #0f172a !important;
}

.dark .room-modal-footer {
  background: #0f172a !important;
  border-top-color: rgba(71, 85, 105, 0.85) !important;
}

.dark .form-input,
.dark .form-textarea {
  background: #1e293b !important;
  border-color: rgba(71, 85, 105, 0.95) !important;
  color: #f8fafc !important;
}

.dark .form-input:focus,
.dark .form-textarea:focus {
  border-color: #94a3b8 !important;
  box-shadow: 0 0 0 3px rgba(148, 163, 184, 0.16) !important;
}

.dark .footer-submit-btn {
  background: #f8fafc !important;
  color: #0f172a !important;
}

.dark .footer-cancel-btn {
  background: #1e293b !important;
  border-color: rgba(71, 85, 105, 0.95) !important;
  color: #f8fafc !important;
}

.dark .selected-character {
  background: #14532d !important;
  border-color: #22c55e !important;
}

.dark .character-item.selected {
  background: #14532d !important;
  border-color: #22c55e !important;
}

.dark .avatar-preview {
  background: #1e293b !important;
}

/*** Duplicate-room confirmation dialog ***/
/* Mirrors ConfirmLogoutModal.vue structure & style for visual consistency. */
.modal-overlay {
  background: rgba(0, 0, 0, 0.4) !important;
  backdrop-filter: blur(4px);
  -webkit-backdrop-filter: blur(4px);
  z-index: 1100; /* above the create-room modal */
  padding: 1rem;
}

.modal-container {
  position: relative;
  background: #FFFFFF !important;
  border-radius: 20px;
  padding: 2rem;
  width: 100%;
  max-width: 420px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.25);
  text-align: center;
}
.dark .modal-container {
  background: #0F172A !important;
  border: 1px solid rgba(71, 85, 105, 0.85);
}

.close-btn {
  position: absolute;
  top: 1rem;
  right: 1rem;
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: transparent;
  border: none;
  color: #94A3B8;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.15s ease;
}
.close-btn:hover:not(:disabled) {
  background: #F1F5F9;
  color: #1E293B;
}
.dark .close-btn:hover:not(:disabled) {
  background: #1E293B;
  color: #F1F5F9;
}
.close-btn:disabled { opacity: 0.5; cursor: not-allowed; }

.modal-icon {
  width: 64px;
  height: 64px;
  margin: 0 auto 1.25rem;
  border-radius: 50%;
  background: #FEF6E0; /* soft amber */
  display: flex;
  align-items: center;
  justify-content: center;
  color: #D6A84F;     /* project gold */
}
.dark .modal-icon {
  background: rgba(214, 168, 79, 0.18);
  color: #D6A84F;
}

.modal-title {
  font-size: 1.25rem;
  font-weight: 600;
  color: #1E293B;
  margin: 0 0 0.5rem;
}
.dark .modal-title {
  color: #F1F5F9;
}

.modal-desc {
  font-size: 0.9rem;
  color: #64748B;
  line-height: 1.5;
  margin: 0 0 1.5rem;
}
.dark .modal-desc {
  color: #94A3B8;
}

.modal-actions {
  display: flex;
  gap: 0.5rem;
}

.btn-cancel,
.btn-secondary,
.btn-confirm {
  flex: 1;
  padding: 0.85rem 1rem;
  border-radius: 12px;
  font-size: 0.9rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}

.btn-cancel {
  background: #F1F5F9;
  border: 1px solid #E2E8F0;
  color: #64748B;
}
.btn-cancel:hover:not(:disabled) {
  background: #E2E8F0;
  color: #1E293B;
}
.dark .btn-cancel {
  background: #1E293B;
  border-color: rgba(71, 85, 105, 0.85);
  color: #94A3B8;
}
.dark .btn-cancel:hover:not(:disabled) {
  background: #334155;
  color: #F1F5F9;
}

.btn-secondary {
  background: #FFFFFF;
  border: 1px solid #D6A84F;
  color: #D6A84F;
}
.btn-secondary:hover:not(:disabled) {
  background: rgba(214, 168, 79, 0.10);
  color: #B58F35;
  border-color: #B58F35;
}
.dark .btn-secondary {
  background: transparent;
  border-color: #D6A84F;
  color: #D6A84F;
}
.dark .btn-secondary:hover:not(:disabled) {
  background: rgba(214, 168, 79, 0.14);
}

.btn-confirm {
  background: #D6A84F; /* gold — primary action */
  border: none;
  color: #FFFFFF;
}
.btn-confirm:hover:not(:disabled) {
  background: #B58F35;
  transform: translateY(-1px);
}
.dark .btn-confirm {
  background: #D6A84F;
  color: #0F172A;
}
.dark .btn-confirm:hover:not(:disabled) {
  background: #E0B863;
}

.btn-cancel:disabled,
.btn-secondary:disabled,
.btn-confirm:disabled {
  opacity: 0.6;
  cursor: not-allowed;
  transform: none;
}

.loading-spinner {
  width: 16px;
  height: 16px;
  border: 2px solid rgba(255, 255, 255, 0.35);
  border-top-color: white;
  border-radius: 50%;
  animation: dup-spin 0.8s linear infinite;
}
.dark .loading-spinner {
  border-top-color: #0F172A;
}
@keyframes dup-spin {
  to { transform: rotate(360deg); }
}
</style>
