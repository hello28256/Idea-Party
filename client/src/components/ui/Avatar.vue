<script setup lang="ts">
interface Props {
  src?: string | null
  name: string
  size?: 'small' | 'medium' | 'large'
  isThinking?: boolean
  gradient?: string
}

const props = withDefaults(defineProps<Props>(), {
  src: null,
  size: 'medium',
  isThinking: false,
  gradient: 'linear-gradient(135deg, var(--color-navy) 0%, var(--color-navy-light) 100%)'
})

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
      <div v-else class="avatar-placeholder" :style="{ background: gradient }">
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
  flex-shrink: 0;
}

.avatar {
  border-radius: 50%;
  overflow: hidden;
  flex-shrink: 0;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
  transition: transform 0.3s ease, box-shadow 0.3s ease;
}

.avatar:hover {
  transform: scale(1.05);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.2);
}

.avatar-small {
  width: 32px;
  height: 32px;
}

.avatar-medium {
  width: 44px;
  height: 44px;
}

.avatar-large {
  width: 52px;
  height: 52px;
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
  color: var(--color-gold-light);
  font-family: 'Playfair Display', serif;
  font-weight: 600;
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.2);
}

.avatar-small .avatar-placeholder {
  font-size: 0.875rem;
}

.avatar-medium .avatar-placeholder {
  font-size: 1.125rem;
}

.avatar-large .avatar-placeholder {
  font-size: 1.375rem;
}

/* Thinking indicator - elegant gold pulsing ring */
.thinking-ring {
  position: absolute;
  inset: -4px;
  border-radius: 50%;
  border: 2px solid var(--color-gold);
  animation: pulse-ring 1.5s ease-in-out infinite;
}

@keyframes pulse-ring {
  0% {
    opacity: 1;
    transform: scale(1);
    border-color: var(--color-gold);
  }
  50% {
    opacity: 0.6;
    transform: scale(1.1);
    border-color: var(--color-gold-light);
  }
  100% {
    opacity: 1;
    transform: scale(1);
    border-color: var(--color-gold);
  }
}
</style>
