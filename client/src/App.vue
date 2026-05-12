<script setup lang="ts">
// App root component - renders router view
</script>

<template>
  <div class="app-container">
    <router-view />
  </div>
</template>

<style>
.app-container {
  position: relative;
  min-height: 100vh;
  background: var(--color-bg);
  transition: var(--transition-theme);
}

html {
  overflow-x: hidden;
}

body {
  margin: 0;
  padding: 0;
  overflow-x: hidden;
  /* Safe area insets for notched devices */
  padding-top: env(safe-area-inset-top);
  padding-bottom: env(safe-area-inset-bottom);
  padding-left: env(safe-area-inset-left);
  padding-right: env(safe-area-inset-right);
}

html {
  overflow-x: hidden;
}

body {
  margin: 0;
  padding: 0;
  overflow-x: hidden;
  /* Safe area insets for notched devices */
  padding-top: env(safe-area-inset-top);
  padding-bottom: env(safe-area-inset-bottom);
  padding-left: env(safe-area-inset-left);
  padding-right: env(safe-area-inset-right);
}
</style>
