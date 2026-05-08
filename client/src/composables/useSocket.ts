import { ref, onMounted, onUnmounted } from 'vue';
import { io, Socket } from 'socket.io-client';

const SOCKET_URL = 'http://localhost:8080';

export interface AIChunkData {
  content: string;
  characterId: string;
  characterName: string;
}

export interface AICompleteData {
  content: string;
  characterId: string;
  characterName: string;
  messageId: string;
}

export function useSocket() {
  const socket = ref<Socket | null>(null);
  const isConnected = ref(false);
  const error = ref<string | null>(null);

  const listeners = new Map<string, Set<(data: unknown) => void>>();

  function connect() {
    if (socket.value?.connected) return;

    socket.value = io(SOCKET_URL, {
      transports: ['websocket', 'polling'],
      autoConnect: true,
    });

    socket.value.on('connect', () => {
      isConnected.value = true;
      error.value = null;
    });

    socket.value.on('disconnect', () => {
      isConnected.value = false;
    });

    socket.value.on('connect_error', (err) => {
      error.value = err.message;
      isConnected.value = false;
    });

    // Re-emit registered listeners
    listeners.forEach((callbacks, event) => {
      callbacks.forEach((callback) => {
        socket.value?.on(event, callback);
      });
    });
  }

  function disconnect() {
    if (socket.value) {
      socket.value.disconnect();
      socket.value = null;
      isConnected.value = false;
    }
  }

  function emit(event: string, data: unknown) {
    if (socket.value?.connected) {
      socket.value.emit(event, data);
    }
  }

  function on(event: string, callback: (data: unknown) => void) {
    if (!listeners.has(event)) {
      listeners.set(event, new Set());
    }
    listeners.get(event)!.add(callback);

    if (socket.value) {
      socket.value.on(event, callback);
    }
  }

  function off(event: string, callback: (data: unknown) => void) {
    listeners.get(event)?.delete(callback);
    socket.value?.off(event, callback);
  }

  function joinRoom(roomId: string) {
    emit('join-room', { roomId });
  }

  function leaveRoom(roomId: string) {
    emit('leave-room', { roomId });
  }

  function sendMessage(roomId: string, message: { content: string; role: string; characterId?: string }) {
    emit('chat message', { roomId, ...message });
  }

  onMounted(() => {
    connect();
  });

  onUnmounted(() => {
    disconnect();
  });

  return {
    socket,
    isConnected,
    error,
    connect,
    disconnect,
    emit,
    on,
    off,
    joinRoom,
    leaveRoom,
    sendMessage,
    onAIChunk: (callback: (data: AIChunkData) => void) => on('ai-chunk', callback as (data: unknown) => void),
    onAIComplete: (callback: (data: AICompleteData) => void) => on('ai-complete', callback as (data: unknown) => void),
    offAIChunk: (callback: (data: AIChunkData) => void) => off('ai-chunk', callback as (data: unknown) => void),
    offAIComplete: (callback: (data: AICompleteData) => void) => off('ai-complete', callback as (data: unknown) => void),
  };
}
