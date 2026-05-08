<script setup lang="ts">
import type { Character } from '../../types';

const props = defineProps<{
  character: Character;
  isSelected: boolean;
  disabled: boolean;
}>();

const emit = defineEmits<{
  toggle: [id: string];
}>();

function handleClick() {
  if (!props.disabled) {
    emit('toggle', props.character.id);
  }
}
</script>

<template>
  <div
    class="character-card"
    :class="{ selected: isSelected, disabled }"
    @click="handleClick"
  >
    <div class="avatar">
      <img v-if="character.avatar" :src="character.avatar" :alt="character.name" />
      <div v-else class="avatar-placeholder">{{ character.name.charAt(0) }}</div>
    </div>
    <h3 class="name">{{ character.name }}</h3>
    <p class="era">{{ character.era }}</p>
    <p class="description">{{ character.description }}</p>
    <div class="expertise">
      <span v-for="skill in character.expertise.slice(0, 3)" :key="skill" class="tag">
        {{ skill }}
      </span>
    </div>
    <div v-if="isSelected" class="selected-indicator">Selected</div>
  </div>
</template>

<style scoped>
.character-card {
  border: 2px solid #e5e5e5;
  border-radius: 12px;
  padding: 1rem;
  cursor: pointer;
  transition: all 0.2s;
  position: relative;
  text-align: center;
}

.character-card:hover:not(.disabled) {
  border-color: #666;
  transform: translateY(-2px);
}

.character-card.selected {
  border-color: #4caf50;
  background-color: #f0f9f0;
}

.character-card.disabled:not(.selected) {
  opacity: 0.5;
  cursor: not-allowed;
}

.avatar {
  width: 80px;
  height: 80px;
  margin: 0 auto 0.5rem;
  border-radius: 50%;
  overflow: hidden;
}

.avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.avatar-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  font-size: 2rem;
  font-weight: bold;
}

.name {
  margin: 0 0 0.25rem;
  font-size: 1.1rem;
}

.era {
  margin: 0 0 0.5rem;
  color: #666;
  font-size: 0.85rem;
}

.description {
  margin: 0 0 0.75rem;
  font-size: 0.9rem;
  color: #444;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.expertise {
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem;
  justify-content: center;
}

.tag {
  background: #eee;
  padding: 0.2rem 0.5rem;
  border-radius: 4px;
  font-size: 0.75rem;
  color: #555;
}

.selected-indicator {
  position: absolute;
  top: 0.5rem;
  right: 0.5rem;
  background: #4caf50;
  color: white;
  padding: 0.25rem 0.5rem;
  border-radius: 4px;
  font-size: 0.75rem;
}
</style>
