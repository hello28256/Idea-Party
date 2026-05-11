<script setup lang="ts">
import { computed, ref, watch } from 'vue'

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

const DEFAULT_AVATAR = '/default_touxiang.svg'

const avatarSrc = computed(() => props.src || DEFAULT_AVATAR)
const imageError = ref(false)

function handleImageError() {
  imageError.value = true
}

watch(() => props.src, () => {
  imageError.value = false
})
</script>

<template>
  <div class="avatar-wrapper" :class="{ 'is-thinking': isThinking }">
    <div class="avatar" :class="sizeClasses[size || 'medium']">
      <img
        v-if="avatarSrc && !imageError"
        :src="avatarSrc"
        :alt="name"
        @error="handleImageError"
      />
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
  box-shadow:
    0 2px 6px rgba(44, 36, 22, 0.1),
    0 1px 2px rgba(44, 36, 22, 0.06);
  transition: transform 0.35s cubic-bezier(0.4, 0, 0.2, 1), box-shadow 0.35s ease;
  border: 2px solid var(--color-cream);
}

.avatar:hover {
  transform: scale(1.08);
  box-shadow:
    0 4px 12px rgba(44, 36, 22, 0.15),
    0 2px 4px rgba(44, 36, 22, 0.1);
}

.avatar-small {
  width: 34px;
  height: 34px;
}

.avatar-medium {
  width: 46px;
  height: 46px;
}

.avatar-large {
  width: 56px;
  height: 56px;
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
  text-shadow: 0 1px 3px rgba(0, 0, 0, 0.25);
  letter-spacing: 0.02em;
}

@media (prefers-color-scheme: dark) {
  .avatar-placeholder {
    background: linear-gradient(135deg, #1a1a2e 0%, #2d2d44 100%);
    color: #f0d78c;
  }
}

.avatar-small .avatar-placeholder {
  font-size: 0.8rem;
}

.avatar-medium .avatar-placeholder {
  font-size: 1.1rem;
}

.avatar-large .avatar-placeholder {
  font-size: 1.4rem;
}

/* Thinking indicator - elegant gold pulsing ring */
.thinking-ring {
  position: absolute;
  inset: -5px;
  border-radius: 50%;
  border: 2px solid transparent;
  background: linear-gradient(var(--color-cream), var(--color-cream)) padding-box,
              linear-gradient(135deg, var(--color-gold) 0%, var(--color-gold-light) 50%, var(--color-gold) 100%) border-box;
  animation: pulse-ring 1.6s ease-in-out infinite;
}

@keyframes pulse-ring {
  0% {
    opacity: 1;
    transform: scale(1);
  }
  50% {
    opacity: 0.5;
    transform: scale(1.12);
  }
  100% {
    opacity: 1;
    transform: scale(1);
  }
}
</style>
