<script setup lang="ts">
const props = defineProps<{
  modelValue: string;
  placeholder?: string;
  type?: 'text' | 'email' | 'password';
}>();

const emit = defineEmits<{
  'update:modelValue': [value: string];
}>();

function handleInput(e: Event) {
  const target = e.target as HTMLInputElement;
  emit('update:modelValue', target.value);
}
</script>

<template>
  <input
    class="input"
    :type="type || 'text'"
    :value="modelValue"
    :placeholder="placeholder"
    @input="handleInput"
  />
</template>

<style scoped>
.input {
  width: 100%;
  padding: 0.75rem;
  border: 1px solid #ddd;
  border-radius: 8px;
  font-size: 1rem;
  transition: border-color 0.2s;
}

.input:focus {
  outline: none;
  border-color: #667eea;
}
</style>
