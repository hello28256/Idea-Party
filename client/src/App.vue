<script setup lang="ts">
// 应用根组件：承担三件事 ——
//   1) 挂载路由出口 <router-view />，让所有页面级视图在容器内渲染；
//   2) 全局常驻 Toast 提示，跨任何路由都需可见（如 401 强制下线、网络异常等）；
//   3) 全局设置弹窗 SettingsModal，用户在任意页面都能唤出修改偏好。
// 不把这些浮层放进 router-view 是为了避免被路由切换销毁：销毁后正在展示的 toast 会瞬间消失，UX 抖动。
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
