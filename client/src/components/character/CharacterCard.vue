<script setup lang="ts">
import type { Character } from '@/types'
import Avatar from '@/components/ui/Avatar.vue'

interface Props {
  character: Character
  isActive?: boolean
  isThinking?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  isActive: false,
  isThinking: false
})

const emit = defineEmits<{
  select: [character: Character]
}>()

function handleClick() {
  emit('select', props.character)
}
</script>

<template>
  <div
    class="character-card flex items-center gap-3 p-3 rounded-xl cursor-pointer transition-all duration-300"
    :class="{
      'hover:bg-[var(--color-parchment)] hover:shadow-sm': !isActive,
      'bg-gradient-to-r from-[var(--color-parchment)] to-[var(--color-ivory)] shadow-sm border-l-4 border-[var(--color-gold)]': isActive
    }"
    @click="handleClick"
  >
    <Avatar
      :src="character.avatarUrl"
      :name="character.name"
      size="small"
      :is-thinking="isThinking"
    />

    <div class="flex-1 min-w-0">
      <div class="flex items-center gap-2">
        <span class="text-sm font-medium text-[var(--color-navy)] truncate">
          {{ character.name }}
        </span>
        <span
          v-if="character.isPreset"
          class="text-xs px-2 py-0.5 rounded-full bg-[var(--color-gold)]/10 text-[var(--color-gold-dark)] border border-[var(--color-gold)]/20"
        >
          智库
        </span>
      </div>
      <p
        v-if="character.description"
        class="text-xs text-[var(--color-text-muted)] truncate mt-0.5 leading-relaxed"
      >
        {{ character.description }}
      </p>
    </div>

    <div v-if="isThinking" class="flex gap-0.5">
      <span class="w-1.5 h-1.5 rounded-full bg-[var(--color-gold)] animate-pulse"></span>
      <span class="w-1.5 h-1.5 rounded-full bg-[var(--color-gold)] animate-pulse animation-delay-100"></span>
      <span class="w-1.5 h-1.5 rounded-full bg-[var(--color-gold)] animate-pulse animation-delay-200"></span>
    </div>

    <svg v-else class="w-4 h-4 text-[var(--color-gold)] opacity-50" fill="none" stroke="currentColor" viewBox="0 0 24 24">
      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7" />
    </svg>
  </div>
</template>

<style scoped>
.animation-delay-100 {
  animation-delay: 100ms;
}
.animation-delay-200 {
  animation-delay: 200ms;
}
</style>
