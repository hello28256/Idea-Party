<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useCharacterStore } from '@/stores/character'
import { useAuthStore } from '@/stores/auth'
import type { Character } from '@/types'

const router = useRouter()
const characterStore = useCharacterStore()
const authStore = useAuthStore()

const mounted = ref(false)

onMounted(() => {
  characterStore.fetchCharacters()
  setTimeout(() => { mounted.value = true }, 50)
})

// Get current user's characters
const myCharacters = computed(() => {
  if (!authStore.user) return []
  return characterStore.characters.filter(
    c => c.creatorUserId === authStore.user!.id && !c.isPreset
  )
})

function goToCreate() {
  router.push('/characters/create')
}

function goToEdit(character: Character) {
  router.push(`/characters/edit/${character.id}`)
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
    <aside class="sidebar">
      <!-- Logo -->
      <div class="sidebar-brand">
        <img src="/image.png" alt="logo" class="sidebar-brand-logo" />
        <span class="logo-text">Idea Party</span>
      </div>

      <!-- Create Button -->
      <button class="create-btn" @click="goToCreate">
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
        <a
          href="#"
          class="nav-item"
          @click.prevent="router.push('/rooms?tab=categories')"
        >
          <span class="nav-emoji">📂</span>
          <span class="nav-label">分类</span>
        </a>
        <a
          href="#"
          class="nav-item"
          @click.prevent="router.push('/rooms?tab=my-rooms')"
        >
          <span class="nav-emoji">💬</span>
          <span class="nav-label">我的聊天室</span>
        </a>
      </nav>
    </aside>

    <!-- Main Content -->
    <main class="main-content">
      <header class="content-header">
        <h1 class="page-title">我的角色库</h1>
        <button class="create-btn-large" @click="goToCreate">
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
        <button class="empty-btn" @click="goToCreate">
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
          <button class="edit-btn" @click="goToEdit(character)">
            <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" />
            </svg>
            编辑
          </button>
        </div>
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

.create-btn:hover {
  opacity: 0.85;
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
  background: linear-gradient(135deg, #4F7DF3 0%, #6B7FFF 100%);
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
  box-shadow: 0 4px 12px rgba(79, 125, 243, 0.3);
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
  background: linear-gradient(135deg, #4F7DF3 0%, #6B7FFF 100%);
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
  box-shadow: 0 6px 16px rgba(79, 125, 243, 0.35);
}

.character-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 1.25rem;
}

.character-card {
  display: flex;
  align-items: flex-start;
  gap: 1rem;
  padding: 1.25rem;
  background: var(--card-bg);
  border: 1px solid var(--border-color);
  border-radius: 16px;
  transition: all 0.25s ease;
}

.character-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.08);
  border-color: #4F7DF3;
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

.edit-btn {
  display: flex;
  align-items: center;
  gap: 0.35rem;
  padding: 0.5rem 0.75rem;
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  color: var(--text-secondary);
  font-size: 0.8rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s ease;
  flex-shrink: 0;
}

.edit-btn:hover {
  background: var(--border-color);
  color: var(--text-primary);
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
