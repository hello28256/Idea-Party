<template>
  <div class="legal-page" :class="{ 'is-embed': isEmbed }">
    <!-- 法务页布局：条款页（/terms）与隐私页（/privacy）共用此 layout。
         设计要点：sticky 头栏 + 单列卡片正文 + 居中 footer，强调"长文阅读"而非"操作表单"，
         与登录/注册的双栏布局刻意区分开。 -->
    <!-- 顶栏两个入口都跳 /login：法务页对未登录用户开放，但所有交互最终仍需登录态，故品牌与"返回"共用一个出口。
         嵌入模式(?embed=1)隐藏顶栏,避免在设置弹窗里出现冗余的 logo 和"返回登录"按钮。 -->
    <header v-if="!isEmbed" class="legal-header">
      <RouterLink to="/login" class="brand">Idea Party</RouterLink>
      <RouterLink to="/login" class="back-button">返回登录</RouterLink>
    </header>

    <main class="legal-main">
      <section class="legal-card">
        <div class="legal-title-block">
          <p class="legal-kicker">LEGAL</p>
          <h1>{{ title }}</h1>
          <p class="legal-subtitle">{{ subtitle }}</p>
        </div>

        <article class="legal-content">
          <!-- 正文 slot：条款/隐私的 <section>/<h2>/<p> 等由各页面自行编写，
               样式由本 layout 通过 :deep() 统一约束，保证多份法务文档视觉一致。 -->
          <slot />
        </article>
      </section>
    </main>

    <footer class="legal-footer">
      © 2026 Idea Party. Built for imagination and conversation.
    </footer>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

// 法务类静态页（条款 / 隐私政策等）共用布局：统一头尾视觉与暗色主题，便于各法务页只关注正文内容。
// 之所以独立成 layout 而非复用 AuthLayout：法务页对未登录访客也必须可访问，且视觉走"长文阅读"卡片风，而非登录的双栏表单，故刻意拆开。
// title / subtitle 由各法务页传入：标题块与正文 slot 解耦，方便复用同一布局且每页可独立文案；不内嵌文案是为了避免每个页面重复相同的 <h1>/<p> 结构。
// embed: ?embed=1 时隐藏顶栏(logo + 返回登录),用于被设置弹窗 iframe 嵌入的场景;
//   独立访问 /privacy 或 /terms 时顶栏仍保留,保证法务页"长文阅读"的基本导航。
const route = useRoute()
const isEmbed = computed(() => route.query.embed === '1')

defineProps<{
  title: string
  subtitle: string
}>()
</script>

<style scoped>
.legal-page {
  min-height: 100vh;
  width: 100%;
  overflow-x: hidden;
  background: linear-gradient(180deg, #ffffff 0%, #f8fafc 100%);
  color: #18181b;
}

.legal-header {
  position: sticky;
  top: 0;
  z-index: 50;
  height: 80px;
  width: 100%;
  border-bottom: 1px solid rgba(24, 24, 27, 0.08);
  background: rgba(255, 255, 255, 0.78);
  backdrop-filter: blur(18px);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 32px;
}

.brand {
  color: #18181b;
  font-size: 28px;
  font-weight: 900;
  letter-spacing: -0.04em;
  text-decoration: none;
}

.back-button {
  border: 1px solid rgba(24, 24, 27, 0.12);
  background: white;
  color: #3f3f46;
  padding: 10px 18px;
  border-radius: 999px;
  font-size: 14px;
  font-weight: 600;
  text-decoration: none;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.06);
  transition: all 0.2s ease;
}

.back-button:hover {
  transform: translateY(-1px);
  background: #f8fafc;
}

.legal-main {
  width: 100%;
  max-width: 960px;
  margin: 0 auto;
  padding: 72px 24px 48px;
}

/* 嵌入模式:没有顶栏,顶部留白从 72px 减到 16px,iframe 内页更紧凑 */
.legal-page.is-embed .legal-main {
  padding-top: 16px;
}

.legal-card {
  width: 100%;
  border-radius: 36px;
  border: 1px solid rgba(24, 24, 27, 0.08);
  background: rgba(255, 255, 255, 0.86);
  backdrop-filter: blur(18px);
  box-shadow: 0 24px 80px rgba(15, 23, 42, 0.08);
  padding: 48px;
}

.legal-title-block {
  border-bottom: 1px solid rgba(24, 24, 27, 0.08);
  padding-bottom: 32px;
  margin-bottom: 40px;
}

.legal-kicker {
  margin: 0 0 16px;
  color: #0284c7;
  font-size: 13px;
  font-weight: 800;
  letter-spacing: 0.24em;
}

.legal-title-block h1 {
  margin: 0;
  color: #09090b;
  font-size: clamp(40px, 6vw, 64px);
  line-height: 1.05;
  font-weight: 900;
  letter-spacing: -0.06em;
}

.legal-subtitle {
  margin: 18px 0 0;
  color: #71717a;
  font-size: 17px;
  line-height: 1.8;
}

.legal-content {
  color: #3f3f46;
  font-size: 17px;
  line-height: 1.9;
}

/* 用 :deep() 穿透 slot 子内容：法务正文（h2 / p / ul 等）由各页面在本 layout 内自行编写，
   样式由本 layout 统一约束，保证多份法务文档排版一致。 */
.legal-content :deep(section) {
  margin-top: 40px;
}

.legal-content :deep(section:first-child) {
  margin-top: 0;
}

.legal-content :deep(h2) {
  margin: 0 0 16px;
  color: #18181b;
  font-size: 26px;
  line-height: 1.25;
  font-weight: 800;
  letter-spacing: -0.03em;
}

.legal-content :deep(p) {
  margin: 0 0 16px;
}

.legal-content :deep(ul) {
  margin: 12px 0 0;
  padding-left: 24px;
}

.legal-content :deep(li) {
  margin: 8px 0;
}

.legal-footer {
  max-width: 960px;
  margin: 0 auto;
  padding: 0 24px 48px;
  text-align: center;
  color: #a1a1aa;
  font-size: 14px;
}

/* 用 :global(.dark) 而非 scoped 类：暗色主题由根节点 <html> 上的 .dark 类触发，
   scoped 样式无法命中组件外的祖先节点，必须显式穿透才能跟随全局主题切换。 */
:global(.dark) .legal-page {
  background: linear-gradient(180deg, #0b0d12 0%, #111318 100%);
  color: white;
}

:global(.dark) .legal-header {
  border-bottom-color: rgba(255, 255, 255, 0.08);
  background: rgba(17, 19, 24, 0.82);
}

:global(.dark) .brand {
  color: white;
}

:global(.dark) .back-button {
  border-color: rgba(255, 255, 255, 0.1);
  background: rgba(255, 255, 255, 0.04);
  color: #e4e4e7;
}

:global(.dark) .back-button:hover {
  background: rgba(255, 255, 255, 0.08);
}

:global(.dark) .legal-card {
  border-color: rgba(255, 255, 255, 0.1);
  background: rgba(255, 255, 255, 0.045);
  box-shadow: 0 24px 80px rgba(0, 0, 0, 0.36);
}

:global(.dark) .legal-title-block {
  border-bottom-color: rgba(255, 255, 255, 0.1);
}

:global(.dark) .legal-kicker {
  color: #7dd3fc;
}

:global(.dark) .legal-title-block h1 {
  color: white;
}

:global(.dark) .legal-subtitle {
  color: #a1a1aa;
}

:global(.dark) .legal-content {
  color: #d4d4d8;
}

:global(.dark) .legal-content :deep(h2) {
  color: white;
}

@media (max-width: 768px) {
  .legal-header {
    height: 72px;
    padding: 0 20px;
  }

  .brand {
    font-size: 24px;
  }

  .legal-main {
    padding: 40px 16px 32px;
  }

  .legal-card {
    border-radius: 28px;
    padding: 32px 24px;
  }
}
</style>
