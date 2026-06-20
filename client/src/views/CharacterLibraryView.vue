<script setup lang="ts">
// CharacterLibraryView：路由 /characters
// 「我的角色库」管理页——展示当前用户创建的自定义角色，支持创建 / 编辑 / 一键开聊。
// 关键依赖：
//   - characterStore：角色 CRUD / 头像上传 / 同名校验
//   - authStore：当前用户标识，用于过滤"我创建的角色"
//   - roomStore：一键开聊时负责 createRoom + addCharacterToRoom
//   - CreateCharacterModal：复用弹窗组件，通过 mode='create' | 'edit' 区分行为
//   - AppSidebar / ALL_NAV_ITEMS：通用左侧导航（activeId='characters' 高亮当前页）
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useCharacterStore } from '@/stores/character'
import { useAuthStore } from '@/stores/auth'
import { useRoomStore } from '@/stores/room'
import type { Character } from '@/types'
import CreateCharacterModal from '@/components/character/CreateCharacterModal.vue'
import AppSidebar from '@/components/ui/AppSidebar.vue'
import { ALL_NAV_ITEMS } from '@/config/sidebar'

const router = useRouter()
const characterStore = useCharacterStore()
const authStore = useAuthStore()
const roomStore = useRoomStore()

// mounted 用作入场淡入动画的触发标志：onMounted 后延迟一帧再置 true，
// 让 <style> 里的 opacity 过渡能正常播放，避免首屏闪烁。
const mounted = ref(false)
const showCreateModal = ref(false)
const showEditModal = ref(false)
const editingCharacter = ref<Character | null>(null)

onMounted(() => {
  // 进入页面立即拉取一次角色列表，保证 Tab 切换回来时数据是最新的。
  characterStore.fetchCharacters()
  // 50ms 延迟是为了让 CSS transition 真正生效，而不是在元素挂载前就改了 class。
  setTimeout(() => { mounted.value = true }, 50)
})

// Get current user's characters
// 仅展示当前用户创建的自定义角色：排除 isPreset 的系统预置角色，
// 是因为业务上预置角色由系统统一提供，用户不应在"我的角色库"里看到/编辑它们。
const myCharacters = computed(() => {
  if (!authStore.user) return []
  const filtered = characterStore.characters.filter(
    c => c.ownerId === authStore.user!.id && !c.isPreset
  )
  console.log('[DEBUG] myCharacters过滤:', {
    totalCharacters: characterStore.characters.length,
    userId: authStore.user.id,
    filteredCount: filtered.length,
    filtered
  })
  return filtered
})

function openCreateModal() {
  showCreateModal.value = true
}

function closeCreateModal() {
  showCreateModal.value = false
}

async function handleCharacterCreated(character: any) {
  // 先乐观地把新角色 unshift 到 store 顶部，让 UI 立即可见；
  // 再异步 fetchCharacters 校正排序与字段，避免本地与服务端短暂不一致。
  if (character) {
    characterStore.characters.unshift(character)
  }
  await characterStore.fetchCharacters()
  closeCreateModal()
}

function openEditModal(character: Character) {
  editingCharacter.value = character
  showEditModal.value = true
}

function closeEditModal() {
  // 关闭编辑弹窗时清空 editingCharacter，防止下次打开旧实例复用导致脏数据。
  showEditModal.value = false
  editingCharacter.value = null
}

function handleCharacterUpdated(updatedCharacter: Character) {
  // Update the character in the list
  // 直接在 store 数组中原地替换，避免再次请求后端造成的闪烁；
  // 后端已是最新数据来源，UI 与 store 同步即可。
  const index = characterStore.characters.findIndex(c => c.id === updatedCharacter.id)
  if (index !== -1) {
    characterStore.characters[index] = updatedCharacter
  }
  closeEditModal()
}

async function startChat(character: Character) {
  // 一键开聊：以角色名作为房间名创建房间，再把该角色加入房间并跳转。
  // 创建和加入是两个独立调用，因为后端没有提供"建房间 + 绑定初始角色"的复合接口。
  try {
    const room = await roomStore.createRoom(character.name)
    await roomStore.addCharacterToRoom(room.id, character.id)
    router.push(`/chat/${room.id}`)
  } catch (e) {
    console.error('[DEBUG] Failed to start chat:', e)
    alert('创建对话失败，请重试')
  }
}

function formatDate(dateStr: string): string {
  const date = new Date(dateStr)
  const now = new Date()
  const diff = now.getTime() - date.getTime()
  const days = Math.floor(diff / (1000 * 60 * 60 * 24))

  if (days === 0) return '今天'
  if (days === 1) return '昨天'
  if (days < 7) return `${days}天前`
  if (days < 30) return `${Math.floor(days / 7)}周前`
  return date.toLocaleDateString('zh-CN')
}
</script>

<template>
  <div class="page-layout" :class="{ mounted }">
    <!-- Left Sidebar -->
    <AppSidebar :navItems="ALL_NAV_ITEMS" activeId="characters">
      <template #create>
        <button class="create-btn" @click="openCreateModal">
          <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
          </svg>
          <span>创建角色</span>
        </button>
      </template>
    </AppSidebar>

    <!-- Main Content -->
    <main class="main-content">
      <header class="content-header">
        <h1 class="page-title">我的角色库</h1>
        <button class="create-btn-large" @click="openCreateModal">
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
          </svg>
          创建角色
        </button>
      </header>

      <!-- Empty State -->
      <div v-if="myCharacters.length === 0" class="empty-state">
        <div class="empty-icon">📚</div>
        <h2 class="empty-title">还没有创建角色</h2>
        <p class="empty-desc">创建你的第一个 AI 角色，开始对话吧！</p>
        <button class="empty-btn" @click="openCreateModal">
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
          </svg>
          创建角色
        </button>
      </div>

      <!-- Character Grid -->
      <div v-else class="character-grid">
        <div
          v-for="character in myCharacters"
          :key="character.id"
          class="character-card"
        >
          <div class="card-header">
            <div class="character-avatar">
              <img
                v-if="character.avatarUrl"
                :src="character.avatarUrl"
                :alt="character.name"
              />
              <span v-else class="avatar-placeholder">{{ character.name.charAt(0) }}</span>
            </div>
            <div class="character-info">
              <h3 class="character-name">{{ character.name }}</h3>
              <p class="character-tagline">{{ character.description || '暂无描述' }}</p>
              <p class="character-date">创建于 {{ formatDate(character.createdAt) }}</p>
            </div>
          </div>
          <div class="card-footer">
            <button class="action-btn chat-btn" @click.stop="startChat(character)">
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
              </svg>
              对话
            </button>
            <button class="action-btn edit-btn" @click.stop="openEditModal(character)">
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" />
              </svg>
              编辑
            </button>
          </div>
        </div>
      </div>
    </main>

    <!-- Create Character Modal -->
    <CreateCharacterModal
      :show="showCreateModal"
      @close="closeCreateModal"
      @created="handleCharacterCreated"
    />

    <!-- Edit Character Modal -->
    <CreateCharacterModal
      v-if="showEditModal"
      :show="showEditModal"
      mode="edit"
      :character="editingCharacter"
      @close="closeEditModal"
      @updated="handleCharacterUpdated"
    />
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

.main-content {
  padding: 2rem;
  overflow-y: auto;
}

.content-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 2rem;
}

.page-title {
  font-size: 1.75rem;
  font-weight: 700;
  color: var(--text-primary);
}

.create-btn-large {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.75rem 1.25rem;
  background: linear-gradient(135deg, #18181b 0%, #3f3f46 100%);
  border: none;
  border-radius: 10px;
  color: white;
  font-size: 0.9rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
}

.create-btn-large:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(24, 24, 27, 0.3);
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 4rem 2rem;
  text-align: center;
}

.empty-icon {
  font-size: 4rem;
  margin-bottom: 1.5rem;
}

.empty-title {
  font-size: 1.5rem;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 0.5rem;
}

.empty-desc {
  font-size: 1rem;
  color: var(--text-muted);
  margin-bottom: 2rem;
}

.empty-btn {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.875rem 1.5rem;
  background: linear-gradient(135deg, #18181b 0%, #3f3f46 100%);
  border: none;
  border-radius: 12px;
  color: white;
  font-size: 1rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
}

.empty-btn:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 16px rgba(24, 24, 27, 0.35);
}

.character-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 1.25rem;
}

.character-card {
  display: flex;
  flex-direction: column;
  padding: 1.25rem;
  background: var(--card-bg);
  border: 1px solid var(--border-color);
  border-radius: 16px;
  transition: all 0.25s ease;
}

.character-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.08);
  border-color: #3f3f46;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 1rem;
}

.card-footer {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  margin-top: 1rem;
  padding-top: 0.75rem;
  border-top: 1px solid var(--border-color);
}

.character-avatar {
  width: 56px;
  height: 56px;
  border-radius: 12px;
  overflow: hidden;
  flex-shrink: 0;
  background: var(--bg-primary);
  display: flex;
  align-items: center;
  justify-content: center;
}

.character-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.avatar-placeholder {
  font-size: 1.5rem;
  font-weight: 700;
  color: var(--text-muted);
}

.character-info {
  flex: 1;
  min-width: 0;
}

.character-name {
  font-size: 1.1rem;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 0.25rem;
}

.character-tagline {
  font-size: 0.875rem;
  color: var(--text-secondary);
  margin-bottom: 0.5rem;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.character-date {
  font-size: 0.75rem;
  color: var(--text-muted);
}

.action-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.35rem;
  width: 100%;
  padding: 0.55rem 0.75rem;
  border-radius: 8px;
  font-size: 0.85rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s ease;
}

.edit-btn {
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  color: var(--text-secondary);
}

.edit-btn:hover {
  background: var(--border-color);
  color: var(--text-primary);
}

.chat-btn {
  background: var(--button-bg);
  border: 1px solid var(--border-color);
  color: var(--button-text);
}

.chat-btn:hover {
  opacity: 0.85;
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
