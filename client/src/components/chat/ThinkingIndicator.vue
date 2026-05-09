<script setup lang="ts">
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
  gap: 0.75rem;
  padding: 0.75rem 1.25rem;
  background: linear-gradient(145deg, var(--color-ivory), var(--color-parchment));
  border: 1px solid var(--color-border);
  border-radius: 2rem;
  animation: fadeInUp 0.4s ease-out;
}

.thinking-avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--color-navy), var(--color-navy-light));
  display: flex;
  align-items: center;
  justify-content: center;
  animation: pulse 2s ease-in-out infinite;
}

.dots {
  display: flex;
  gap: 4px;
  align-items: center;
}

.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background-color: var(--color-gold);
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
  font-size: 0.9rem;
  color: var(--color-text-secondary);
}

.character-name {
  font-family: 'Playfair Display', serif;
  font-weight: 600;
  color: var(--color-navy);
}

@keyframes fadeInUp {
  from {
    opacity: 0;
    transform: translateY(10px);
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
    box-shadow: 0 0 0 8px rgba(201, 169, 98, 0);
  }
}
</style>
