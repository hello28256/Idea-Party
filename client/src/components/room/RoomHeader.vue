<script setup lang="ts">
import { ref } from 'vue'
import type { Room } from '@/types'

interface Props {
  room: Room
}

defineProps<Props>()

const showMenu = ref(false)

function toggleMenu() {
  showMenu.value = !showMenu.value
}

function closeMenu() {
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
