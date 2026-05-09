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
    class="character-card flex items-center gap-3 p-3 rounded-lg cursor-pointer transition-all duration-200"
    :class="{
      'bg-[#F0FDF4]': !isActive,
      'bg-[#F0FDF4] border-l-4 border-[#10B981]': isActive
    }"
    @click="handleClick"
  >
    <Avatar
      :src="character.avatarUrl"
      :name="character.name"
      size="small"
      :class="{ 'ring-2 ring-[#10B981]': isThinking }"
    />

    <div class="flex-1 min-w-0">
      <div class="flex items-center gap-2">
        <span class="text-sm font-medium text-[#1F2937] truncate">
          {{ character.name }}
        </span>
        <span
          v-if="character.isPreset"
          class="text-xs px-1.5 py-0.5 rounded bg-[#10B981]/10 text-[#10B981]"
        >
          预设
        </span>
      </div>
      <p
        v-if="character.description"
        class="text-xs text-[#6B7280] truncate mt-0.5"
      >
        {{ character.description }}
      </p>
    </div>

    <div v-if="isThinking" class="flex gap-0.5">
      <span class="w-1.5 h-1.5 rounded-full bg-[#10B981] animate-pulse"></span>
      <span class="w-1.5 h-1.5 rounded-full bg-[#10B981] animate-pulse animation-delay-100"></span>
      <span class="w-1.5 h-1.5 rounded-full bg-[#10B981] animate-pulse animation-delay-200"></span>
    </div>
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
