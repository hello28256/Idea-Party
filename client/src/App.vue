<script setup lang="ts">
// App root component - renders router view
</script>

<template>
  <div class="app-container">
    <!-- Decorative background pattern -->
    <div class="bg-pattern"></div>
    <div class="grain-overlay"></div>
    <router-view />
  </div>
</template>

<style>
.app-container {
  position: relative;
  min-height: 100vh;
}

.bg-pattern {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  pointer-events: none;
  background:
    radial-gradient(ellipse at 20% 0%, rgba(201, 169, 98, 0.08) 0%, transparent 50%),
    radial-gradient(ellipse at 80% 100%, rgba(30, 42, 58, 0.05) 0%, transparent 50%),
    linear-gradient(180deg, var(--color-cream) 0%, var(--color-ivory) 100%);
}

/* Art deco geometric pattern overlay */
.bg-pattern::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  opacity: 0.03;
  background-image: url("data:image/svg+xml,%3Csvg width='60' height='60' viewBox='0 0 60 60' xmlns='http://www.w3.org/2000/svg'%3E%3Cg fill='none' fill-rule='evenodd'%3E%3Cg fill='%231E2A3A' fill-opacity='1'%3E%3Cpath d='M36 34v-4h-2v4h-4v2h4v4h2v-4h4v-2h-4zm0-30V0h-2v4h-4v2h4v4h2V6h4V4h-4zM6 34v-4H4v4H0v2h4v4h2v-4h4v-2H6zM6 4V0H4v4H0v2h4v4h2V6h4V4H6z'/%3E%3C/g%3E%3C/g%3E%3C/svg%3E");
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
