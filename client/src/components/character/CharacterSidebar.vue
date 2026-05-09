<script setup lang="ts">
import type { Character } from '@/types'
import Avatar from '@/components/ui/Avatar.vue'

interface Props {
  show: boolean
  characters: Character[]
  activeCharacterId?: string | null
  isThinking?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  activeCharacterId: null,
  isThinking: false
})

const emit = defineEmits<{
  close: []
  characterSelected: [character: Character]
}>()

function handleCharacterClick(character: Character) {
  emit('characterSelected', character)
}

function handleClose() {
  emit('close')
}
</script>

<template>
  <!-- Desktop sidebar: always visible at lg+ -->
  <aside
    class="hidden lg:flex flex-col w-60 border-r border-[#E5E7EB] bg-[#FAFAFA] h-full"
  >
    <!-- Header -->
    <div class="p-4 border-b border-[#E5E7EB]">
      <h2 class="text-base font-semibold text-[#374151]">角色列表</h2>
    </div>

    <!-- Character list -->
    <div class="flex-1 overflow-y-auto p-3 flex flex-col gap-2">
      <div
        v-for="char in characters"
        :key="char.id"
        class="character-card flex items-center gap-3 p-3 rounded-lg cursor-pointer transition-all duration-200"
        :class="{
          'bg-[#F0FDF4]': activeCharacterId !== char.id,
          'bg-[#F0FDF4] border-l-4 border-[#10B981]': activeCharacterId === char.id
        }"
        @click="handleCharacterClick(char)"
      >
        <Avatar
          :src="char.avatarUrl"
          :name="char.name"
          size="small"
          :class="{ 'ring-2 ring-[#10B981]': isThinking && activeCharacterId === char.id }"
        />

        <div class="flex-1 min-w-0">
          <div class="flex items-center gap-2">
            <span class="text-sm font-medium text-[#1F2937] truncate">
              {{ char.name }}
            </span>
            <span
              v-if="char.isPreset"
              class="text-xs px-1.5 py-0.5 rounded bg-[#10B981]/10 text-[#10B981]"
            >
              预设
            </span>
          </div>
          <p
            v-if="char.description"
            class="text-xs text-[#6B7280] truncate mt-0.5"
          >
            {{ char.description }}
          </p>
        </div>

        <!-- Thinking indicator -->
        <div v-if="isThinking && activeCharacterId === char.id" class="flex gap-0.5">
          <span class="w-1.5 h-1.5 rounded-full bg-[#10B981] animate-pulse"></span>
          <span class="w-1.5 h-1.5 rounded-full bg-[#10B981] animate-pulse animation-delay-100"></span>
          <span class="w-1.5 h-1.5 rounded-full bg-[#10B981] animate-pulse animation-delay-200"></span>
        </div>
      </div>

      <!-- Empty state -->
      <div v-if="characters.length === 0" class="text-center py-8 px-4">
        <p class="text-sm text-[#6B7280] mb-3">还没有角色</p>
        <button
          class="text-sm text-[#10B981] font-medium"
          @click="$emit('characterSelected', null)"
        >
          添加角色
        </button>
      </div>
    </div>
  </aside>

  <!-- Mobile drawer: slide-in from left -->
  <Teleport to="body">
    <Transition name="drawer">
      <div
        v-if="show"
        class="fixed inset-y-0 left-0 z-50 lg:hidden"
      >
        <!-- Backdrop -->
        <div
          class="fixed inset-0 bg-black/50"
          @click="handleClose"
        ></div>

        <!-- Drawer content -->
        <div
          class="relative w-64 h-full bg-[#FAFAFA] shadow-xl flex flex-col"
        >
          <!-- Header -->
          <div class="p-4 border-b border-[#E5E7EB] flex items-center justify-between">
            <h2 class="text-base font-semibold text-[#374151]">角色列表</h2>
            <button
              @click="handleClose"
              class="p-1.5 rounded-md hover:bg-[#F0FDF4] transition-colors"
              aria-label="关闭"
            >
              <svg class="w-5 h-5 text-[#6B7280]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>

          <!-- Character list -->
          <div class="flex-1 overflow-y-auto p-3 flex flex-col gap-2">
            <div
              v-for="char in characters"
              :key="char.id"
              class="character-card flex items-center gap-3 p-3 rounded-lg cursor-pointer transition-all duration-200"
              :class="{
                'bg-[#F0FDF4]': activeCharacterId !== char.id,
                'bg-[#F0FDF4] border-l-4 border-[#10B981]': activeCharacterId === char.id
              }"
              @click="handleCharacterClick(char)"
            >
              <Avatar
                :src="char.avatarUrl"
                :name="char.name"
                size="small"
                :class="{ 'ring-2 ring-[#10B981]': isThinking && activeCharacterId === char.id }"
              />

              <div class="flex-1 min-w-0">
                <div class="flex items-center gap-2">
                  <span class="text-sm font-medium text-[#1F2937] truncate">
                    {{ char.name }}
                  </span>
                  <span
                    v-if="char.isPreset"
                    class="text-xs px-1.5 py-0.5 rounded bg-[#10B981]/10 text-[#10B981]"
                  >
                    预设
                  </span>
                </div>
                <p
                  v-if="char.description"
                  class="text-xs text-[#6B7280] truncate mt-0.5"
                >
                  {{ char.description }}
                </p>
              </div>

              <!-- Thinking indicator -->
              <div v-if="isThinking && activeCharacterId === char.id" class="flex gap-0.5">
                <span class="w-1.5 h-1.5 rounded-full bg-[#10B981] animate-pulse"></span>
                <span class="w-1.5 h-1.5 rounded-full bg-[#10B981] animate-pulse animation-delay-100"></span>
                <span class="w-1.5 h-1.5 rounded-full bg-[#10B981] animate-pulse animation-delay-200"></span>
              </div>
            </div>

            <!-- Empty state -->
            <div v-if="characters.length === 0" class="text-center py-8 px-4">
              <p class="text-sm text-[#6B7280] mb-3">还没有角色</p>
              <button
                class="text-sm text-[#10B981] font-medium"
                @click="$emit('characterSelected', null)"
              >
                添加角色
              </button>
            </div>
          </div>

          <!-- Add character button -->
          <div class="p-3 border-t border-[#E5E7EB]">
            <button
              class="w-full py-3 px-4 bg-[#10B981] text-white rounded-lg font-medium text-sm hover:bg-[#059669] transition-colors"
              @click="$emit('characterSelected', null)"
            >
              添加角色
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.animation-delay-100 {
  animation-delay: 100ms;
}
.animation-delay-200 {
  animation-delay: 200ms;
}

/* Drawer transition */
.drawer-enter-active,
.drawer-leave-active {
  transition: transform 0.3s ease;
}

.drawer-enter-from,
.drawer-leave-to {
  transform: translateX(-100%);
}

.drawer-enter-from .fixed.inset-0,
.drawer-leave-to .fixed.inset-0 {
  opacity: 0;
}
</style>
