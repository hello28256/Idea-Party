<script setup lang="ts">
import { computed } from 'vue'
import type { Character } from '@/types'
import Avatar from '@/components/ui/Avatar.vue'

interface Props {
  show: boolean
  characters: Character[]
  activeCharacterId?: string | null
  isThinking?: boolean
  chatMode?: 'dialogue' | 'discussion'
  isDiscussing?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  activeCharacterId: null,
  isThinking: false,
  chatMode: 'dialogue',
  isDiscussing: false
})

const emit = defineEmits<{
  close: []
  characterSelected: [character: Character | null]
  characterDetail: [character: Character]
  switchMode: [mode: 'dialogue' | 'discussion']
}>()

const isDiscussionMode = computed(() => props.chatMode === 'discussion')

function handleCharacterClick(character: Character) {
  emit('characterSelected', character)
}

function handleCharacterDetail(character: Character, event: Event) {
  event.stopPropagation()
  emit('characterDetail', character)
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
        class="character-card group flex items-center gap-3 p-3 rounded-xl cursor-pointer transition-all duration-300"
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

        <!-- Info button -->
        <button
          class="p-1.5 rounded-lg hover:bg-[var(--color-gold)]/10 text-[var(--color-text-muted)] hover:text-[var(--color-gold)] transition-colors opacity-0 group-hover:opacity-100"
          :class="{ 'opacity-100': activeCharacterId === char.id }"
          @click="handleCharacterDetail(char, $event)"
          title="查看详情"
        >
          <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
          </svg>
        </button>
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

      <!-- Mode toggle -->
      <div class="mt-3 pt-3 border-t border-[var(--color-border)]">
        <!-- Discussion mode toggle -->
        <button
          class="w-full py-3 px-3 rounded-xl border-2 transition-all mode-toggle-btn"
          :class="isDiscussionMode
            ? 'bg-gradient-to-r from-[var(--color-navy)] to-[var(--color-navy-light)] border-[var(--color-navy)] text-white shadow-lg'
            : 'bg-[var(--color-parchment)] border-[var(--color-border)] text-[var(--color-text-secondary)] hover:border-[var(--color-gold)]'"
          @click="$emit('switchMode', isDiscussionMode ? 'dialogue' : 'discussion')"
        >
          <div class="flex items-center justify-between">
            <div class="flex items-center gap-2">
              <!-- Discussion icon -->
              <div
                class="w-8 h-8 rounded-lg flex items-center justify-center transition-colors"
                :class="isDiscussionMode ? 'bg-white/20' : 'bg-[var(--color-gold)]/10'"
              >
                <svg v-if="isDiscussionMode" class="w-4 h-4 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 8h2a2 2 0 012 2v6a2 2 0 01-2 2h-2v4l-4-4H9a1.994 1.994 0 01-1.414-.586m0 0L11 14h4a2 2 0 002-2V6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2v4l.586-.586z" />
                </svg>
                <svg v-else class="w-4 h-4 text-[var(--color-gold-dark)]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
                </svg>
              </div>
              <div class="text-left">
                <div class="text-sm font-semibold">{{ isDiscussionMode ? '讨论模式' : '对话模式' }}</div>
                <div class="text-[10px] opacity-80">
                  {{ isDiscussionMode ? '角色轮流持续讨论' : '角色响应一次结束' }}
                </div>
              </div>
            </div>
            <!-- Toggle switch -->
            <div
              class="w-12 h-6 rounded-full relative transition-colors"
              :class="isDiscussionMode ? 'bg-white/30' : 'bg-[var(--color-border)]'"
            >
              <div
                class="w-5 h-5 rounded-full absolute top-0.5 transition-all shadow-md"
                :class="isDiscussionMode ? 'left-[26px] bg-white' : 'left-0.5 bg-[var(--color-text-muted)]'"
              ></div>
            </div>
          </div>
        </button>

        <!-- Discussion mode description -->
        <div v-if="isDiscussionMode" class="mt-2 px-2 py-2 rounded-lg bg-[var(--color-navy)]/5 text-[10px] text-[var(--color-text-muted)]">
          <div class="flex items-start gap-1.5">
            <svg class="w-3 h-3 mt-0.5 text-[var(--color-gold)] shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
            </svg>
            <span>角色按顺序轮流发言，可暂停/继续</span>
          </div>
        </div>
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
              class="character-card group flex items-center gap-3 p-3 rounded-xl cursor-pointer transition-all duration-300"
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

              <!-- Info button -->
              <button
                class="p-1.5 rounded-lg hover:bg-[var(--color-gold)]/10 text-[var(--color-text-muted)] hover:text-[var(--color-gold)] transition-colors opacity-0 group-hover:opacity-100"
                :class="{ 'opacity-100': activeCharacterId === char.id }"
                @click="handleCharacterDetail(char, $event)"
                title="查看详情"
              >
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                </svg>
              </button>
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

          <!-- Mode toggle for mobile -->
          <div class="p-3 border-t border-[var(--color-border)]">
            <button
              class="w-full py-3 px-3 rounded-xl border-2 transition-all mode-toggle-btn"
              :class="isDiscussionMode
                ? 'bg-gradient-to-r from-[var(--color-navy)] to-[var(--color-navy-light)] border-[var(--color-navy)] text-white shadow-lg'
                : 'bg-[var(--color-parchment)] border-[var(--color-border)] text-[var(--color-text-secondary)] hover:border-[var(--color-gold)]'"
              @click="$emit('switchMode', isDiscussionMode ? 'dialogue' : 'discussion')"
            >
              <div class="flex items-center justify-between">
                <div class="flex items-center gap-2">
                  <div
                    class="w-8 h-8 rounded-lg flex items-center justify-center transition-colors"
                    :class="isDiscussionMode ? 'bg-white/20' : 'bg-[var(--color-gold)]/10'"
                  >
                    <svg v-if="isDiscussionMode" class="w-4 h-4 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 8h2a2 2 0 012 2v6a2 2 0 01-2 2h-2v4l-4-4H9a1.994 1.994 0 01-1.414-.586m0 0L11 14h4a2 2 0 002-2V6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2v4l.586-.586z" />
                    </svg>
                    <svg v-else class="w-4 h-4 text-[var(--color-gold-dark)]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
                    </svg>
                  </div>
                  <div class="text-left">
                    <div class="text-sm font-semibold">{{ isDiscussionMode ? '讨论模式' : '对话模式' }}</div>
                    <div class="text-[10px] opacity-80">
                      {{ isDiscussionMode ? '角色轮流持续讨论' : '角色响应一次结束' }}
                    </div>
                  </div>
                </div>
                <div
                  class="w-12 h-6 rounded-full relative transition-colors"
                  :class="isDiscussionMode ? 'bg-white/30' : 'bg-[var(--color-border)]'"
                >
                  <div
                    class="w-5 h-5 rounded-full absolute top-0.5 transition-all shadow-md"
                    :class="isDiscussionMode ? 'left-[26px] bg-white' : 'left-0.5 bg-[var(--color-text-muted)]'"
                  ></div>
                </div>
              </div>
            </button>
          </div>

          <!-- Add character button -->
          <div class="p-3 pt-0">
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

.character-card {
  position: relative;
  overflow: hidden;
}

.character-card::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: linear-gradient(135deg, rgba(201, 169, 98, 0.08) 0%, transparent 60%);
  opacity: 0;
  transition: opacity 0.3s ease;
  border-radius: inherit;
}

.character-card:hover::before {
  opacity: 1;
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

/* Mode toggle button */
.mode-toggle-btn {
  position: relative;
  overflow: hidden;
}

.mode-toggle-btn::before {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(135deg, rgba(255,255,255,0.15) 0%, transparent 50%);
  opacity: 0;
  transition: opacity 0.3s ease;
}

.mode-toggle-btn:hover::before {
  opacity: 1;
}
</style>
