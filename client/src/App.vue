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
  height: 100vh;
  height: 100dvh;
  overflow: hidden;
  background: var(--color-bg);
  transition: var(--transition-theme);
}

html, body {
  height: 100%;
  overflow: hidden;
  margin: 0;
  padding: 0;
}
</style>
