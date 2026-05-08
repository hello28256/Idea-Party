<script setup lang="ts">
import { ref, computed } from 'vue';
import { useCharactersStore } from '../../stores/characters';
import { useRoomStore } from '../../stores/room';
import Button from '../ui/Button.vue';
import Input from '../ui/Input.vue';
import Avatar from '../ui/Avatar.vue';

const emit = defineEmits<{
  created: [roomId: string];
  cancel: [];
}>();

const charactersStore = useCharactersStore();
const roomStore = useRoomStore();

const roomName = ref('');
const selectedTheme = ref('General');

const themes = ['History', 'Literature', 'Science', 'Philosophy', 'General'];

const canCreate = computed(() => {
  return roomName.value.trim().length > 0 && charactersStore.selectedIds.size > 0;
});

async function handleCreate() {
  if (!canCreate.value) return;

  try {
    const room = await roomStore.createRoom(
      roomName.value.trim(),
      selectedTheme.value,
      Array.from(charactersStore.selectedIds)
    );
    emit('created', room.id);
  } catch (e) {
    console.error('Failed to create room:', e);
  }
}
</script>

<template>
  <div class="modal-overlay">
    <div class="modal">
      <h2>Create Chat Room</h2>

      <div class="form-group">
        <label>Room Name</label>
        <Input v-model="roomName" placeholder="Enter room name..." />
      </div>

      <div class="form-group">
        <label>Theme</label>
        <div class="theme-options">
          <button
            v-for="theme in themes"
            :key="theme"
            class="theme-btn"
            :class="{ selected: selectedTheme === theme }"
            @click="selectedTheme = theme"
          >
            {{ theme }}
          </button>
        </div>
      </div>

      <div class="form-group">
        <label>Selected Characters ({{ charactersStore.selectedIds.size }})</label>
        <div class="selected-characters">
          <div
            v-for="character in charactersStore.selectedCharacters"
            :key="character.id"
            class="character-chip"
          >
            <Avatar :src="character.avatar" :name="character.name" size="small" />
            <span>{{ character.name }}</span>
          </div>
          <p v-if="charactersStore.selectedIds.size === 0" class="no-selection">
            No characters selected
          </p>
        </div>
      </div>

      <div class="actions">
        <Button variant="secondary" @click="emit('cancel')">Cancel</Button>
        <Button :disabled="!canCreate || roomStore.isLoading" @click="handleCreate">
          {{ roomStore.isLoading ? 'Creating...' : 'Start Chat' }}
        </Button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
}

.modal {
  background: white;
  border-radius: 16px;
  padding: 2rem;
  width: 100%;
  max-width: 500px;
  max-height: 90vh;
  overflow-y: auto;
}

.modal h2 {
  margin: 0 0 1.5rem;
}

.form-group {
  margin-bottom: 1.5rem;
}

.form-group label {
  display: block;
  margin-bottom: 0.5rem;
  font-weight: 600;
  color: #333;
}

.theme-options {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}

.theme-btn {
  padding: 0.5rem 1rem;
  border: 2px solid #e0e0e0;
  border-radius: 8px;
  background: white;
  cursor: pointer;
  transition: all 0.2s;
}

.theme-btn:hover {
  border-color: #667eea;
}

.theme-btn.selected {
  border-color: #667eea;
  background: #667eea;
  color: white;
}

.selected-characters {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}

.character-chip {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem;
  background: #f5f5f5;
  border-radius: 8px;
}

.character-chip span {
  font-size: 0.875rem;
}

.no-selection {
  color: #999;
  font-size: 0.875rem;
  margin: 0;
}

.actions {
  display: flex;
  justify-content: flex-end;
  gap: 1rem;
  margin-top: 1.5rem;
}
</style>
