<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useRouter } from 'vue-router'
import { useSettingsStore } from '@/stores/settings'
import ConfirmLogoutModal from './ConfirmLogoutModal.vue'

// UserDropdown 是侧边栏底部的用户卡片+弹出菜单组合。
// 用户卡 click 触发弹窗；弹窗承担「设置入口 / 管理后台入口 / 退出登录」三类用户级操作。
// 与 authStore（取当前用户与 isAdmin）、settingsStore（打开设置抽屉）、router（跳转）协作。

const authStore = useAuthStore()
const router = useRouter()
const settingsStore = useSettingsStore()

const isOpen = ref(false)
// 二段式退出：先关菜单再弹确认弹窗，避免确认框叠在菜单之上造成点击错位。
const showLogoutModal = ref(false)
const dropdownRef = ref<HTMLElement | null>(null)
// menuRef/cardRef 分离：菜单和卡片在 DOM 上是兄弟节点，需要分别判断点击落点。
const menuRef = ref<HTMLElement | null>(null)
const cardRef = ref<HTMLElement | null>(null)
// 头像加载失败兜底：用户自定义头像 URL 失效时回退到本地默认头像，避免破图。
const avatarError = ref(false)

const menuItems = [
  { id: 'settings', label: '设置', emoji: '⚙️', action: () => { settingsStore.openSettings(); closeMenu() } },
  // disabled 项预留后续功能入口，先占位避免菜单结构后续频繁改动。
  { id: 'my-characters', label: '我的角色', emoji: '✨', disabled: true },
  { id: 'my-rooms', label: '我的聊天', emoji: '💬', disabled: true },
]

function goAdmin() {
  router.push('/admin/feedbacks')
  closeMenu()
}

function toggleMenu() {
  isOpen.value = !isOpen.value
}

function closeMenu() {
  isOpen.value = false
}

// 同时排除菜单和卡片：用户可能点击卡片本身（再次点击关闭），也可能在菜单内交互，
// 只有两者都不包含目标时才真正算「外部点击」。
function handleClickOutside(event: MouseEvent) {
  if (!isOpen.value) return

  const target = event.target as Node
  const menuEl = menuRef.value
  const cardEl = cardRef.value

  if (menuEl && !menuEl.contains(target) && cardEl && !cardEl.contains(target)) {
    closeMenu()
  }
}

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && isOpen.value) {
    closeMenu()
  }
}

function handleMenuItemClick(item: typeof menuItems[0]) {
  if (item.disabled) return
  if (item.action) {
    item.action()
  }
  closeMenu()
}

function handleLogoutClick() {
  closeMenu()
  showLogoutModal.value = true
}

function handleConfirmLogout() {
  showLogoutModal.value = false
  authStore.logout()
  router.push('/login')
}

onMounted(() => {
  // 监听挂到 document 而非组件根节点：菜单用 absolute 定位脱离文档流，组件内监听会漏掉外部点击。
  document.addEventListener('click', handleClickOutside)
  document.addEventListener('keydown', handleKeydown)
})

onUnmounted(() => {
  document.removeEventListener('click', handleClickOutside)
  document.removeEventListener('keydown', handleKeydown)
})
</script>

<template>
  <div class="user-dropdown" ref="dropdownRef">
    <!-- Floating Popover Menu -->
    <Transition name="popover">
      <div v-if="isOpen" class="popover-menu" ref="menuRef">
        <!-- Menu Items -->
        <button
          v-for="item in menuItems"
          :key="item.id"
          class="menu-item"
          :class="{ disabled: item.disabled }"
          @click="handleMenuItemClick(item)"
        >
          <span class="item-emoji">{{ item.emoji }}</span>
          <span class="item-label">{{ item.label }}</span>
          <span v-if="item.disabled" class="item-soon">soon</span>
        </button>

        <!-- Divider -->
        <div class="menu-divider"></div>

        <!-- Admin (only for admins) -->
        <button
          v-if="authStore.user?.isAdmin"
          class="menu-item"
          @click="goAdmin"
        >
          <span class="item-emoji">🛡️</span>
          <span class="item-label">管理后台</span>
        </button>

        <!-- Logout -->
        <button class="logout-item" @click="handleLogoutClick">
          <span class="item-emoji">🚪</span>
          <span class="item-label">退出登录</span>
        </button>
      </div>
    </Transition>

    <!-- User Card - Minimal -->
    <button class="user-card" ref="cardRef" @click="toggleMenu">
      <div class="user-avatar">
        <img
          v-if="authStore.user?.avatarUrl && !avatarError"
          :src="authStore.user.avatarUrl"
          :alt="authStore.user?.displayName"
          @error="avatarError = true"
        />
        <img
          v-else
          src="/image.png"
          :alt="authStore.user?.displayName || '用户'"
          @error="avatarError = true"
        />
      </div>
      <div class="user-info">
        <span class="user-name">{{ authStore.user?.displayName || '访客' }}</span>
        <span class="user-handle">@{{ authStore.user?.username || 'guest' }}</span>
      </div>
      <svg class="chevron" :class="{ open: isOpen }" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M18 15l-6-6-6 6"/>
      </svg>
    </button>

    <!-- Logout Confirm Modal -->
    <ConfirmLogoutModal
      :show="showLogoutModal"
      @close="showLogoutModal = false"
      @confirm="handleConfirmLogout"
    />
  </div>
</template>

<style scoped>
.user-dropdown {
  position: relative;
  margin-top: auto;
}

/* User Card - Minimal & Flat */
.user-card {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.6rem 0.6rem;
  background: transparent;
  border: 1px solid transparent;
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.15s ease;
  width: 100%;
  position: relative;
  z-index: 10;
}

.user-card:hover {
  background: var(--bg-primary);
  border-color: var(--border-color);
}

.user-avatar {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  overflow: hidden;
  flex-shrink: 0;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
  background: var(--bg-primary);
}

.user-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

@media (prefers-color-scheme: dark) {
  .user-avatar {
    background: var(--bg-primary);
  }
}

.user-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  text-align: left;
}

.user-name {
  font-size: 0.8rem;
  font-weight: 500;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  transition: color 0.25s ease;
}

.user-handle {
  font-size: 0.7rem;
  color: var(--text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  transition: color 0.25s ease;
}

.chevron {
  color: var(--text-muted);
  transition: transform 0.2s ease;
  flex-shrink: 0;
}

.chevron.open {
  transform: rotate(180deg);
}

/* Popover Menu - Minimal */
.popover-menu {
  position: absolute;
  bottom: calc(100% + 6px);
  left: 0;
  right: 0;
  background: var(--card-bg);
  border: 1px solid var(--border-color);
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.08);
  z-index: 100;
  transition: background-color 0.25s ease, border-color 0.25s ease;
}

.menu-items {
  padding: 0.35rem;
}

.menu-item,
.logout-item {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  width: 100%;
  padding: 0.5rem 0.65rem;
  background: transparent;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.15s ease;
  text-align: left;
}

.menu-item:hover:not(.disabled) {
  background: var(--bg-primary);
}

.menu-item.disabled {
  cursor: default;
}

.logout-item {
  color: #EF4444;
}

.logout-item:hover {
  background: #FEF2F2;
}

.item-emoji {
  font-size: 0.85rem;
  width: 18px;
  text-align: center;
  flex-shrink: 0;
}

.item-label {
  flex: 1;
  font-size: 0.8rem;
  color: var(--text-primary);
  transition: color 0.25s ease;
}

.logout-item .item-label {
  color: #EF4444;
  font-weight: 500;
}

.item-soon {
  font-size: 0.65rem;
  font-weight: 500;
  color: var(--text-muted);
  opacity: 0.7;
}

.menu-divider {
  height: 1px;
  background: var(--border-color);
  margin: 0.25rem 0;
  transition: background-color 0.25s ease;
}

/* Animation */
.popover-enter-active,
.popover-leave-active {
  transition: all 0.15s ease;
  transform-origin: bottom center;
}

.popover-enter-from,
.popover-leave-to {
  opacity: 0;
  transform: translateY(6px) scale(0.97);
}
</style>
