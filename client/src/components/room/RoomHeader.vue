<script setup lang="ts">
// 房间视图顶栏：展示房间名 + 角色数量徽标，并承载右侧操作菜单的容器。
// 菜单项由父组件通过具名 slot "menu-items" 注入，使本组件可在不同上下文（房间详情 / 我的房间等）
// 复用，避免把删除、重命名等业务操作耦合进纯展示组件。
// 父组件：RoomDetailView、MyRoomsList 等所有需要展示房间名 + 操作菜单的视图。
import { ref } from 'vue'
import type { Room } from '@/types'

// room：当前房间实体；characterCount 来自后端聚合，避免前端再发请求统计。
interface Props {
  // 当前房间实体；characterCount 来自后端聚合，避免前端再发请求统计。
  room: Room
}

defineProps<Props>()

// 本地 UI 状态：是否展开右侧下拉菜单。与父级路由/数据解耦，关闭时由 mouseleave 自动收起。
const showMenu = ref(false)

// 切换菜单可见性；按钮 click 会冒泡，但菜单容器 @mouseleave 已独立处理关闭，
// 这里只需翻转布尔值，无需阻止事件。
function toggleMenu() {
  // 切换菜单可见性；按钮 click 会冒泡，但菜单容器 @mouseleave 已独立处理关闭，
  // 这里只需翻转布尔值，无需阻止事件。
  showMenu.value = !showMenu.value
}

// 鼠标移出菜单区域时触发，用于桌面端的"鼠标离开即收起"交互；
// 移动端无 hover 时由父级点击外部或路由切换等场景调用。
function closeMenu() {
  // 鼠标移出菜单区域时触发，用于桌面端的"鼠标离开即收起"交互；
  // 移动端无 hover 时由父级点击外部或路由切换等场景调用。
  showMenu.value = false
}
</script>

<template>
  <div class="flex items-center justify-between px-4 py-3 border-b border-border bg-white">
    <div class="flex items-center gap-3">
      <h1 class="text-2xl font-semibold text-text-primary">{{ room.name }}</h1>
      <span
        v-if="room.characterCount > 0"
        class="px-2 py-0.5 text-xs font-medium bg-secondary-bg text-text-secondary rounded-full"
      >
        {{ room.characterCount }} 个角色
      </span>
    </div>

    <div class="relative">
      <button
        class="p-2 rounded-md hover:bg-gray-100 text-text-secondary"
        @click="toggleMenu"
        aria-label="设置菜单"
      >
        <svg
          class="w-5 h-5"
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path
            stroke-linecap="round"
            stroke-linejoin="round"
            stroke-width="2"
            d="M12 5v.01M12 12v.01M12 19v.01M12 6a1 1 0 110-2 1 1 0 010 2zm0 7a1 1 0 110-2 1 1 0 010 2zm0 7a1 1 0 110-2 1 1 0 010 2z"
          />
        </svg>
      </button>

      <div
        v-if="showMenu"
        class="absolute right-0 mt-1 w-48 bg-white rounded-md shadow-lg border border-border py-1 z-10"
        @mouseleave="closeMenu"
      >
        <slot name="menu-items" />
      </div>
    </div>
  </div>
</template>
