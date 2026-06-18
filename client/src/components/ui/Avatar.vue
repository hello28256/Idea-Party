<script setup lang="ts">
import { computed, ref, watch } from 'vue'

// 通用头像组件：支持图片源失败回退为首字母占位、思考中状态指示。
// 配合聊天消息列表使用：聊天室中每个 AI 角色都需要稳定可识别的头像展示。

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
  // 海军蓝渐变作为占位背景：与品牌主色调一致，避免在缺少头像时出现突兀的纯色块。
  gradient: 'linear-gradient(135deg, var(--color-navy) 0%, var(--color-navy-light) 100%)'
})

const sizeClasses = {
  small: 'avatar-small',
  medium: 'avatar-medium',
  large: 'avatar-large',
}

// 项目根静态资源 fallback：用户未上传头像时使用，保证 UI 不留空白。
const DEFAULT_AVATAR = '/image.png'

// 优先使用用户上传的头像，缺失时回退到项目默认图；保持 template 单条件渲染的简洁性。
const avatarSrc = computed(() => props.src || DEFAULT_AVATAR)
const imageError = ref(false)

// 标记图片加载失败：浏览器 <img> 触发 @error 时（如 404/网络断开），
// 必须切到占位首字母显示，否则会留下破图图标影响观感。
function handleImageError() {
  imageError.value = true
}

// 监听 src 变化时重置错误态：换源后要重新允许尝试加载，
// 否则一次失败后即使换图也会永久停留在占位态。
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

  .avatar {
    box-shadow: 0 1px 3px rgba(0, 0, 0, 0.3);
    border-color: rgba(255, 255, 255, 0.1);
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
