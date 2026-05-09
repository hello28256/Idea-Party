<script setup lang="ts">
import { computed } from 'vue'

interface Props {
  modelValue: string
  type?: 'text' | 'email' | 'password' | 'number' | 'tel' | 'url'
  placeholder?: string
  disabled?: boolean
  error?: string
  label?: string
  id?: string
}

const props = withDefaults(defineProps<Props>(), {
  type: 'text',
  placeholder: '',
  disabled: false
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const inputId = computed(() => props.id || `input-${Math.random().toString(36).slice(2, 9)}`)

function handleInput(event: Event) {
  const target = event.target as HTMLInputElement
  emit('update:modelValue', target.value)
}
</script>

<template>
  <div class="flex flex-col gap-1">
    <label
      v-if="label"
      :for="inputId"
      class="text-label text-text-secondary"
    >
      {{ label }}
    </label>
    <input
      :id="inputId"
      :type="type"
      :value="modelValue"
      :placeholder="placeholder"
      :disabled="disabled"
      :class="[
        'input',
        {
          'border-destructive focus:border-destructive focus:ring-red-100': error,
          'opacity-50 cursor-not-allowed': disabled
        }
      ]"
      @input="handleInput"
    />
    <span
      v-if="error"
      class="text-sm text-destructive"
    >
      {{ error }}
    </span>
  </div>
</template>
