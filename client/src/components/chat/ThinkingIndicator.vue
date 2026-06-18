<script setup lang="ts">
// AI 角色发言前的"正在思考"占位指示器。
// 由父组件在等待流式首 token 返回的间隙挂载，避免用户感知到"无响应"——
// 多角色群聊场景下 LLM 推理常有数秒延迟，纯空白会让人误以为崩溃。
// 仅依赖 characterName 一个 prop：刻意保持无状态，由调用方控制显隐（v-if），
// 这样不同角色轮次可独立挂载/卸载，也便于配合 Moderator 的发言编排节奏复用。
defineProps<{
  characterName: string
}>()
</script>

<template>
  <div class="thinking-indicator">
    <div class="thinking-avatar">
      <svg class="w-5 h-5 text-[var(--color-gold)]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 117.072 0l-.548.547A3.374 3.374 0 0014 18.469V19a2 2 0 11-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z" />
      </svg>
    </div>
    <div class="dots">
      <span class="dot"></span>
      <span class="dot"></span>
      <span class="dot"></span>
    </div>
    <span class="thinking-text">
      <span class="character-name">{{ characterName }}</span>
      正在沉思...
    </span>
  </div>
</template>

<style scoped>
.thinking-indicator {
  display: inline-flex;
  align-items: center;
  gap: 0.875rem;
  padding: 0.875rem 1.5rem;
  background: linear-gradient(145deg, var(--color-ivory) 0%, var(--color-parchment) 100%);
  border: 1px solid rgba(224, 214, 200, 0.6);
  border-radius: 2rem;
  animation: fadeInUp 0.4s ease-out;
  box-shadow:
    0 1px 3px rgba(44, 36, 22, 0.04),
    0 4px 16px rgba(201, 169, 98, 0.1);
}

.thinking-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: linear-gradient(145deg, var(--color-navy) 0%, var(--color-navy-light) 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  animation: pulse 2s ease-in-out infinite;
  box-shadow: 0 2px 8px rgba(30, 42, 58, 0.2);
}

.dots {
  display: flex;
  gap: 5px;
  align-items: center;
}

.dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--color-gold) 0%, var(--color-gold-light) 100%);
  box-shadow: 0 0 6px rgba(201, 169, 98, 0.4);
}

.dot:nth-child(1) {
  animation: sequential-pulse 1.4s ease-in-out infinite 0s;
}

.dot:nth-child(2) {
  animation: sequential-pulse 1.4s ease-in-out infinite 0.2s;
  width: 6px;
  height: 6px;
}

.dot:nth-child(3) {
  animation: sequential-pulse 1.4s ease-in-out infinite 0.4s;
}

@keyframes sequential-pulse {
  0%, 60%, 100% {
    opacity: 0.25;
    transform: scale(0.85);
  }
  30% {
    opacity: 1;
    transform: scale(1);
  }
}

.thinking-text {
  font-size: 0.875rem;
  color: var(--color-text-secondary);
  letter-spacing: 0.01em;
}

.character-name {
  font-family: 'Playfair Display', serif;
  font-weight: 600;
  color: var(--color-navy);
  margin-right: 0.25rem;
}

@keyframes fadeInUp {
  from {
    opacity: 0;
    transform: translateY(12px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@keyframes pulse {
  0%, 100% {
    box-shadow: 0 0 0 0 rgba(201, 169, 98, 0.4);
  }
  50% {
    box-shadow: 0 0 0 10px rgba(201, 169, 98, 0);
  }
}
</style>
