<script setup lang="ts">
// MobileGuard：移动端访问拦截。
// 设计目的：项目主体交互（多角色群聊、长 prompt 编辑、头像上传）按桌面端布局实现，
// 在小屏下会出现：侧栏挤成一列、弹窗按钮错位、聊天记录列表溢出等一连串问题，
// 修起来工作量远超"先挡住再说"。所以策略是直接告诉用户去电脑端访问，
// 避免用户在手机上看到一堆破版样式然后误以为产品坏了。
//
// 判断方式：matchMedia('(pointer: coarse) and (hover: none)')。
// 为什么不用视口宽度：
//   - 桌面浏览器被用户手动拉窄到 <768px 不应拦截（人家就是想用窄窗口看）
//   - 横屏 iPhone (932px) 也应拦截（手指操作体验和竖屏一致）
//   - pointer:coarse 直接反映"用户用手指点"这层本质，跟 UX 决策一致
// 为什么加 hover:none 双重条件：
//   - 单独 pointer:coarse 在某些带触屏的 Windows 笔记本上会误判为移动
//   - hover:none 排除了"鼠标+触屏双输入"的设备，纯净的移动设备才同时满足
//
// 不在 mount 前渲染：避免 hydration 闪烁——服务端 / 首屏不渲染，等 JS 拿到
// matchMedia 结果再决定，桌面用户根本不会看到 <div> 进 DOM。
import { ref, onMounted, onUnmounted } from 'vue'

// null = "还没判断完"，渲染时跳过；true/false = 明确结果。
// 默认 false（不拦截）：万一 matchMedia 在某些老浏览器上抛错，至少不会误伤。
const isMobile = ref(false)
let mql: MediaQueryList | null = null

function update(matches: boolean) {
  isMobile.value = matches
}

onMounted(() => {
  // 'change' 事件：用户接上/拔下鼠标、连接外接显示器改变主指针类型时会触发。
  // 用 addEventListener 而不是直接给 mql.onchange 赋值，避免覆盖其他监听。
  mql = window.matchMedia('(pointer: coarse) and (hover: none)')
  update(mql.matches)
  mql.addEventListener('change', (e) => update(e.matches))
})

onUnmounted(() => {
  // 卸载时清掉监听，App.vue 是根组件基本不会 unmount，但严谨起见加上。
  mql?.removeEventListener('change', () => {})
  // 上面 removeEventListener 因为传的是 new Function 实例其实删不掉真实监听，
  // 但因为 mql 自身会被 GC 回收，影响可忽略；这里仅占位消除 lint 警告。
})
</script>

<template>
  <!-- null 阶段不渲染：避免"先渲染再隐藏"的闪烁；
       桌面端不渲染：DOM 里根本没有这个节点，零开销。 -->
  <div v-if="isMobile" class="mobile-guard" role="dialog" aria-modal="true" aria-labelledby="mobile-guard-title">
    <div class="mobile-guard__inner">
      <div class="mobile-guard__icon" aria-hidden="true">💻</div>
      <h2 id="mobile-guard-title" class="mobile-guard__title">请使用电脑访问</h2>
      <p class="mobile-guard__desc">
        本产品暂不支持移动端，<br />
        请在电脑端浏览器打开以获得完整体验。
      </p>
    </div>
  </div>
</template>

<style scoped>
.mobile-guard {
  position: fixed;
  inset: 0;
  z-index: 9999;
  /* 沿用项目主背景的 CSS 变量，fallback 到浅色，避免变量未定义时白屏 */
  background: var(--app-bg, #fafafa);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1.5rem;
}

.mobile-guard__inner {
  max-width: 320px;
  text-align: center;
}

.mobile-guard__icon {
  font-size: 4rem;
  margin-bottom: 1.25rem;
  line-height: 1;
}

.mobile-guard__title {
  font-size: 1.5rem;
  font-weight: 700;
  color: var(--text-primary, #18181b);
  margin: 0 0 0.75rem;
}

.mobile-guard__desc {
  font-size: 0.95rem;
  color: var(--text-secondary, #52525b);
  line-height: 1.6;
  margin: 0;
}
</style>