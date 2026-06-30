<script setup lang="ts">
// CharacterLibraryView：路由 /characters
// 「我的角色库」管理页——展示当前用户创建的自定义角色，支持创建 / 编辑。
// 卡片点击 → 弹 CreateCharacterModal mode='edit'（该弹窗已自带「对话」按钮，无需在卡片上重复）。
// 关键依赖：
//   - characterStore：角色 CRUD / 头像上传 / 同名校验
//   - authStore：当前用户标识，用于过滤"我创建的角色"
//   - CreateCharacterModal：复用弹窗组件，通过 mode='create' | 'edit' 区分行为（编辑模式已带「对话」按钮）
//   - AppSidebar / ALL_NAV_ITEMS：通用左侧导航（activeId='characters' 高亮当前页）
import { ref, computed, onMounted } from 'vue'
import { charactersApi } from '@/api/characters'
import { useCharacterStore } from '@/stores/character'
import { useAuthStore } from '@/stores/auth'
import type { Character } from '@/types'
import CreateCharacterModal from '@/components/character/CreateCharacterModal.vue'
import AppSidebar from '@/components/ui/AppSidebar.vue'
import { ALL_NAV_ITEMS } from '@/config/sidebar'

const characterStore = useCharacterStore()
const authStore = useAuthStore()

// mounted 用作入场淡入动画的触发标志：onMounted 后延迟一帧再置 true，
// 让 <style> 里的 opacity 过渡能正常播放，避免首屏闪烁。
const mounted = ref(false)
const showCreateModal = ref(false)
const showEditModal = ref(false)
const editingCharacter = ref<Character | null>(null)

onMounted(async () => {
  // 进入页面立即拉取一次角色列表，保证 Tab 切换回来时数据是最新的。
  await characterStore.fetchCharacters()
  // 50ms 延迟是为了让 CSS transition 真正生效，而不是在元素挂载前就改了 class。
  setTimeout(() => { mounted.value = true }, 50)

  // 老库补全：用户历史上 clone 推荐角色时（FeaturedCharacters map 漏保留 avatarUrl 字段的旧版本），
  // 创建出来的私有副本 avatarUrl 为空。一次性从 /api/characters/recommended 拉预设的"正确头像 URL"
  // 表，按名字匹配把缺失头像的就地补上（PUT /api/characters/{id}）。幂等：已有 avatarUrl 的不动。
  // 为什么在页面挂载时跑而不是在卡片点击时：用户已经不再点击老角色的话，
  // 根本走不到点击路径上的修复分支；放在挂载阶段能保证只要用户进入页面就能就地补齐头像。
  await patchMissingAvatarUrls()
})

/**
 * 检测当前用户角色库里 avatarUrl 为空或仍是 DiceBear 风格的私有副本，
 * 从 recommended API 找到同名 preset 的本地头像路径，调 updateCharacter 写回。
 * 仅补当前用户私有角色（isPreset=false），避免误改系统预设。
 */
async function patchMissingAvatarUrls() {
  try {
    const presets = await charactersApi.getRecommended()
    // 用 map<name, avatarUrl> 做 O(1) 查表，只保留非空且是本地路径的预设头像
    const presetAvatars = new Map<string, string>()
    for (const p of presets.data) {
      if (p.avatarUrl && p.avatarUrl.startsWith('/api/')) {
        presetAvatars.set(p.name, p.avatarUrl)
      }
    }
    const needPatch = characterStore.characters.filter(c =>
      !c.isPreset &&
      c.ownerId === authStore.user?.id &&
      (!c.avatarUrl || c.avatarUrl.startsWith('http'))
    )
    if (needPatch.length === 0) return
    console.log(`[AvatarPatch] patching ${needPatch.length} characters with missing avatars`)
    await Promise.all(needPatch.map(async (c) => {
      const newUrl = presetAvatars.get(c.name) || `/api/upload/avatars/presets/${encodeURIComponent(c.name)}.jpg`
      console.log(`[AvatarPatch] '${c.name}': '${c.avatarUrl || ''}' -> '${newUrl}'`)
      await characterStore.updateCharacter(c.id, {
        name: c.name,
        description: c.description || '',
        avatarUrl: newUrl,
        prompt: c.prompt || ''
      })
    }))
    // patch 完成后重新拉一次 store，让前端 UI 立即看到新头像（避免用户手动刷新页面）
    await characterStore.fetchCharacters()
    console.log('[AvatarPatch] done, store refreshed')
  } catch (e) {
    console.warn('[AvatarPatch] failed:', e)
  }
}

// 获取当前用户的角色
// 仅展示当前用户创建的自定义角色：排除 isPreset 的系统预置角色，
// 是因为业务上预置角色由系统统一提供，用户不应在"我的角色库"里看到/编辑它们。
// 按 createdAt 降序：新创建的角色排在前面（用户创建完想立刻找到/编辑时不必滚到底）。
// 缺失 createdAt 的旧数据用空串兜底排序到末尾，不抛错。
const myCharacters = computed(() => {
  if (!authStore.user) return []
  const filtered = characterStore.characters.filter(
    c => c.ownerId === authStore.user!.id && !c.isPreset
  )
  return filtered
    .slice()
    .sort((a, b) => (b.createdAt || '').localeCompare(a.createdAt || ''))
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

// openEditModal：点击卡片后弹出编辑模态框（mode='edit'，弹窗自身带「对话」按钮）。
// 为什么不放在卡片底部做两个按钮：弹窗有更大空间容纳长 prompt，且「对话」入口与角色信息在视觉上分离，
// 避免用户把"管理资源"与"立即消费"两种意图混在一起。
function openEditModal(character: Character) {
  editingCharacter.value = character
  showEditModal.value = true
}

// closeEditModal：关闭编辑弹窗时清空 editingCharacter，防止下次打开旧实例复用导致脏数据。
function closeEditModal() {
  showEditModal.value = false
  editingCharacter.value = null
}

// handleCharacterUpdated：编辑保存成功后原地替换 store 里的角色，避免再次 fetch 引起的列表闪烁。
function handleCharacterUpdated(updatedCharacter: Character) {
  const index = characterStore.characters.findIndex(c => c.id === updatedCharacter.id)
  if (index !== -1) {
    characterStore.characters[index] = updatedCharacter
  }
  closeEditModal()
}

function formatDate(dateStr: string): string {
  const date = new Date(dateStr)
  const now = new Date()
  const diffMs = now.getTime() - date.getTime()
  const minutes = Math.floor(diffMs / (1000 * 60))
  const hours = Math.floor(diffMs / (1000 * 60 * 60))
  const days = Math.floor(diffMs / (1000 * 60 * 60 * 24))

  // padStart: 时分十位补 0，统一 08:05 这种格式，避免出现 8:5
  const hh = String(date.getHours()).padStart(2, '0')
  const mm = String(date.getMinutes()).padStart(2, '0')

  if (minutes < 1) return '刚刚'
  if (hours < 1) return `${minutes}分钟前`
  if (days === 0) return `今天 ${hh}:${mm}`
  if (days === 1) return `昨天 ${hh}:${mm}`
  if (days < 7) return `${days}天前`
  if (days < 30) return `${Math.floor(days / 7)}周前`
  return date.toLocaleDateString('zh-CN')
}
</script>

<template>
  <div class="page-layout" :class="{ mounted }">
    <!-- 左侧边栏 -->
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

    <!-- 主内容 -->
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

      <!-- 空状态 -->
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

      <!-- 角色卡片网格 -->
      <div v-else class="character-grid">
        <div
          v-for="character in myCharacters"
          :key="character.id"
          class="character-card"
          role="button"
          tabindex="0"
          @click="openEditModal(character)"
          @keydown.enter="openEditModal(character)"
          @keydown.space.prevent="openEditModal(character)"
        >
          <div class="card-header">
            <!-- 左侧固定列：头像 + 创建时间，不随右侧内容上下移动 -->
            <div class="character-meta">
              <div class="character-avatar">
                <img
                  v-if="character.avatarUrl"
                  :src="character.avatarUrl"
                  :alt="character.name"
                />
                <span v-else class="avatar-placeholder">{{ character.name.charAt(0) }}</span>
              </div>
              <p class="character-date">创建于 {{ formatDate(character.createdAt) }}</p>
            </div>
            <div class="character-info">
              <h3 class="character-name">{{ character.name }}</h3>
              <p class="character-tagline">{{ character.description || '暂无描述' }}</p>
            </div>
          </div>
        </div>
      </div>
    </main>

    <!-- 创建角色弹窗 -->
    <CreateCharacterModal
      :show="showCreateModal"
      @close="closeCreateModal"
      @created="handleCharacterCreated"
    />

    <!-- 编辑角色弹窗：编辑模式自带「对话」按钮，无需在卡片底部重复 -->
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
  /* 固定高度让 main-content 内部能滚，否则 grid 子项会被内容撑高，
     min-height: 100vh 时整个 layout 跟着内容长，body 出现滚动条（很窄 6px 易被忽略） */
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
  padding: 2rem;
  overflow-y: auto;
  /* grid 子项默认 min-height: auto，会按内容撑高无法触发 overflow-y:auto */
  min-height: 0;
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
  cursor: pointer;
  user-select: none;
  transition: transform 0.2s ease, box-shadow 0.2s ease, border-color 0.2s ease;
}

.character-card:hover {
  transform: translateY(-3px);
  box-shadow: 0 10px 28px rgba(0, 0, 0, 0.12);
  border-color: #3f3f46;
}

.character-card:focus-visible {
  outline: none;
  border-color: #18181b;
  box-shadow: 0 0 0 3px rgba(24, 24, 27, 0.18);
}

.card-header {
  display: flex;
  flex-direction: row;
  align-items: flex-start;
  gap: 0.85rem;
}

/* 左侧固定列：纵向排列头像 + 创建时间。
   用 gap 而不是 margin-top 来表达"头像和日期之间的间距"——
   gap 是 Flex/Grid 原生的布局语义，描述"子项之间的距离"；
   而 margin 是在子项自身上加"距上一个兄弟多少像素"的位置偏移。
   两种都能用，但 gap 把"间距"和"元素自身属性"解耦，更符合"布局驱动样式"。
   align-items: flex-start 保证头像+日期从上到下紧贴顶端开始排，
   不会被右侧 description 多行撑高后把日期挤到中间 */
.character-meta {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 0.4rem;
  flex-shrink: 0;
}

.character-avatar {
  flex-shrink: 0;
  width: 56px;
  height: 56px;
  border-radius: 12px;
  background: var(--bg-primary);
  overflow: hidden;
}

.character-avatar > img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.avatar-placeholder {
  width: 100%;
  height: 100%;
  font-size: 1.5rem;
  font-weight: 700;
  color: var(--text-muted);
  display: flex;
  align-items: center;
  justify-content: center;
}

.character-info {
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
  width: 100%;
  min-width: 0;
}

.character-name {
  font-size: 1.1rem;
  font-weight: 600;
  color: var(--text-primary);
}

.character-date {
  font-size: 0.72rem;
  color: var(--text-muted);
  white-space: nowrap;
  /* 左对齐：与头像左边缘保持一致（avatar 是 12px 圆角方形，文本起点和头像起点相同） */
  text-align: left;
}

.character-tagline {
  font-size: 0.875rem;
  color: var(--text-secondary);
  margin: 0;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
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
    min-height: 0;
  }
}
</style>
