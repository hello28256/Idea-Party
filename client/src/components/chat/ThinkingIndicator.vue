<script setup lang="ts">
defineProps<{
  characterName: string
}>()
</script>

<template>
  <div class="thinking-indicator">
    <div class="dots">
      <span class="dot"></span>
      <span class="dot"></span>
      <span class="dot"></span>
    </div>
    <span class="thinking-text">{{ characterName }} 正在思考...</span>
  </div>
</template>

<style scoped>
.thinking-indicator {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 1rem;
  color: #6B7280;
  font-size: 0.875rem;
}

.dots {
  display: flex;
  gap: 4px;
  align-items: center;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background-color: #10B981;
}

.dot:nth-child(1) {
  animation: sequential-pulse 1.4s ease-in-out infinite 0s;
}

.dot:nth-child(2) {
  animation: sequential-pulse 1.4s ease-in-out infinite 0.2s;
}

.dot:nth-child(3) {
  animation: sequential-pulse 1.4s ease-in-out infinite 0.4s;
}

@keyframes sequential-pulse {
  0%, 60%, 100% {
    opacity: 0.3;
    transform: scale(0.8);
  }
  30% {
    opacity: 1;
    transform: scale(1);
  }
}

.thinking-text {
  color: #6B7280;
}
</style>
