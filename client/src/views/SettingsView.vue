<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useSettingsStore } from '@/stores/settings'
import Input from '@/components/ui/Input.vue'
import Button from '@/components/ui/Button.vue'

const router = useRouter()
const settingsStore = useSettingsStore()

const apiKey = ref(settingsStore.deepseekApiKey)
const saved = ref(false)
const error = ref<string | null>(null)

onMounted(async () => {
  await settingsStore.fetchApiKey()
  apiKey.value = settingsStore.deepseekApiKey
})

async function handleSave() {
  error.value = null
  try {
    await settingsStore.setApiKey(apiKey.value)
    saved.value = true
    setTimeout(() => {
      saved.value = false
    }, 2000)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '保存失败'
  }
}

async function handleClear() {
  if (confirm('确定要清除 API Key 吗？')) {
    try {
      await settingsStore.clearApiKey()
      apiKey.value = ''
    } catch (e) {
      error.value = e instanceof Error ? e.message : '清除失败'
    }
  }
}

function goBack() {
  router.back()
}
</script>

<template>
  <div class="min-h-screen">
    <!-- Header -->
    <header class="header">
      <div class="max-w-2xl mx-auto px-4 py-6">
        <div class="flex items-center gap-4">
          <button
            @click="goBack"
            class="p-2 rounded-lg hover:bg-[var(--color-parchment)] text-[var(--color-text-secondary)] transition-colors"
          >
            <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M10 19l-7-7m0 0l7-7m-7 7h18" />
            </svg>
          </button>
          <div>
            <h1 class="text-heading">设置</h1>
            <p class="text-sm text-[var(--color-text-secondary)]">配置你的 AI 服务</p>
          </div>
        </div>
      </div>
    </header>

    <!-- Content -->
    <main class="max-w-2xl mx-auto px-4 py-8">
      <!-- API Key Section -->
      <div class="card mb-6">
        <div class="flex items-center gap-3 mb-4">
          <div class="w-10 h-10 rounded-lg bg-gradient-to-br from-[var(--color-navy)] to-[var(--color-navy-light)] flex items-center justify-center">
            <svg class="w-5 h-5 text-[var(--color-gold)]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 7a2 2 0 012 2m4 0a6 6 0 01-7.743 5.743L11 17H9v2H7v2H4a1 1 0 01-1-1v-2.586a1 1 0 01.293-.707l5.964-5.964A6 6 0 1121 9z" />
            </svg>
          </div>
          <div>
            <h2 class="text-subheading text-[var(--color-navy)]">DeepSeek API Key</h2>
            <p class="text-sm text-[var(--color-text-secondary)]">用于 AI 角色回复的生成</p>
          </div>
        </div>

        <div class="space-y-4">
          <div class="relative">
            <Input
              v-model="apiKey"
              type="text"
              placeholder="请输入 DeepSeek API Key"
            />
            <button
              @click="settingsStore.toggleShowKey"
              class="absolute right-3 top-1/2 -translate-y-1/2 p-1 text-[var(--color-text-muted)] hover:text-[var(--color-text-secondary)]"
            >
              <svg v-if="!settingsStore.showApiKey" class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" />
              </svg>
              <svg v-else class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M9.88 9.88l-3.29-3.29m7.532 7.532l3.29 3.29M3 3l3.59 3.59m0 0A9.953 9.953 0 0112 5c4.478 0 8.268 2.943 9.543 7a10.025 10.025 0 01-4.132 5.411m0 0L21 21" />
              </svg>
            </button>
          </div>

          <div class="flex items-center gap-3 flex-wrap">
            <Button variant="primary" @click="handleSave" :loading="settingsStore.loading">
              <svg class="w-4 h-4 mr-1.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
              </svg>
              保存
            </Button>
            <Button variant="secondary" @click="handleClear" :disabled="!settingsStore.hasApiKey || settingsStore.loading">
              清除
            </Button>
            <Transition name="fade">
              <span v-if="saved" class="text-sm text-[var(--color-success)] flex items-center gap-1.5">
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
                </svg>
                已保存
              </span>
            </Transition>
            <Transition name="fade">
              <span v-if="error" class="text-sm text-[var(--color-destructive)]">
                {{ error }}
              </span>
            </Transition>
          </div>
        </div>

        <!-- Status indicator -->
        <div class="mt-4 pt-4 border-t border-[var(--color-border)]">
          <div class="flex items-center gap-2">
            <div
              class="w-2 h-2 rounded-full"
              :class="settingsStore.hasApiKey ? 'bg-[var(--color-success)]' : 'bg-[var(--color-text-muted)]'"
            ></div>
            <span class="text-sm text-[var(--color-text-secondary)]">
              {{ settingsStore.hasApiKey ? 'API Key 已配置' : 'API Key 未配置' }}
            </span>
          </div>
        </div>
      </div>

      <!-- Info Card -->
      <div class="card">
        <h3 class="text-subheading text-[var(--color-navy)] mb-3">如何获取 API Key？</h3>
        <ol class="text-sm text-[var(--color-text-secondary)] space-y-2 list-decimal list-inside">
          <li>访问 <a href="https://platform.deepseek.com" target="_blank" class="text-[var(--color-gold)] hover:underline">DeepSeek 开放平台</a></li>
          <li>注册账号并完成认证</li>
          <li>在 API Keys 页面创建一个新的 Key</li>
          <li>复制并粘贴到上方输入框</li>
        </ol>
      </div>
    </main>
  </div>
</template>

<style scoped>
.header {
  background: linear-gradient(180deg, var(--color-ivory) 0%, var(--color-cream) 100%);
  border-bottom: 1px solid var(--color-border);
}
</style>
