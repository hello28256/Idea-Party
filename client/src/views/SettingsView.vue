<script setup lang="ts">
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
.settings-page-shell {
  display: none;
}
</style>
