<script setup lang="ts">
// CharacterEditView：路由 /characters/edit/:id
// 「编辑角色」独立整页视图——角色库点击卡片跳转至此。
// 与早期嵌入在角色库里的弹窗式编辑相比，整页布局更适合显示长 prompt / 多字段，
// 也能避免弹窗在小屏上的滚动穿透问题。
// 关键依赖：
//   - characterStore：角色 CRUD + 头像上传
//   - authStore：登录用户（删除/重名校验兜底）
//   - roomStore：一键开聊时负责 createRoom + addCharacterToRoom
//   - ConfirmDialog：删除前的二次确认（受控模式，由父组件管理 show/loading）
//   - useToast：保存/删除/开聊结果反馈
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useCharacterStore } from '@/stores/character'
import { useAuthStore } from '@/stores/auth'
import { useRoomStore } from '@/stores/room'
import { charactersApi } from '@/api/characters'
import type { Character } from '@/types'
import AppSidebar from '@/components/ui/AppSidebar.vue'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'
import { ALL_NAV_ITEMS } from '@/config/sidebar'
import { useToast } from '@/composables/useToast'

const route = useRoute()
const router = useRouter()
const characterStore = useCharacterStore()
const authStore = useAuthStore()
const roomStore = useRoomStore()
const toast = useToast()

// mounted 用作入场淡入的触发标志；onMounted 后延迟一帧再置 true，让 CSS transition 正常播放，避免首屏闪烁。
const mounted = ref(false)
// saving/deleting/startingChat 分别跟踪不同按钮的繁忙态，避免互相阻塞（例如删除中时仍可点"返回"）
const saving = ref(false)
const deleting = ref(false)
const startingChat = ref(false)
const generatingPrompt = ref(false)
const uploadingAvatar = ref(false)
const avatarSearching = ref(false)
// error 同时承担表单级错误（顶部展示）与字段级校验提示（避免每字段再开一个 ref）
const error = ref<string | null>(null)

// 表单状态：与后端 Character 字段对齐的最小子集；avatarPreview 单独存一份避免后端相对路径被 <img> 直接渲染失败
interface CharacterForm {
  name: string
  description: string
  avatarUrl: string
  prompt: string
}
const form = ref<CharacterForm>({ name: '', description: '', avatarUrl: '', prompt: '' })
const avatarPreview = ref<string | null>(null)
// 路由 id 即要编辑的角色 id；computed 让模板与提交逻辑复用同一来源，避免拼写不一致
const editingCharacterId = computed(() => route.params.id as string | undefined)
// 当前角色从 store 中取出（列表页进入时 store 已加载）；找不到时为 null，UI 用 notFound 状态展示
const currentCharacter = computed<Character | null>(() => {
  if (!editingCharacterId.value) return null
  return characterStore.getCharacterById(editingCharacterId.value) ?? null
})

const showDeleteConfirm = ref(false)
// 自动获取头像的候选列表 + 错误信息；与 CreateCharacterModal 保持一致的本地 UI 状态
const avatarCandidates = ref<Array<{ thumbnailUrl: string; title: string; wikiUrl: string }>>([])
const avatarSearchError = ref<string | null>(null)
const showAvatarCandidates = ref(false)
const fileInputRef = ref<HTMLInputElement | null>(null)

// notFound：路由 id 解析成功但 store 中找不到对应角色（被并发删除、链接过期、跨账号访问等）。
// 单独标记而非直接跳走，便于用户看到上下文（"该角色已不存在"），并提供回到角色库的入口。
const notFound = computed(() => !!editingCharacterId.value && !currentCharacter.value)

onMounted(async () => {
  setTimeout(() => { mounted.value = true }, 50)
  // 编辑模式下 store 列表可能尚未加载（例如直接打开 URL 进入），主动 fetch 一次保证 currentCharacter 可解析
  if (characterStore.characters.length === 0) {
    await characterStore.fetchCharacters()
  }
  hydrateFromCharacter()
})

// 监听 id 变化：处理用户在同一视图内通过其他途径切换路由 id 的极端场景，
// 例如浏览器后退或代码触发的 router.replace；每次切换都重新水合表单，避免显示上一个角色的字段。
watch(editingCharacterId, () => {
  hydrateFromCharacter()
})

// hydrateFromCharacter：从 store 中读取当前路由对应的角色，填充表单与头像预览；
// 没找到时清空表单，由 notFound 状态控制空态文案。
function hydrateFromCharacter() {
  const c = currentCharacter.value
  if (c) {
    form.value = {
      name: c.name || '',
      description: c.description || '',
      avatarUrl: c.avatarUrl || '',
      prompt: c.prompt || ''
    }
    avatarPreview.value = c.avatarUrl || null
  } else {
    form.value = { name: '', description: '', avatarUrl: '', prompt: '' }
    avatarPreview.value = null
  }
  // 切换角色时清掉旧错误，避免上一次操作的残留提示干扰新表单
  error.value = null
  showAvatarCandidates.value = false
  avatarCandidates.value = []
  avatarSearchError.value = null
}

// handleSubmit：编辑模式提交入口。校验通过后调 store.updateCharacter，成功回跳角色库，失败读取 store.error 展示。
// 校验顺序：名称必填 → 登录态；编辑模式不做重名校验（业务允许改名为已存在名称）。
async function handleSubmit() {
  if (!form.value.name.trim()) {
    error.value = '请输入角色名称'
    return
  }
  if (!authStore.user) {
    error.value = '请先登录'
    return
  }
  if (!currentCharacter.value) {
    error.value = '角色不存在或已被删除'
    return
  }

  saving.value = true
  error.value = null
  try {
    const result = await characterStore.updateCharacter(currentCharacter.value.id, {
      name: form.value.name.trim(),
      description: form.value.description.trim(),
      avatarUrl: form.value.avatarUrl,
      prompt: form.value.prompt.trim()
    })
    if (result) {
      toast.success('已保存修改')
      router.push('/characters')
    } else {
      error.value = characterStore.error || '保存失败'
    }
  } catch (e: any) {
    console.error('[CharacterEditView] save failed:', e)
    error.value = e.response?.data?.message || e.message || '保存失败'
  } finally {
    saving.value = false
  }
}

// startChat：一键开聊入口。
// 不依赖表单已保存——使用 currentCharacter 的最新已落库数据创建房间并挂入角色，
// 避免用户必须先点保存再点开始对话的多余步骤；想保存可在编辑后另点「保存修改」。
// 如果表单里的 name 与落库的不同，提示用户先保存，避免房间名与服务端实际角色名脱钩。
async function startChat() {
  if (!currentCharacter.value || startingChat.value) return

  const trimmedName = form.value.name.trim()
  if (!trimmedName) {
    error.value = '请先填写角色名称再开始对话'
    return
  }
  if (trimmedName !== (currentCharacter.value.name || '')) {
    error.value = '角色名称已修改，请先点击「保存修改」再开始对话'
    return
  }

  startingChat.value = true
  error.value = null
  try {
    const room = await roomStore.createRoom(currentCharacter.value.name)
    await roomStore.addCharacterToRoom(room.id, currentCharacter.value.id)
    router.push(`/chat/${room.id}`)
  } catch (e: any) {
    console.error('[CharacterEditView] startChat failed:', e)
    error.value = e.response?.data?.message || e.message || '创建对话失败，请重试'
  } finally {
    startingChat.value = false
  }
}

// requestDelete：仅弹出二次确认；真正的删除在 confirmDelete 里执行。
// 拆成两步避免误触：受控 ConfirmDialog + danger 样式让"删除"成为需要刻意操作的按钮。
function requestDelete() {
  if (!currentCharacter.value) return
  showDeleteConfirm.value = true
}

async function confirmDelete() {
  if (!currentCharacter.value) return
  deleting.value = true
  error.value = null
  try {
    const success = await characterStore.deleteCharacter(currentCharacter.value.id)
    if (success) {
      toast.success(`已删除角色「${currentCharacter.value.name}」`)
      showDeleteConfirm.value = false
      router.replace('/characters')
    } else {
      const msg = characterStore.error || '删除失败'
      error.value = msg
      toast.error(msg)
      showDeleteConfirm.value = false
    }
  } catch (e: any) {
    console.error('[CharacterEditView] delete failed:', e)
    const msg = e.response?.data?.message || e.message || '删除角色失败'
    error.value = msg
    toast.error(msg)
  } finally {
    deleting.value = false
  }
}

function goBack() {
  router.push('/characters')
}

// handleGeneratePrompt：调用后端的 AI 联网检索 / 描述生成接口，覆盖当前 prompt 字段。
// 仅校验「名字或描述至少有一个」，避免两者都空时让 AI 端做无效推理。
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
    console.error('[CharacterEditView] generatePrompt failed:', e)
  } finally {
    generatingPrompt.value = false
  }
}

// triggerAvatarUpload / handleAvatarFileChange：客户端先做 MIME + 体积两道校验，失败不发请求；
// 上传成功把后端返回的 URL 同时回写到 form.avatarUrl 与 avatarPreview，保持两者一致避免视觉错位。
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
    if (fileInputRef.value) fileInputRef.value.value = ''
  }
}

// autoFetchAvatar：调后端 /characters/avatar-search 拿 2-3 个维基百科候选缩略图，用户点选后写入表单。
// 后端 downloadAvatarIfExternal 会在 create/update 时自动下载外链到本地，避免后续渲染每次打外网。
async function autoFetchAvatar() {
  const name = form.value.name?.trim()
  if (!name) {
    avatarSearchError.value = '请先输入角色名'
    return
  }
  avatarSearching.value = true
  avatarSearchError.value = null
  try {
    const resp = await charactersApi.searchAvatars(name)
    avatarCandidates.value = resp.data
    showAvatarCandidates.value = true
    if (resp.data.length === 0) {
      avatarSearchError.value = '维基百科未找到该角色，请手动上传或换个名字试试'
    }
  } catch (e: any) {
    console.error('[CharacterEditView] autoFetchAvatar failed:', e)
    avatarSearchError.value = e.response?.data?.message || e.message || '搜索失败，请稍后重试'
    avatarCandidates.value = []
  } finally {
    avatarSearching.value = false
  }
}

function pickAvatarCandidate(candidate: { thumbnailUrl: string; title: string; wikiUrl: string }) {
  form.value.avatarUrl = candidate.thumbnailUrl
  avatarPreview.value = candidate.thumbnailUrl
  showAvatarCandidates.value = false
}
</script>

<template>
  <div class="page-layout" :class="{ mounted }">
    <AppSidebar :navItems="ALL_NAV_ITEMS" activeId="characters" />

    <main class="main-content">
      <!-- 空态：找不到角色（已被删、链接过期等）。给一个返回按钮引导用户回到角色库。 -->
      <div v-if="notFound" class="not-found-state">
        <div class="not-found-icon">🔍</div>
        <h2 class="not-found-title">找不到该角色</h2>
        <p class="not-found-desc">该角色可能已被删除或链接已失效。</p>
        <button class="primary-btn" @click="goBack">返回角色库</button>
      </div>

      <template v-else>
        <!-- 顶部：返回 + 标题 + 操作区 -->
        <header class="content-header">
          <button class="back-btn" @click="goBack">
            <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 19l-7-7 7-7" />
            </svg>
            返回角色库
          </button>
          <h1 class="page-title">编辑角色</h1>
          <!-- 操作区：对话 / 保存修改 / 删除
               「对话」与「保存」的关系由 startChat() 内部按 name 是否变化判断，
               这里不重复做 disabled 控制避免冲突。 -->
          <div class="header-actions">
            <button
              type="button"
              class="chat-btn"
              :disabled="saving || deleting || startingChat"
              @click="startChat"
            >
              <svg v-if="startingChat" class="w-4 h-4 spinning" fill="none" viewBox="0 0 24 24">
                <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
                <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
              </svg>
              <svg v-else class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9 8s9 3.582 9 8z" />
              </svg>
              {{ startingChat ? '进入中...' : '对话' }}
            </button>
            <button
              type="button"
              class="save-btn"
              :disabled="saving || deleting || startingChat"
              @click="handleSubmit"
            >
              {{ saving ? '保存中...' : '保存修改' }}
            </button>
            <button
              type="button"
              class="delete-btn"
              :disabled="saving || deleting || startingChat"
              @click="requestDelete"
            >
              {{ deleting ? '删除中...' : '删除角色' }}
            </button>
          </div>
        </header>

        <!-- 表单卡片：与创建态保持一致的字段顺序，降低用户切换页面的认知成本 -->
        <section class="form-card">
          <input
            ref="fileInputRef"
            type="file"
            accept="image/jpeg,image/png,image/gif,image/webp"
            class="hidden"
            @change="handleAvatarFileChange"
          />

          <div class="form-group">
            <label class="form-label">角色名称 <span class="required">*</span></label>
            <input
              v-model="form.name"
              type="text"
              placeholder="请输入角色名称"
              class="form-input"
            />
          </div>

          <div class="form-group">
            <label class="form-label">角色描述</label>
            <textarea
              v-model="form.description"
              rows="3"
              placeholder="请输入角色描述"
              class="form-textarea"
            ></textarea>
          </div>

          <div class="form-group">
            <label class="form-label">角色头像</label>
            <div class="avatar-upload-row">
              <div class="avatar-preview-circle" @click="triggerAvatarUpload">
                <img v-if="avatarPreview" :src="avatarPreview" alt="Avatar preview" />
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
                    :disabled="uploadingAvatar || avatarSearching"
                  >
                    {{ uploadingAvatar ? '上传中...' : '上传头像' }}
                  </button>
                  <button
                    type="button"
                    class="secondary-button"
                    @click="autoFetchAvatar"
                    :disabled="avatarSearching || uploadingAvatar"
                  >
                    {{ avatarSearching ? '搜索中...' : '自动获取头像' }}
                  </button>
                </div>
                <p v-if="avatarSearchError" class="avatar-search-error">{{ avatarSearchError }}</p>
              </div>
            </div>

            <div v-if="showAvatarCandidates && avatarCandidates.length > 0" class="avatar-candidates">
              <p class="avatar-candidates-title">选择一张候选头像（点击即可）</p>
              <div class="avatar-candidates-grid">
                <button
                  v-for="(c, idx) in avatarCandidates"
                  :key="c.wikiUrl + idx"
                  type="button"
                  class="avatar-candidate"
                  :title="c.title"
                  @click="pickAvatarCandidate(c)"
                >
                  <img :src="c.thumbnailUrl" :alt="c.title" />
                </button>
              </div>
              <button
                type="button"
                class="secondary-button avatar-candidates-close"
                @click="showAvatarCandidates = false"
              >收起候选</button>
            </div>
          </div>

          <div class="form-group">
            <label class="form-label">角色设定 (Prompt)</label>
            <textarea
              v-model="form.prompt"
              rows="6"
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
              {{ generatingPrompt ? '生成中...' : 'AI 生成提示词' }}
            </button>
            <p class="form-hint">根据角色名称联网生成，或根据描述内容智能生成</p>
          </div>

          <p v-if="error" class="form-error">{{ error }}</p>
        </section>
      </template>
    </main>

    <ConfirmDialog
      :show="showDeleteConfirm"
      title="删除角色"
      :message="`确定要删除角色「${currentCharacter?.name || ''}」吗？此操作不可恢复。`"
      confirm-text="删除"
      cancel-text="取消"
      :danger="true"
      :loading="deleting"
      @confirm="confirmDelete"
      @cancel="showDeleteConfirm = false"
    />
  </div>
</template>

<style scoped>
.page-layout {
  display: grid;
  grid-template-columns: 260px 1fr;
  height: 100vh;
  background: var(--app-bg);
  opacity: 0;
  overflow: hidden;
  transition: opacity 0.4s ease;
}

.page-layout.mounted {
  opacity: 1;
}

.main-content {
  padding: 2rem 2.5rem;
  overflow-y: auto;
  min-height: 0;
}

.content-header {
  display: flex;
  align-items: center;
  gap: 1rem;
  margin-bottom: 2rem;
  flex-wrap: wrap;
}

.back-btn {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  padding: 0.5rem 0.85rem;
  background: transparent;
  border: 1px solid var(--border-color);
  border-radius: 10px;
  color: var(--text-secondary);
  font-size: 0.85rem;
  cursor: pointer;
  transition: all 0.15s ease;
}

.back-btn:hover {
  border-color: var(--text-muted);
  color: var(--text-primary);
}

.page-title {
  flex: 1;
  font-size: 1.5rem;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  flex-wrap: wrap;
}

.chat-btn,
.save-btn,
.delete-btn {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.55rem 1rem;
  border: none;
  border-radius: 10px;
  font-size: 0.875rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
}

.chat-btn {
  background: linear-gradient(135deg, #18181b 0%, #3f3f46 100%);
  color: #ffffff;
}

.chat-btn:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(24, 24, 27, 0.3);
}

.save-btn {
  background: var(--bg-primary);
  color: var(--text-primary);
  border: 1px solid var(--border-color);
}

.save-btn:hover:not(:disabled) {
  border-color: var(--text-muted);
}

.delete-btn {
  background: #000000;
  color: #ffffff;
}

.delete-btn:hover:not(:disabled) {
  background: #1f2937;
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.25);
}

.chat-btn:disabled,
.save-btn:disabled,
.delete-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
  transform: none;
  box-shadow: none;
}

.spinning svg {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/* 表单卡片：max-width 限制长 prompt 文本框的可读宽度 */
.form-card {
  max-width: 760px;
  background: var(--card-bg);
  border: 1px solid var(--border-color);
  border-radius: 16px;
  padding: 2rem;
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}

.form-group {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.form-label {
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--text-primary);
}

.form-label .required {
  color: #dc2626;
  margin-left: 2px;
}

.form-input,
.form-textarea {
  width: 100%;
  border: 1px solid var(--border-color);
  background: var(--bg-primary);
  color: var(--text-primary);
  padding: 0.65rem 0.85rem;
  border-radius: 10px;
  font-size: 0.9rem;
  outline: none;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}

.form-input:focus,
.form-textarea:focus {
  border-color: var(--text-primary);
  box-shadow: 0 0 0 3px rgba(24, 24, 27, 0.08);
}

.form-textarea {
  resize: vertical;
  line-height: 1.6;
}

.prompt-textarea {
  min-height: 180px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 0.85rem;
}

.form-hint {
  font-size: 0.75rem;
  color: var(--text-muted);
}

.form-error {
  font-size: 0.85rem;
  color: #dc2626;
}

.hidden {
  display: none;
}

/* 头像上传 */
.avatar-upload-row {
  display: flex;
  align-items: center;
  gap: 1rem;
  padding: 1rem;
  border: 1px dashed var(--border-color);
  border-radius: 14px;
  background: var(--bg-primary);
}

.avatar-preview-circle {
  width: 88px;
  height: 88px;
  border-radius: 50%;
  overflow: hidden;
  display: grid;
  place-items: center;
  background: var(--bg-secondary, #f1f5f9);
  color: var(--text-muted);
  border: 2px solid var(--border-color);
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
  gap: 0.35rem;
}

.avatar-upload-title {
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--text-primary);
}

.avatar-upload-desc {
  font-size: 0.75rem;
  color: var(--text-muted);
}

.avatar-upload-actions {
  display: flex;
  gap: 0.5rem;
  flex-wrap: wrap;
  margin-top: 0.25rem;
}

.avatar-upload-actions .secondary-button {
  flex: 1;
  min-width: 120px;
  justify-content: center;
}

.avatar-search-error {
  margin-top: 0.4rem;
  font-size: 0.75rem;
  color: #ef4444;
}

.avatar-candidates {
  margin-top: 0.85rem;
  padding: 0.85rem;
  border-radius: 12px;
  background: var(--bg-primary);
  border: 1px dashed var(--border-color);
}

.avatar-candidates-title {
  font-size: 0.75rem;
  font-weight: 500;
  color: var(--text-secondary);
  margin-bottom: 0.6rem;
}

.avatar-candidates-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(64px, 1fr));
  gap: 0.6rem;
  margin-bottom: 0.6rem;
}

.avatar-candidate {
  padding: 0;
  border: 2px solid transparent;
  border-radius: 10px;
  background: transparent;
  overflow: hidden;
  cursor: pointer;
  aspect-ratio: 1 / 1;
  transition: all 0.2s ease;
}

.avatar-candidate:hover {
  border-color: var(--text-primary);
  transform: translateY(-2px);
}

.avatar-candidate img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.avatar-candidates-close {
  font-size: 0.75rem;
  padding: 0.25rem 0.6rem;
  width: 100%;
  justify-content: center;
}

/* AI 生成按钮 */
.ai-generate-button {
  align-self: flex-start;
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.5rem 0.9rem;
  border: 1px solid rgba(202, 138, 4, 0.32);
  background: rgba(234, 179, 8, 0.14);
  color: #92400e;
  border-radius: 10px;
  font-size: 0.8rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s;
}

.ai-generate-button:hover:not(:disabled) {
  opacity: 0.9;
  transform: translateY(-1px);
}

.ai-generate-button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 次要按钮 */
.secondary-button {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  padding: 0.45rem 0.85rem;
  border-radius: 10px;
  border: 1px solid var(--border-color);
  background: var(--bg-primary);
  color: var(--text-primary);
  font-size: 0.8rem;
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

/* 空态 */
.not-found-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 5rem 2rem;
  text-align: center;
}

.not-found-icon {
  font-size: 3rem;
  margin-bottom: 1rem;
}

.not-found-title {
  font-size: 1.25rem;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 0.4rem;
}

.not-found-desc {
  font-size: 0.9rem;
  color: var(--text-muted);
  margin-bottom: 1.5rem;
}

.primary-btn {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.65rem 1.4rem;
  border: none;
  border-radius: 10px;
  background: linear-gradient(135deg, #18181b 0%, #3f3f46 100%);
  color: #ffffff;
  font-size: 0.9rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
}

.primary-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(24, 24, 27, 0.3);
}

/* 响应式：窄屏下隐藏侧栏，表单占满宽度 */
@media (max-width: 768px) {
  .page-layout {
    grid-template-columns: 1fr;
  }

  .main-content {
    padding: 1.25rem;
  }

  .content-header {
    flex-wrap: wrap;
  }

  .page-title {
    flex-basis: 100%;
    order: 2;
  }

  .header-actions {
    flex-basis: 100%;
    order: 3;
    justify-content: flex-end;
  }

  .form-card {
    padding: 1.25rem;
  }
}
</style>
