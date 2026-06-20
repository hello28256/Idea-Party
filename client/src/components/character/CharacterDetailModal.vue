<script setup lang="ts">
import type { Character } from '@/types'
import Avatar from '@/components/ui/Avatar.vue'

// 角色详情弹窗：只读展示角色信息（头像/简介/Prompt/预设标识），不提供编辑入口。
// 设计为受控组件：父组件通过 show 控制可见性，character 为 null 时不渲染，
// 避免父组件在弹窗打开期间清空数据时残留旧内容。
// 父组件：CharacterSidebar（侧栏「查看详情」图标）、CharacterListView（列表中点击角色）等。

// show：父组件控制显隐
// character：null 时不渲染（template 守卫），避免空数据
interface Props {
  show: boolean
  character: Character | null
}

defineProps<Props>()

// close：遮罩点击 / 关闭按钮 / 底部按钮三处共用，父组件收到后置 show=false
const emit = defineEmits<{
  close: []
}>()

// 统一封装关闭逻辑：点击遮罩、关闭按钮、底部"关闭"按钮三处共用，
// 让父组件只需监听一个事件即可回收弹窗状态。
function handleClose() {
  emit('close')
}
</script>

<template>
  <!-- Teleport 到 body：避免父容器的 overflow/transform/stacking context 影响 fixed 定位和层级。
       z-[100] 保持在应用层浮层之上，但低于全局 Toast（Toast 使用更高 z-index）。 -->
  <Teleport to="body">
    <Transition name="modal">
      <div
        v-if="show && character"
        class="fixed inset-0 z-[100] flex items-center justify-center p-4"
      >
        <!-- Backdrop：点击遮罩即关闭，符合用户对模态弹窗的预期。 -->
        <div
          class="absolute inset-0 bg-black/40 backdrop-blur-sm"
          @click="handleClose"
        ></div>

        <!-- Modal content -->
        <div
          class="relative w-full max-w-lg bg-gradient-to-b from-[var(--color-ivory)] to-[var(--color-cream)] rounded-2xl shadow-2xl overflow-hidden"
        >
          <!-- Header with avatar -->
          <div class="relative p-6 pb-0">
            <!-- Decorative top bar -->
            <div class="absolute top-0 left-0 right-0 h-24 bg-gradient-to-br from-[var(--color-navy)] via-[var(--color-navy-light)] to-[var(--color-gold-dark)] opacity-90"></div>

            <!-- Close button -->
            <button
              @click="handleClose"
              class="absolute top-4 right-4 z-10 p-2 rounded-full bg-white/20 hover:bg-white/30 transition-colors"
            >
              <svg class="w-5 h-5 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>

            <!-- Avatar and name -->
            <div class="relative flex flex-col items-center pt-4">
              <Avatar
                :src="character.avatarUrl"
                :name="character.name"
                size="large"
                class="w-20 h-20 border-4 border-white shadow-lg"
              />
              <h2 class="mt-4 text-xl font-semibold text-[var(--color-navy)] font-['Playfair_Display']">
                {{ character.name }}
              </h2>
              <!-- 同时兼容 isPreset 和 preset 两个字段：历史数据中两种命名都存在，
                   后续统一后可收敛为单一字段。 -->
              <span
                v-if="character.isPreset || character.preset"
                class="mt-2 px-3 py-1 text-xs font-medium rounded-full bg-[var(--color-gold)]/20 text-[var(--color-gold-dark)] border border-[var(--color-gold)]/30"
              >
                预设角色
              </span>
            </div>
          </div>

          <!-- Description -->
          <div v-if="character.description" class="px-6 pt-4">
            <h3 class="text-sm font-medium text-[var(--color-text-muted)] mb-1">简介</h3>
            <p class="text-sm text-[var(--color-navy)] leading-relaxed">
              {{ character.description }}
            </p>
          </div>

          <!-- Prompt 区块：whitespace-pre-wrap 保留 LLM prompt 中的换行与缩进。
             后端生成的 prompt 经常带结构化段落，原样展示才能体现格式。
             空值兜底文案，避免卡片出现空白段落影响阅读。 -->
          <div class="px-6 pt-4 pb-6">
            <h3 class="text-sm font-medium text-[var(--color-text-muted)] mb-2">角色设定 (Prompt)</h3>
            <div class="bg-white/60 rounded-xl p-4 border border-[var(--color-border)]">
              <p class="text-sm text-[var(--color-navy)] leading-relaxed whitespace-pre-wrap">
                {{ character.prompt || '暂无角色设定' }}
              </p>
            </div>
          </div>

          <!-- Footer -->
          <div class="px-6 pb-6 flex justify-center">
            <button
              @click="handleClose"
              class="px-6 py-2 bg-gradient-to-r from-[var(--color-navy)] to-[var(--color-navy-light)] text-[var(--color-gold)] rounded-lg font-medium text-sm hover:opacity-90 transition-opacity"
            >
              关闭
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
/* 模态过渡：遮罩淡入淡出 + 内容轻微缩放上移，营造"浮起"感而非生硬出现。
   > div:last-child 定位到内容卡片（遮罩是首个 div），让两者动画解耦。 */
.modal-enter-active,
.modal-leave-active {
  transition: all 0.3s ease;
}

.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}

.modal-enter-from > div:last-child,
.modal-leave-to > div:last-child {
  transform: scale(0.95) translateY(20px);
}

.modal-enter-active > div:last-child,
.modal-leave-active > div:last-child {
  transition: transform 0.3s ease;
}
</style>
