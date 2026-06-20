<script setup lang="ts">
// SettingsView：路由 /settings
// SettingsView 是一个"路由触发器"页面：/settings 路由的唯一作用是打开全局
// SettingsModal（由 App.vue 挂载），然后立即跳回上一页，避免覆盖用户当前的
// 浏览上下文。真正的设置 UI 不在页面里，而在浮层里。
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useSettingsStore } from '@/stores/settings'

const router = useRouter()
const settingsStore = useSettingsStore()

// 当进入 /settings 路由时，打开浮层弹窗并回退到上一页，
// 让用户看到弹窗覆盖在原本内容之上（而不是用路由替换整个页面）。
onMounted(() => {
  settingsStore.openSettings()
  // replace 而不是 push：避免在历史栈里留下 /settings 占位，
  // 让浏览器后退键回到真正的上一页（如 my-rooms / chat）。
  router.replace('/characters')
})
</script>

<template>
  <div class="settings-page-shell">
    <!-- 页面刻意留空：浮层 SettingsModal 由 App.vue 渲染，
         覆盖在用户原本浏览的页面之上。 -->
  </div>
</template>

<style scoped>
/* 路由组件必须存在以匹配 /settings，但页面本身不渲染任何内容
   （设置 UI 由 App.vue 的 SettingsModal 浮层承担），所以隐藏壳层。 */
.settings-page-shell {
  display: none;
}
</style>
