<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useRouter } from 'vue-router'
import ConfirmLogoutModal from './ConfirmLogoutModal.vue'

const authStore = useAuthStore()
const router = useRouter()

const isOpen = ref(false)
const showLogoutModal = ref(false)
const dropdownRef = ref<HTMLElement | null>(null)
const menuRef = ref<HTMLElement | null>(null)
const cardRef = ref<HTMLElement | null>(null)

const menuItems = [
  { id: 'settings', label: '设置', emoji: '⚙️', action: () => router.push('/settings') },
  { id: 'my-characters', label: '我的角色', emoji: '✨', disabled: true },
  { id: 'my-rooms', label: '我的聊天室', emoji: '💬', disabled: true },
]

function toggleMenu() {
  isOpen.value = !isOpen.value
}

function closeMenu() {
  isOpen.value = false
}

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
          v-if="authStore.user?.avatarUrl"
          :src="authStore.user.avatarUrl"
          :alt="authStore.user?.name || '用户'"
          @error="$event.target.style.display = 'none'"
        />
        <div v-else class="user-avatar-placeholder">
          {{ authStore.user?.name?.charAt(0) || '访客'.charAt(0) }}
        </div>
      </div>
      <div class="user-info">
        <span class="user-name">{{ authStore.user?.name || '访客' }}</span>
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
  background: #e8e8ed;
  border-color: #e0e0e5;
}

@media (prefers-color-scheme: dark) {
  .user-card:hover {
    background: #374151;
    border-color: #4b5563;
  }

  .user-name {
    color: #f3f4f6;
  }

  .chevron {
    color: #9ca3af;
  }
}

.user-avatar {
  width: 40px;
  height: 40px;
  border-radius: 9999px;
  overflow: hidden;
  flex-shrink: 0;
  background: #e5e7eb;
}

.user-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.user-avatar-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #e5e7eb;
  color: #374151;
  font-size: 1rem;
  font-weight: 600;
}

@media (prefers-color-scheme: dark) {
  .user-avatar {
    background: #374151;
  }

  .user-avatar img {
    filter: brightness(0.9);
  }

  .user-avatar-placeholder {
    background: #374151;
    color: white;
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
  color: #1E293B;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.chevron {
  color: #94A3B8;
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
  background: #FFFFFF;
  border: 1px solid #E5E7EB;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.08);
  z-index: 100;
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
  background: #f5f5f7;
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
  color: #1E293B;
}

.logout-item .item-label {
  color: #EF4444;
  font-weight: 500;
}

.item-soon {
  font-size: 0.65rem;
  font-weight: 500;
  color: #94A3B8;
  opacity: 0.7;
}

.menu-divider {
  height: 1px;
  background: #E5E7EB;
  margin: 0.25rem 0;
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

@media (prefers-color-scheme: dark) {
  .popover-menu {
    background: #1f2937;
    border-color: #374151;
  }

  .menu-item:hover:not(.disabled) {
    background: #374151;
  }

  .menu-item.disabled {
    opacity: 0.5;
  }

  .logout-item:hover {
    background: #7f1d1d;
  }

  .item-label {
    color: #f3f4f6;
  }

  .logout-item .item-label {
    color: #f87171;
  }

  .menu-divider {
    background: #374151;
  }

  .item-soon {
    color: #9ca3af;
  }
}
</style>
