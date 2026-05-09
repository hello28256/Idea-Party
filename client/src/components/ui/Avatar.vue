<script setup lang="ts">
defineProps<{
  src?: string | null
  name: string
  size?: 'small' | 'medium' | 'large'
  isThinking?: boolean
}>()

const sizeClasses = {
  small: 'avatar-small',
  medium: 'avatar-medium',
  large: 'avatar-large',
}
</script>

<template>
  <div class="avatar-wrapper" :class="{ 'is-thinking': isThinking }">
    <div class="avatar" :class="sizeClasses[size || 'medium']">
      <img v-if="src" :src="src" :alt="name" />
      <div v-else class="avatar-placeholder">
        {{ name.charAt(0) }}
      </div>
    </div>
    <div v-if="isThinking" class="thinking-ring"></div>
  </div>
</template>

<style scoped>
.avatar-wrapper {
  position: relative;
  display: inline-flex;
}

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
  width: 40px;
  height: 40px;
}

.avatar-large {
  width: 48px;
  height: 48px;
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
  font-size: 1.125rem;
}

.avatar-large .avatar-placeholder {
  font-size: 1.25rem;
}

/* Thinking indicator - pulsing ring */
.thinking-ring {
  position: absolute;
  inset: -4px;
  border-radius: 50%;
  border: 2px solid #10B981;
  animation: pulse-ring 1.5s ease-in-out infinite;
}

@keyframes pulse-ring {
  0% {
    opacity: 1;
    transform: scale(1);
  }
  50% {
    opacity: 0.5;
    transform: scale(1.1);
  }
  100% {
    opacity: 1;
    transform: scale(1);
  }
}
</style>
