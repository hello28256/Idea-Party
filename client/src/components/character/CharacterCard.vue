<script setup lang="ts">
// 角色列表中的单个角色卡片：负责展示角色基础信息（头像/名称/描述），
// 并向父列表反馈"选中"事件。由 CharacterListView 之类容器按数组渲染，
// 通过 isActive 区分当前正在对话的角色，通过 isThinking 展示正在发言动画。
import type { Character } from '@/types'
import Avatar from '@/components/ui/Avatar.vue'

interface Props {
  character: Character
  isActive?: boolean
  isThinking?: boolean
}

// 默认值让父组件可省略非必填状态，避免每次使用都写两遍 false。
const props = withDefaults(defineProps<Props>(), {
  isActive: false,
  isThinking: false
})

const emit = defineEmits<{
  select: [character: Character]
}>()

// 点击事件向上抛出，由父组件决定切换当前角色或加入房间等业务动作；
// 卡片本身不持有选中状态，保持纯展示+事件转发，避免与父级选中态冲突。
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
.character-card {
  position: relative;
  overflow: hidden;
}

.character-card::before {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(135deg, rgba(201, 169, 98, 0.05) 0%, transparent 60%);
  opacity: 0;
  transition: opacity 0.3s ease;
  border-radius: inherit;
}

.character-card:hover::before {
  opacity: 1;
}

.animation-delay-100 {
  animation-delay: 100ms;
}
.animation-delay-200 {
  animation-delay: 200ms;
}
</style>
