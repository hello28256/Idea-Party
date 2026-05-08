import { defineStore } from 'pinia';
import { ref } from 'vue';
import { api } from '../services/api';
import type { Message } from '../types';

export const useMessagesStore = defineStore('messages', () => {
  const messages = ref<Message[]>([]);
  const isLoading = ref(false);
  const error = ref<string | null>(null);
  const isSending = ref(false);

  async function fetchMessages(roomId: string) {
    isLoading.value = true;
    error.value = null;
    try {
      messages.value = await api.getMessages(roomId);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Unknown error';
    } finally {
      isLoading.value = false;
    }
  }

  async function sendMessage(roomId: string, content: string, role: 'user' | 'character' = 'user', characterId?: string) {
    isSending.value = true;
    error.value = null;
    try {
      const message = await api.sendMessage(roomId, { content, role, characterId });
      messages.value.push(message);
      return message;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Unknown error';
      throw e;
    } finally {
      isSending.value = false;
    }
  }

  function addMessage(message: Message) {
    messages.value.push(message);
  }

  function clearMessages() {
    messages.value = [];
  }

  return {
    messages,
    isLoading,
    error,
    isSending,
    fetchMessages,
    sendMessage,
    addMessage,
    clearMessages,
  };
});
