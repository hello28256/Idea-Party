import { defineStore } from 'pinia';
import { ref } from 'vue';
import { api } from '../services/api';
import type { Room } from '../types';

export const useRoomStore = defineStore('room', () => {
  const currentRoom = ref<Room | null>(null);
  const isLoading = ref(false);
  const error = ref<string | null>(null);

  async function createRoom(name: string, theme: string | undefined, characterIds: string[]) {
    isLoading.value = true;
    error.value = null;
    try {
      currentRoom.value = await api.createRoom({ name, theme, characterIds });
      return currentRoom.value;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Unknown error';
      throw e;
    } finally {
      isLoading.value = false;
    }
  }

  async function fetchRoom(id: string) {
    isLoading.value = true;
    error.value = null;
    try {
      currentRoom.value = await api.getRoom(id);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Unknown error';
    } finally {
      isLoading.value = false;
    }
  }

  function setRoom(room: Room) {
    currentRoom.value = room;
  }

  function clearRoom() {
    currentRoom.value = null;
  }

  return {
    currentRoom,
    isLoading,
    error,
    createRoom,
    fetchRoom,
    setRoom,
    clearRoom,
  };
});
