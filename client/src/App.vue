<script setup lang="ts">
// App root component - renders router view
import SettingsModal from '@/components/settings/SettingsModal.vue'
</script>

<template>
  <div class="app-container">
    <router-view />
    <SettingsModal />
  </div>
</template>

<style>
.app-container {
  position: relative;
  min-height: 100vh;
  background: var(--color-bg);
  transition: var(--transition-theme);
  display: flex;
  flex-direction: column;
}

router-view {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}

html, body {
  margin: 0;
  padding: 0;
}
</style>
