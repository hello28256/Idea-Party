<script setup lang="ts">
interface Props {
  type?: 'button' | 'submit' | 'reset'
  variant?: 'primary' | 'secondary' | 'destructive'
  disabled?: boolean
  loading?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  type: 'button',
  variant: 'primary',
  disabled: false,
  loading: false
})

const emit = defineEmits<{
  click: [event: MouseEvent]
}>()

function handleClick(event: MouseEvent) {
  if (!props.disabled && !props.loading) {
    emit('click', event)
  }
}
</script>

<template>
  <button
    :type="type"
    :disabled="disabled || loading"
    :class="[
      'inline-flex items-center justify-center gap-2 px-4 py-2 rounded-md font-medium transition-colors duration-150',
      'focus:outline-none focus:ring-2 focus:ring-offset-2',
      {
        'bg-accent text-white hover:bg-accent-hover focus:ring-accent': variant === 'primary',
        'bg-secondary-bg text-text-primary border border-border hover:bg-gray-100 focus:ring-gray-400': variant === 'secondary',
        'bg-destructive text-white hover:bg-red-600 focus:ring-destructive': variant === 'destructive',
        'opacity-50 cursor-not-allowed': disabled || loading
      }
    ]"
    @click="handleClick"
  >
    <svg
      v-if="loading"
      class="animate-spin h-4 w-4"
      xmlns="http://www.w3.org/2000/svg"
      fill="none"
      viewBox="0 0 24 24"
    >
      <circle
        class="opacity-25"
        cx="12"
        cy="12"
        r="10"
        stroke="currentColor"
        stroke-width="4"
      />
      <path
        class="opacity-75"
        fill="currentColor"
        d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
      />
    </svg>
    <slot />
  </button>
</template>
