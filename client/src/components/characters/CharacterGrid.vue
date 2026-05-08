<script setup lang="ts">
import { onMounted } from 'vue';
import { useCharactersStore } from '../../stores/characters';
import CharacterCard from './CharacterCard.vue';

const store = useCharactersStore();

onMounted(() => {
  store.fetchCharacters();
});
</script>

<template>
  <div class="character-grid-container">
    <div class="header">
      <h2>Select Characters</h2>
      <p class="selection-count">
        {{ store.selectedIds.size }} / {{ store.maxSelection }} selected
      </p>
    </div>

    <div v-if="store.isLoading" class="loading">Loading characters...</div>
    <div v-else-if="store.error" class="error">{{ store.error }}</div>
    <div v-else class="character-grid">
      <CharacterCard
        v-for="character in store.characters"
        :key="character.id"
        :character="character"
        :is-selected="store.isSelected(character.id)"
        :disabled="!store.isSelected(character.id) && store.selectedIds.size >= store.maxSelection"
        @toggle="store.toggleSelection"
      />
    </div>
  </div>
</template>

<style scoped>
.character-grid-container {
  padding: 1rem;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1rem;
}

.header h2 {
  margin: 0;
}

.selection-count {
  margin: 0;
  color: #666;
}

.loading,
.error {
  text-align: center;
  padding: 2rem;
  color: #666;
}

.error {
  color: #d32f2f;
}

.character-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 1rem;
}
</style>
