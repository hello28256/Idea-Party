import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { Character, CharacterRequest } from '@/types'
import { charactersApi } from '@/api/characters'

export const useCharacterStore = defineStore('character', () => {
  // State
  const characters = ref<Character[]>([])
  const presets = ref<Character[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  // Computed
  const userCharacters = computed(() => characters.value.filter(c => !c.isPreset))

  // Check if user already has a character with the same name
  function hasDuplicateName(creatorUserId: string, name: string, excludeId?: string): boolean {
    return characters.value.some(c =>
      c.creatorUserId === creatorUserId &&
      c.name.toLowerCase() === name.toLowerCase() &&
      c.id !== excludeId
    )
  }

  // Actions
  async function fetchCharacters() {
    loading.value = true
    error.value = null
    try {
      const response = await charactersApi.list()
      characters.value = response.data
    } catch (e: any) {
      error.value = e.response?.data?.message || e.response?.data?.error || e.message || 'Failed to fetch characters'
      console.error('[DEBUG] fetchCharacters failed:', e)
    } finally {
      loading.value = false
    }
  }

  async function fetchPresets() {
    loading.value = true
    error.value = null
    try {
      const response = await charactersApi.getPresets()
      presets.value = response.data
    } catch (e: any) {
      error.value = e.response?.data?.message || e.response?.data?.error || e.message || 'Failed to fetch presets'
      console.error('[DEBUG] fetchPresets failed:', e)
    } finally {
      loading.value = false
    }
  }

  async function createCharacter(data: CharacterRequest): Promise<Character | null> {
    loading.value = true
    error.value = null
    try {
      const response = await charactersApi.create(data)
      characters.value.push(response.data)
      return response.data
    } catch (e: any) {
      error.value = e.response?.data?.message || e.response?.data?.error || e.message || 'Failed to create character'
      console.error('[DEBUG] createCharacter failed:', e)
      return null
    } finally {
      loading.value = false
    }
  }

  async function updateCharacter(id: string, data: CharacterRequest): Promise<Character | null> {
    loading.value = true
    error.value = null
    try {
      const response = await charactersApi.update(id, data)
      const index = characters.value.findIndex(c => c.id === id)
      if (index !== -1) {
        characters.value[index] = response.data
      }
      return response.data
    } catch (e: any) {
      error.value = e.response?.data?.message || e.response?.data?.error || e.message || 'Failed to update character'
      console.error('[DEBUG] updateCharacter failed:', e)
      return null
    } finally {
      loading.value = false
    }
  }

  async function deleteCharacter(id: string): Promise<boolean> {
    loading.value = true
    error.value = null
    try {
      await charactersApi.remove(id)
      characters.value = characters.value.filter(c => c.id !== id)
      return true
    } catch (e: any) {
      error.value = e.response?.data?.message || e.response?.data?.error || e.message || 'Failed to delete character'
      console.error('[DEBUG] deleteCharacter failed:', e)
      return false
    } finally {
      loading.value = false
    }
  }

  function getCharacterById(id: string): Character | undefined {
    return characters.value.find(c => c.id === id)
  }

  async function uploadAvatar(file: File): Promise<string | null> {
    try {
      const response = await charactersApi.uploadAvatar(file)
      return response.data.url
    } catch (e: any) {
      console.error('[DEBUG] uploadAvatar failed:', e)
      return null
    }
  }

  return {
    characters,
    presets,
    loading,
    error,
    userCharacters,
    hasDuplicateName,
    fetchCharacters,
    fetchPresets,
    createCharacter,
    updateCharacter,
    deleteCharacter,
    getCharacterById,
    uploadAvatar
  }
})
