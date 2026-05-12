<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useCharacterStore } from '@/stores/character'
import { useAuthStore } from '@/stores/auth'
import { charactersApi } from '@/api/characters'

const router = useRouter()
const characterStore = useCharacterStore()
const authStore = useAuthStore()

const mounted = ref(false)
const loading = ref(false)
const generatingPrompt = ref(false)
const uploadingAvatar = ref(false)
const error = ref<string | null>(null)
const avatarPreview = ref<string | null>(null)
const fileInputRef = ref<HTMLInputElement | null>(null)

const form = ref({
  name: '',
  description: '',
  avatarUrl: '',
  prompt: ''
})

onMounted(() => {
  setTimeout(() => { mounted.value = true }, 50)
})

async function handleSubmit() {
  if (!form.value.name.trim()) {
    error.value = '请输入角色名称'
    return
  }

  if (!authStore.user) {
    error.value = '请先登录'
    return
  }

  // Check for duplicate name
  if (characterStore.hasDuplicateName(authStore.user.id, form.value.name.trim())) {
    error.value = '你已经创建过这个角色了'
    return
  }

  loading.value = true
  error.value = null

  try {
    const result = await characterStore.createCharacter({
      name: form.value.name.trim(),
      description: form.value.description.trim(),
      avatarUrl: form.value.avatarUrl,
      prompt: form.value.prompt.trim(),
      creatorUserId: authStore.user.id
    })

    if (result) {
      router.push('/characters')
    } else {
      error.value = characterStore.error || '创建角色失败'
    }
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

function handleCancel() {
  router.back()
}

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
  <div class="page-layout" :class="{ mounted }">
    <!-- Left Sidebar -->
    <aside class="sidebar">
      <!-- Logo -->
      <div class="sidebar-brand">
        <img src="/image.png" alt="logo" class="sidebar-brand-logo" />
        <span class="logo-text">Idea Party</span>
      </div>

      <!-- Create Button -->
      <button class="create-btn" disabled>
        <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
        </svg>
        <span>创建角色</span>
      </button>

      <!-- Navigation -->
      <nav class="nav-menu">
        <a
          href="#"
          class="nav-item"
          @click.prevent="router.push('/rooms')"
        >
          <span class="nav-emoji">🔍</span>
          <span class="nav-label">发现</span>
        </a>
        <a
          href="#"
          class="nav-item active"
        >
          <span class="nav-emoji">📚</span>
          <span class="nav-label">角色库</span>
        </a>
        <a
          href="#"
          class="nav-item"
          @click.prevent="router.push('/rooms?tab=trending')"
        >
          <span class="nav-emoji">🔥</span>
          <span class="nav-label">热门</span>
        </a>
      </nav>
    </aside>

    <!-- Main Content -->
    <main class="main-content">
      <div class="form-container">
        <header class="form-header">
          <button class="back-btn" @click="handleCancel">
            <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 19l-7-7 7-7" />
            </svg>
          </button>
          <h1 class="page-title">创建角色</h1>
        </header>

        <form @submit.prevent="handleSubmit" class="character-form">
          <!-- Avatar Upload -->
          <div class="form-section">
            <label class="form-label">角色头像</label>
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
                :class="{ 'has-image': avatarPreview }"
                @click="triggerAvatarUpload"
              >
                <img v-if="avatarPreview" :src="avatarPreview" alt="Avatar preview" />
                <svg v-else class="w-8 h-8" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z" />
                </svg>
              </div>
              <div class="avatar-actions">
                <button
                  type="button"
                  @click="triggerAvatarUpload"
                  :disabled="uploadingAvatar"
                  class="upload-btn"
                >
                  {{ uploadingAvatar ? '上传中...' : '上传头像' }}
                </button>
                <p class="upload-hint">支持 JPEG、PNG、GIF、WebP，不超过 5MB</p>
              </div>
            </div>
          </div>

          <!-- Name -->
          <div class="form-section">
            <label class="form-label">
              角色名称 <span class="required">*</span>
            </label>
            <input
              v-model="form.name"
              type="text"
              class="form-input"
              placeholder="请输入角色名称"
              maxlength="50"
            />
          </div>

          <!-- Description -->
          <div class="form-section">
            <label class="form-label">角色描述</label>
            <textarea
              v-model="form.description"
              class="form-textarea"
              placeholder="请输入角色描述"
              rows="3"
              maxlength="500"
            ></textarea>
          </div>

          <!-- Prompt -->
          <div class="form-section">
            <label class="form-label">角色设定 (Prompt)</label>
            <textarea
              v-model="form.prompt"
              class="form-textarea"
              placeholder="输入角色设定，用于定义 AI 角色的行为和风格"
              rows="5"
            ></textarea>
            <button
              type="button"
              @click="handleGeneratePrompt"
              :disabled="generatingPrompt"
              class="generate-btn"
            >
              <svg v-if="generatingPrompt" class="w-4 h-4 animate-spin" fill="none" viewBox="0 0 24 24">
                <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
                <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
              </svg>
              {{ generatingPrompt ? '生成中...' : 'AI 生成提示词' }}
            </button>
            <p class="input-hint">根据角色名称联网生成，或根据描述内容智能生成</p>
          </div>

          <!-- Error -->
          <p v-if="error" class="error-message">{{ error }}</p>

          <!-- Actions -->
          <div class="form-actions">
            <button type="button" class="cancel-btn" @click="handleCancel">
              取消
            </button>
            <button type="submit" class="submit-btn" :disabled="loading">
              {{ loading ? '创建中...' : '创建角色' }}
            </button>
          </div>
        </form>
      </div>
    </main>
  </div>
</template>

<style scoped>
.page-layout {
  display: grid;
  grid-template-columns: 260px 1fr;
  min-height: 100vh;
  background: var(--app-bg);
  opacity: 0;
  overflow: visible;
  transition: opacity 0.4s ease;
}

.page-layout.mounted {
  opacity: 1;
}

.sidebar {
  background: var(--sidebar-bg);
  border-right: 1px solid var(--border-color);
  display: flex;
  flex-direction: column;
  padding: 1rem;
  position: sticky;
  top: 0;
  height: 100vh;
  overflow-y: auto;
}

.sidebar-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0.25rem;
  margin-bottom: 0.875rem;
}

.sidebar-brand-logo {
  width: 32px;
  height: 32px;
  object-fit: contain;
  border-radius: 8px;
  flex-shrink: 0;
}

.logo-text {
  font-size: 22px;
  font-weight: 800;
  font-family: Inter, SF Pro Display, PingFang SC, sans-serif;
  line-height: 1;
  color: var(--text-primary);
  letter-spacing: -0.5px;
}

.create-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.4rem;
  width: 160px;
  height: 42px;
  background: var(--button-bg);
  border: none;
  border-radius: 14px;
  color: var(--button-text);
  font-size: 15px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s ease;
  margin-bottom: 1rem;
}

.create-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.nav-menu {
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  padding: 0.5rem 0.65rem;
  border-radius: 8px;
  color: var(--text-secondary);
  text-decoration: none;
  font-size: 0.875rem;
  font-weight: 400;
  transition: all 0.15s ease;
}

.nav-item:hover {
  background: var(--bg-primary);
  color: var(--text-primary);
}

.nav-item.active {
  background: var(--bg-primary);
  color: var(--text-primary);
  font-weight: 500;
}

.nav-emoji {
  font-size: 1rem;
  width: 20px;
  text-align: center;
}

.main-content {
  padding: 2rem;
  overflow-y: auto;
  display: flex;
  justify-content: center;
}

.form-container {
  width: 100%;
  max-width: 600px;
}

.form-header {
  display: flex;
  align-items: center;
  gap: 1rem;
  margin-bottom: 2rem;
}

.back-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  background: var(--card-bg);
  border: 1px solid var(--border-color);
  border-radius: 10px;
  color: var(--text-secondary);
  cursor: pointer;
  transition: all 0.15s ease;
}

.back-btn:hover {
  background: var(--bg-primary);
  color: var(--text-primary);
}

.page-title {
  font-size: 1.5rem;
  font-weight: 700;
  color: var(--text-primary);
}

.character-form {
  background: var(--card-bg);
  border: 1px solid var(--border-color);
  border-radius: 16px;
  padding: 2rem;
}

.form-section {
  margin-bottom: 1.5rem;
}

.form-label {
  display: block;
  font-size: 0.9rem;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 0.5rem;
}

.required {
  color: #EF4444;
}

.form-input,
.form-textarea {
  width: 100%;
  padding: 0.75rem 1rem;
  background: var(--input-bg);
  border: 1px solid var(--border-color);
  border-radius: 10px;
  font-size: 0.9rem;
  color: var(--text-primary);
  transition: all 0.2s ease;
  box-sizing: border-box;
}

.form-input:focus,
.form-textarea:focus {
  outline: none;
  border-color: #4F7DF3;
  box-shadow: 0 0 0 3px rgba(79, 125, 243, 0.1);
}

.form-input::placeholder,
.form-textarea::placeholder {
  color: var(--text-muted);
}

.form-textarea {
  resize: vertical;
  min-height: 80px;
}

.hidden {
  display: none;
}

.avatar-upload {
  display: flex;
  align-items: center;
  gap: 1rem;
}

.avatar-preview {
  width: 80px;
  height: 80px;
  border-radius: 12px;
  border: 2px dashed var(--border-color);
  background: var(--bg-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.2s ease;
  overflow: hidden;
}

.avatar-preview:hover {
  border-color: #4F7DF3;
}

.avatar-preview.has-image {
  border-style: solid;
  border-color: #4F7DF3;
}

.avatar-preview img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.avatar-preview svg {
  color: var(--text-muted);
}

.avatar-actions {
  flex: 1;
}

.upload-btn {
  padding: 0.5rem 1rem;
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  font-size: 0.85rem;
  font-weight: 500;
  color: var(--text-primary);
  cursor: pointer;
  transition: all 0.15s ease;
}

.upload-btn:hover:not(:disabled) {
  background: var(--border-color);
}

.upload-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.upload-hint {
  font-size: 0.75rem;
  color: var(--text-muted);
  margin-top: 0.5rem;
}

.generate-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  width: 100%;
  padding: 0.75rem;
  margin-top: 0.75rem;
  background: linear-gradient(135deg, #4F7DF3 0%, #6B7FFF 100%);
  border: none;
  border-radius: 10px;
  color: white;
  font-size: 0.9rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
}

.generate-btn:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(79, 125, 243, 0.3);
}

.generate-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.input-hint {
  font-size: 0.75rem;
  color: var(--text-muted);
  margin-top: 0.5rem;
}

.error-message {
  padding: 0.75rem 1rem;
  background: rgba(239, 68, 68, 0.1);
  border: 1px solid rgba(239, 68, 68, 0.3);
  border-radius: 10px;
  color: #EF4444;
  font-size: 0.875rem;
  margin-bottom: 1.5rem;
}

.form-actions {
  display: flex;
  gap: 1rem;
  justify-content: flex-end;
}

.cancel-btn {
  padding: 0.75rem 1.5rem;
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: 10px;
  color: var(--text-secondary);
  font-size: 0.9rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s ease;
}

.cancel-btn:hover {
  background: var(--border-color);
  color: var(--text-primary);
}

.submit-btn {
  padding: 0.75rem 1.5rem;
  background: linear-gradient(135deg, #4F7DF3 0%, #6B7FFF 100%);
  border: none;
  border-radius: 10px;
  color: white;
  font-size: 0.9rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
}

.submit-btn:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(79, 125, 243, 0.3);
}

.submit-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

@media (max-width: 768px) {
  .page-layout {
    grid-template-columns: 1fr;
  }

  .sidebar {
    display: none;
  }

  .main-content {
    padding: 1rem;
  }
}
</style>
