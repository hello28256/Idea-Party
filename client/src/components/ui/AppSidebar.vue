<script setup lang="ts">
// 通用侧边栏组件：渲染品牌区 + 父级传入的「创建」操作槽位 + 导航列表。
// 不内嵌任何具体路由数据：每个视图（HomeView / RoomListView 等）按需传入 navItems 子集与当前 activeId，
// 这样同一组件能服务于「发现/我的聊天/角色库/场景」等多种上下文，避免在侧边栏里硬编码路由分支。
// 组件本身无全局单例：随父级路由一起挂载/卸载，由 HomeView / RoomListView 等各自持有。

import { useRouter } from 'vue-router'
import type { NavItem } from '@/config/sidebar'

defineProps<{
  navItems: NavItem[]
  activeId: string
}>()

const router = useRouter()

// 用 router.push 而非 <router-link>：因为外层 <a href="#"> + @click.prevent 的写法更便于自定义 hover/active 样式，
// 同时避免在多次重渲染时 router-link 触发默认 scrollBehavior 的副作用。
function go(route: string) {
  router.push(route)
}
</script>

<template>
  <aside class="app-sidebar">
    <!-- 品牌 -->
    <div class="sidebar-brand">
      <img src="/image.png" alt="logo" class="sidebar-brand-logo" />
      <span class="logo-text">Idea Party</span>
    </div>

    <!-- 创建按钮插槽（不同页面不同：创建角色 / 创建对话） -->
    <slot name="create" />

    <!-- 导航 -->
    <nav class="nav-menu">
      <a
        v-for="item in navItems"
        :key="item.id"
        href="#"
        class="nav-item"
        :class="{ active: item.id === activeId }"
        @click.prevent="go(item.route)"
      >
        <span class="nav-emoji">{{ item.emoji }}</span>
        <span class="nav-label">{{ item.label }}</span>
      </a>
    </nav>
  </aside>
</template>

<style scoped>
.app-sidebar {
  background: var(--sidebar-bg, #f7f8fa);
  border-right: 1px solid var(--border-color, #e5e7eb);
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
  color: var(--text-primary, #111827);
  letter-spacing: -0.5px;
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
  color: var(--text-secondary, #6b7280);
  text-decoration: none;
  font-size: 0.875rem;
  font-weight: 400;
  transition: all 0.15s ease;
}

.nav-item:hover {
  background: var(--bg-primary, #f5f7fb);
  color: var(--text-primary, #111827);
}

.nav-item.active {
  background: var(--bg-primary, #f5f7fb);
  color: var(--text-primary, #111827);
  font-weight: 500;
}

.nav-emoji {
  font-size: 1rem;
  width: 20px;
  text-align: center;
}

.nav-label {
  flex: 1;
}
</style>
