<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { BRAND_LOGO } from '@/constants/brand'

// 通用头像组件：始终渲染 <img>,src 直接绑定,失败时切到 OSS 占位图 (BRAND_LOGO)。
// 之前的 v-if/v-else 设计会在 <img> onerror 时切到首字占位 — 但首字占位与"图片加载中"的视觉
// 一致性差,尤其是页面快速滚动时多张头像连续切换,首字闪一下很碍眼。
// 新设计:src 始终是有效 URL (props.src || BRAND_LOGO),@error 时 fallback 到 BRAND_LOGO。
// 浏览器永远显示一张图,不会有"破图"或"首字"。

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

// 当前显示的 src:有 props.src 时用它,加载失败后切到 BRAND_LOGO 占位。
const currentSrc = ref<string>(props.src || BRAND_LOGO)

// 监听 props.src 变化,重置 currentSrc 让浏览器重新加载。
watch(() => props.src, (newSrc) => {
  currentSrc.value = newSrc || BRAND_LOGO
}, { immediate: true })

function handleImageError() {
  // 加载失败时切到 BRAND_LOGO(OSS 完整 URL,确保可达)。
  // 避免 fallback 到首字 — 用户反馈首字占位与图片切换有"闪一下"的视觉问题。
  if (currentSrc.value !== BRAND_LOGO) {
    currentSrc.value = BRAND_LOGO
  }
}
</script>

<template>
  <div class="avatar-wrapper" :class="{ 'is-thinking': isThinking }">
    <div class="avatar" :class="sizeClasses[size || 'medium']">
      <img
        :src="currentSrc"
        :alt="name"
        @error="handleImageError"
      />
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
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
  transition: transform 0.2s ease;
  border: 1px solid rgba(0, 0, 0, 0.06);
}

.avatar:hover {
  transform: scale(1.05);
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.12);
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

@media (prefers-color-scheme: dark) {
  .avatar {
    box-shadow: 0 1px 3px rgba(0, 0, 0, 0.3);
    border-color: rgba(255, 255, 255, 0.1);
  }
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