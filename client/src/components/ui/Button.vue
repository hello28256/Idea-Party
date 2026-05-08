<script setup lang="ts">
defineProps<{
  variant?: 'primary' | 'secondary';
  disabled?: boolean;
}>();
</script>

<template>
  <button
    class="btn"
    :class="[variant || 'primary', { disabled }]"
    :disabled="disabled"
  >
    <slot />
  </button>
</template>

<style scoped>
.btn {
  padding: 0.75rem 1.5rem;
  border: none;
  border-radius: 8px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.primary {
  background: #667eea;
  color: white;
}

.primary:hover:not(:disabled) {
  background: #5568d3;
}

.secondary {
  background: #e0e0e0;
  color: #333;
}

.secondary:hover:not(:disabled) {
  background: #d0d0d0;
}

.disabled,
.btn:disabled {
  background: #ccc;
  cursor: not-allowed;
}
</style>
