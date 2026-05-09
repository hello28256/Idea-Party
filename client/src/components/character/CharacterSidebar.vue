<script setup lang="ts">
import type { Character } from '@/types'
import Avatar from '@/components/ui/Avatar.vue'

interface Props {
  show: boolean
  characters: Character[]
  activeCharacterId?: string | null
  isThinking?: boolean
}

withDefaults(defineProps<Props>(), {
  activeCharacterId: null,
  isThinking: false
})

const emit = defineEmits<{
  close: []
  characterSelected: [character: Character | null]
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
    class="hidden lg:flex flex-col w-64 border-r border-[var(--color-border)] bg-gradient-to-b from-[var(--color-ivory)] to-[var(--color-cream)] h-full"
  >
    <!-- Header -->
    <div class="p-4 border-b border-[var(--color-border)]">
      <div class="flex items-center gap-2">
        <div class="w-8 h-8 rounded-lg bg-gradient-to-br from-[var(--color-navy)] to-[var(--color-navy-light)] flex items-center justify-center">
          <svg class="w-4 h-4 text-[var(--color-gold)]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0z" />
          </svg>
        </div>
        <h2 class="text-base font-semibold text-[var(--color-navy)] font-['Playfair_Display']">思想家</h2>
      </div>
    </div>

    <!-- Character list -->
    <div class="flex-1 overflow-y-auto p-3 flex flex-col gap-2">
      <div
        v-for="char in characters"
        :key="char.id"
        class="character-card flex items-center gap-3 p-3 rounded-xl cursor-pointer transition-all duration-300"
        :class="{
          'hover:bg-[var(--color-parchment)]': activeCharacterId !== char.id,
          'bg-gradient-to-r from-[var(--color-parchment)] to-[var(--color-ivory)] border-l-4 border-[var(--color-gold)] shadow-sm': activeCharacterId === char.id
        }"
        @click="handleCharacterClick(char)"
      >
        <Avatar
          :src="char.avatarUrl"
          :name="char.name"
          size="small"
          :is-thinking="isThinking && activeCharacterId === char.id"
        />

        <div class="flex-1 min-w-0">
          <div class="flex items-center gap-2">
            <span class="text-sm font-medium text-[var(--color-navy)] truncate">
              {{ char.name }}
            </span>
            <span
              v-if="char.isPreset || char.preset"
              class="text-xs px-2 py-0.5 rounded-full bg-[var(--color-gold)]/10 text-[var(--color-gold-dark)] border border-[var(--color-gold)]/20"
            >
              智库
            </span>
          </div>
          <p
            v-if="char.description"
            class="text-xs text-[var(--color-text-muted)] truncate mt-0.5 leading-relaxed"
          >
            {{ char.description }}
          </p>
        </div>

        <!-- Thinking indicator -->
        <div v-if="isThinking && activeCharacterId === char.id" class="flex gap-0.5">
          <span class="w-1.5 h-1.5 rounded-full bg-[var(--color-gold)] animate-pulse"></span>
          <span class="w-1.5 h-1.5 rounded-full bg-[var(--color-gold)] animate-pulse animation-delay-100"></span>
          <span class="w-1.5 h-1.5 rounded-full bg-[var(--color-gold)] animate-pulse animation-delay-200"></span>
        </div>
      </div>

      <!-- Empty state -->
      <div v-if="characters.length === 0" class="text-center py-8 px-4">
        <div class="w-16 h-16 mx-auto mb-4 rounded-full bg-[var(--color-parchment)] flex items-center justify-center">
          <svg class="w-8 h-8 text-[var(--color-gold)] opacity-50" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M18 9v3m0 0v3m0-3h3m-3 0h-3m-2-5a4 4 0 11-8 0 4 4 0 018 0zM3 20a6 6 0 0112 0v1H3v-1z" />
          </svg>
        </div>
        <p class="text-sm text-[var(--color-text-secondary)] mb-3">还没有角色</p>
        <button
          class="text-sm text-[var(--color-gold)] font-medium hover:text-[var(--color-gold-dark)] transition-colors"
          @click="$emit('characterSelected', null)"
        >
          添加角色
        </button>
      </div>

      <!-- Always show add character button -->
      <button
        class="w-full mt-2 py-2 px-3 rounded-lg border border-dashed border-[var(--color-border)] text-sm text-[var(--color-text-secondary)] hover:bg-[var(--color-parchment)] hover:border-[var(--color-gold)] transition-colors flex items-center justify-center gap-2"
        @click="$emit('characterSelected', null)"
      >
        <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
        </svg>
        添加角色
      </button>
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
          class="fixed inset-0 bg-black/40 backdrop-blur-sm"
          @click="handleClose"
        ></div>

        <!-- Drawer content -->
        <div
          class="relative w-72 h-full bg-gradient-to-b from-[var(--color-ivory)] to-[var(--color-cream)] shadow-2xl flex flex-col"
        >
          <!-- Header -->
          <div class="p-4 border-b border-[var(--color-border)] flex items-center justify-between">
            <div class="flex items-center gap-2">
              <div class="w-8 h-8 rounded-lg bg-gradient-to-br from-[var(--color-navy)] to-[var(--color-navy-light)] flex items-center justify-center">
                <svg class="w-4 h-4 text-[var(--color-gold)]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0z" />
                </svg>
              </div>
              <h2 class="text-base font-semibold text-[var(--color-navy)] font-['Playfair_Display']">思想家</h2>
            </div>
            <button
              @click="handleClose"
              class="p-2 rounded-lg hover:bg-[var(--color-parchment)] transition-colors"
              aria-label="关闭"
            >
              <svg class="w-5 h-5 text-[var(--color-text-secondary)]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>

          <!-- Character list -->
          <div class="flex-1 overflow-y-auto p-3 flex flex-col gap-2">
            <div
              v-for="char in characters"
              :key="char.id"
              class="character-card flex items-center gap-3 p-3 rounded-xl cursor-pointer transition-all duration-300"
              :class="{
                'hover:bg-[var(--color-parchment)]': activeCharacterId !== char.id,
                'bg-gradient-to-r from-[var(--color-parchment)] to-[var(--color-ivory)] border-l-4 border-[var(--color-gold)] shadow-sm': activeCharacterId === char.id
              }"
              @click="handleCharacterClick(char)"
            >
              <Avatar
                :src="char.avatarUrl"
                :name="char.name"
                size="small"
                :is-thinking="isThinking && activeCharacterId === char.id"
              />

              <div class="flex-1 min-w-0">
                <div class="flex items-center gap-2">
                  <span class="text-sm font-medium text-[var(--color-navy)] truncate">
                    {{ char.name }}
                  </span>
                  <span
                    v-if="char.isPreset || char.preset"
                    class="text-xs px-2 py-0.5 rounded-full bg-[var(--color-gold)]/10 text-[var(--color-gold-dark)] border border-[var(--color-gold)]/20"
                  >
                    智库
                  </span>
                </div>
                <p
                  v-if="char.description"
                  class="text-xs text-[var(--color-text-muted)] truncate mt-0.5 leading-relaxed"
                >
                  {{ char.description }}
                </p>
              </div>

              <!-- Thinking indicator -->
              <div v-if="isThinking && activeCharacterId === char.id" class="flex gap-0.5">
                <span class="w-1.5 h-1.5 rounded-full bg-[var(--color-gold)] animate-pulse"></span>
                <span class="w-1.5 h-1.5 rounded-full bg-[var(--color-gold)] animate-pulse animation-delay-100"></span>
                <span class="w-1.5 h-1.5 rounded-full bg-[var(--color-gold)] animate-pulse animation-delay-200"></span>
              </div>
            </div>

            <!-- Empty state -->
            <div v-if="characters.length === 0" class="text-center py-8 px-4">
              <p class="text-sm text-[var(--color-text-secondary)] mb-3">还没有角色</p>
              <button
                class="text-sm text-[var(--color-gold)] font-medium hover:text-[var(--color-gold-dark)] transition-colors"
                @click="$emit('characterSelected', null)"
              >
                添加角色
              </button>
            </div>
          </div>

          <!-- Add character button -->
          <div class="p-3 border-t border-[var(--color-border)]">
            <button
              class="w-full py-3 px-4 bg-gradient-to-r from-[var(--color-navy)] to-[var(--color-navy-light)] text-[var(--color-gold)] rounded-xl font-medium text-sm hover:opacity-90 transition-opacity flex items-center justify-center gap-2"
              @click="$emit('characterSelected', null)"
            >
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
              </svg>
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
  transition: transform 0.35s cubic-bezier(0.4, 0, 0.2, 1);
}

.drawer-enter-from,
.drawer-leave-to {
  transform: translateX(-100%);
}
</style>
