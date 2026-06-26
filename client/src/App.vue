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
    <!-- BUILD-MARKER-2026-06-26-A: 如果你看到页面上有这个红色 banner，说明这个 tab 加载的是新代码。
         看不到 = 浏览器在用旧 chunk，需要完全关闭 tab + 重新打开。 -->
    <div style="position:fixed;top:0;left:0;right:0;z-index:99999;background:#dc2626;color:#fff;padding:12px 20px;text-align:center;font-weight:700;font-size:15px;font-family:system-ui;box-shadow:0 4px 12px rgba(0,0,0,0.3);">
      🚨 新代码已加载 BUILD-2026-06-26-A — 角色卡应该没有「对话/编辑」按钮 — 如果还看到按钮 = 浏览器在用旧 chunk
    </div>
    <div style="height:48px;"></div>
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
