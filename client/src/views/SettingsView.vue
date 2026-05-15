<script setup lang="ts">
import { useRouter } from 'vue-router'
import SettingsModal from '@/components/settings/SettingsModal.vue'

const router = useRouter()

function handleClose() {
  if (window.history.length > 1) {
    router.back()
  } else {
    router.push('/characters')
  }
}
</script>

<template>
  <div class="settings-page-shell">
    <!-- Background -->
    <div class="settings-background"></div>
    <!-- Settings Modal -->
    <SettingsModal :open="true" @close="handleClose" />
  </div>
</template>

<style scoped>
.settings-page-shell {
  position: fixed;
  inset: 0;
  z-index: 999;
}

.settings-background {
  position: absolute;
  inset: 0;
  background: var(--color-ivory, #faf7f2);
  opacity: 0.5;
}
</style>
