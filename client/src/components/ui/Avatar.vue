<script setup lang="ts">
defineProps<{
  src?: string | null;
  name: string;
  size?: 'small' | 'medium' | 'large';
}>();

const sizeClasses = {
  small: 'avatar-small',
  medium: 'avatar-medium',
  large: 'avatar-large',
};
</script>

<template>
  <div class="avatar" :class="sizeClasses[size || 'medium']">
    <img v-if="src" :src="src" :alt="name" />
    <div v-else class="avatar-placeholder">
      {{ name.charAt(0) }}
    </div>
  </div>
</template>

<style scoped>
.avatar {
  border-radius: 50%;
  overflow: hidden;
  flex-shrink: 0;
}

.avatar-small {
  width: 32px;
  height: 32px;
}

.avatar-medium {
  width: 48px;
  height: 48px;
}

.avatar-large {
  width: 64px;
  height: 64px;
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
  font-weight: bold;
}

.avatar-small .avatar-placeholder {
  font-size: 0.875rem;
}

.avatar-medium .avatar-placeholder {
  font-size: 1.25rem;
}

.avatar-large .avatar-placeholder {
  font-size: 1.5rem;
}
</style>
