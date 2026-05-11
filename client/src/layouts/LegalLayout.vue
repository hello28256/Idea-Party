<template>
  <div class="legal-page">
    <header class="legal-header">
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
