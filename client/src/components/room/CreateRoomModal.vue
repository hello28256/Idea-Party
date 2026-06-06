<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { useRoomStore } from '@/stores/room'
import { useCharacterStore } from '@/stores/character'
import { useAuthStore } from '@/stores/auth'
import { charactersApi } from '@/api/characters'
import type { Character } from '@/types'

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

// Get user's own characters for selection
const myCharacters = computed(() => {
  return characterStore.characters.filter(
    c => c.ownerId === authStore.user?.id && !c.isPreset
  )
})

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
      // Check if this character already has a chat room
      await roomStore.fetchMyRooms()
      const existingRoom = roomStore.myRooms.find(room =>
        room.characters?.some(c => c.id === selectedCharacter.value!.id)
      )

      if (existingRoom) {
        // Navigate to existing room
        emit('created', existingRoom.id)
      } else {
        // Create new room for this character
        const room = await roomStore.createRoom(selectedCharacter.value.name)
        await roomStore.addCharacterToRoom(room.id, selectedCharacter.value.id)
        emit('created', room.id)
      }
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
        [...selectedCharacterIds.value]
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
</style>
