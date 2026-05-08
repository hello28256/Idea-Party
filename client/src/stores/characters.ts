import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import { api } from '../services/api';
import type { Character } from '../types';

export const useCharactersStore = defineStore('characters', () => {
  const characters = ref<Character[]>([]);
  const isLoading = ref(false);
  const error = ref<string | null>(null);
  const selectedIds = ref<Set<string>>(new Set());

  const MAX_SELECTION = 6;

  const selectedCharacters = computed(() =>
    characters.value.filter((c: Character) => selectedIds.value.has(c.id))
  );

  async function fetchCharacters() {
    isLoading.value = true;
    error.value = null;
    try {
      characters.value = await api.getCharacters();
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Unknown error';
    } finally {
      isLoading.value = false;
    }
  }

  function toggleSelection(id: string) {
    if (selectedIds.value.has(id)) {
      selectedIds.value.delete(id);
    } else if (selectedIds.value.size < MAX_SELECTION) {
      selectedIds.value.add(id);
    }
    selectedIds.value = new Set(selectedIds.value);
  }

  function isSelected(id: string): boolean {
    return selectedIds.value.has(id);
  }

  function clearSelection() {
    selectedIds.value = new Set();
  }

  return {
    characters,
    isLoading,
    error,
    selectedIds,
    selectedCharacters,
    fetchCharacters,
    toggleSelection,
    isSelected,
    clearSelection,
    maxSelection: MAX_SELECTION,
  };
});
