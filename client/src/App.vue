<script setup lang="ts">
import { ref } from 'vue';
import { useCharactersStore } from './stores/characters';
import { useRoomStore } from './stores/room';
import CharacterGrid from './components/characters/CharacterGrid.vue';
import CreateRoomModal from './components/room/CreateRoomModal.vue';
import ChatRoom from './components/chat/ChatRoom.vue';
import Button from './components/ui/Button.vue';

const charactersStore = useCharactersStore();
const roomStore = useRoomStore();

const view = ref<'selection' | 'create' | 'chat'>('selection');

function handleStartCreate() {
  if (charactersStore.selectedIds.size > 0) {
    view.value = 'create';
  }
}

function handleRoomCreated(roomId: string) {
  charactersStore.clearSelection();
  roomStore.fetchRoom(roomId);
  view.value = 'chat';
}

function handleCancelCreate() {
  view.value = 'selection';
}

function handleBackToSelection() {
  roomStore.clearRoom();
  view.value = 'selection';
}
</script>

<template>
  <div id="app">
    <template v-if="view === 'selection'">
      <header class="app-header">
        <h1>IdeaParty</h1>
        <p>AI Multi-Character Chat Platform</p>
      </header>
      <CharacterGrid />
      <div class="actions" v-if="charactersStore.selectedIds.size > 0">
        <Button @click="handleStartCreate">
          Create Room with {{ charactersStore.selectedIds.size }} Characters
        </Button>
      </div>
    </template>

    <template v-else-if="view === 'create'">
      <CreateRoomModal
        @created="handleRoomCreated"
        @cancel="handleCancelCreate"
      />
    </template>

    <template v-else-if="view === 'chat'">
      <button class="back-btn" @click="handleBackToSelection">
        &larr; Back to Characters
      </button>
      <ChatRoom />
    </template>
  </div>
</template>

<style scoped>
#app {
  min-height: 100vh;
  background: #fafafa;
}

.app-header {
  text-align: center;
  padding: 2rem 1rem;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
}

.app-header h1 {
  margin: 0;
  font-size: 2rem;
}

.app-header p {
  margin: 0.5rem 0 0;
  opacity: 0.9;
}

.actions {
  padding: 1rem;
  text-align: center;
}

.back-btn {
  position: absolute;
  top: 1rem;
  left: 1rem;
  padding: 0.5rem 1rem;
  background: white;
  border: 1px solid #ddd;
  border-radius: 8px;
  cursor: pointer;
  z-index: 10;
}

.back-btn:hover {
  background: #f5f5f5;
}
</style>
