<script setup lang="ts">
// 应用根组件：承载路由出口 + 全局 UI 浮层（SettingsModal / Toast）。
// 不在 router-view 内挂载是因为这些组件需跨路由常驻，避免随页面切换被销毁。
import SettingsModal from '@/components/settings/SettingsModal.vue'
import Toast from '@/components/ui/Toast.vue'
</script>

<template>
  <div class="app-container">
    <router-view />
    <SettingsModal />
    <Toast />
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
