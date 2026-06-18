<script setup lang="ts">
// SettingsView 是一个"路由触发器"页面：/settings 路由的唯一作用是打开全局
// SettingsModal（由 App.vue 挂载），然后立即跳回上一页，避免覆盖用户当前的
// 浏览上下文。真正的设置 UI 不在页面里，而在浮层里。
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useSettingsStore } from '@/stores/settings'

const router = useRouter()
const settingsStore = useSettingsStore()

// When the /settings route is entered, open the floating modal and
// bounce back to the previous page so the user sees the modal over
// their original content (instead of a full-page route replacement).
onMounted(() => {
  settingsStore.openSettings()
  // replace 而不是 push：避免在历史栈里留下 /settings 占位，
  // 让浏览器后退键回到真正的上一页（如 my-rooms / chat）。
  router.replace('/characters')
})
</script>

<template>
  <div class="settings-page-shell">
    <!-- Page intentionally empty: the floating SettingsModal is
         rendered by App.vue and overlays whatever the user was on. -->
  </div>
</template>

<style scoped>
/* 路由组件必须存在以匹配 /settings，但页面本身不渲染任何内容
   （设置 UI 由 App.vue 的 SettingsModal 浮层承担），所以隐藏壳层。 */
.settings-page-shell {
  display: none;
}
</style>
