<script setup lang="ts">
function goBack() {
  window.history.back()
}
</script>

<template>
  <div class="min-h-screen w-full overflow-x-hidden bg-[#f7f7f8] text-zinc-950 transition-colors dark:bg-[#0b0d12] dark:text-white">
    <!-- Sticky Header -->
    <header class="sticky top-0 z-50 w-full border-b border-zinc-200/70 bg-white/80 backdrop-blur-xl dark:border-white/10 dark:bg-[#111315]/90">
      <div class="mx-auto flex h-20 w-full max-w-6xl items-center justify-between px-6">
        <router-link
          to="/login"
          class="text-2xl font-black tracking-[-0.03em] text-zinc-950 transition-colors dark:text-white"
        >
          Idea Party
        </router-link>

        <button
          @click="goBack"
          class="rounded-full border border-zinc-200 bg-white px-5 py-2 text-sm font-medium text-zinc-700 shadow-sm transition hover:bg-zinc-50 dark:border-white/10 dark:bg-white/[0.04] dark:text-zinc-200 dark:hover:bg-white/[0.08]"
        >
          返回登录
        </button>
      </div>
    </header>

    <!-- Main Content -->
    <main class="mx-auto w-full max-w-4xl px-6 py-14 md:px-8 md:py-20">
      <!-- Content Card -->
      <section class="w-full rounded-[32px] border border-black/5 bg-white/85 p-8 shadow-[0_20px_80px_rgba(15,23,42,0.08)] backdrop-blur-xl dark:border-white/10 dark:bg-white/[0.04] dark:shadow-[0_20px_80px_rgba(0,0,0,0.35)] md:p-12">
        <!-- Title Area -->
        <div class="border-b border-zinc-200 pb-8 dark:border-white/10">
          <p class="mb-4 text-sm font-semibold uppercase tracking-[0.24em] text-sky-600 dark:text-sky-300">
            Legal
          </p>
          <h1 class="max-w-3xl text-4xl font-black tracking-[-0.04em] text-zinc-950 dark:text-white md:text-5xl">
            Idea Party 隐私政策
          </h1>
          <p class="mt-4 text-base text-zinc-500 dark:text-zinc-400">
            我们如何保护你的数据
          </p>
        </div>

        <!-- Content -->
        <article class="mt-10 space-y-10 text-[17px] leading-8 text-zinc-700 dark:text-zinc-300">
          <section class="space-y-4">
            <p>
              Idea Party 重视你的隐私与数据安全。
            </p>
            <p>
              本隐私政策说明我们会收集哪些信息、如何使用这些信息，以及你对自己数据拥有的选择权。使用 Idea Party 即表示你理解并同意本政策中描述的做法。
            </p>
          </section>

          <section class="space-y-4">
            <h2 class="text-2xl font-bold tracking-tight text-zinc-950 dark:text-white">
              1. 我们收集的信息
            </h2>
            <p>为了提供服务，我们可能会收集以下信息：</p>
            <p class="font-medium text-zinc-900 dark:text-white">账户信息：</p>
            <p>包括用户名、邮箱地址、密码加密信息以及账户创建时间。</p>
            <p class="font-medium text-zinc-900 dark:text-white">使用信息：</p>
            <p>包括你访问的页面、创建的聊天室、使用的功能、登录状态、设备类型和基础日志信息。</p>
            <p class="font-medium text-zinc-900 dark:text-white">用户输入内容：</p>
            <p>包括你发送给 AI 角色的消息、创建的角色设定、聊天室标题、场景描述和其他你主动输入的内容。</p>
            <p class="font-medium text-zinc-900 dark:text-white">技术信息：</p>
            <p>包括浏览器类型、设备信息、IP 地址、错误日志、性能数据和 Cookie 或本地存储信息。</p>
          </section>

          <section class="space-y-4">
            <h2 class="text-2xl font-bold tracking-tight text-zinc-950 dark:text-white">
              2. 我们如何使用信息
            </h2>
            <p>我们使用收集的信息来：</p>
            <ul class="list-disc space-y-2 pl-6 marker:text-zinc-400">
              <li>创建和管理你的账户</li>
              <li>提供 AI 角色对话和群聊功能</li>
              <li>保存你的聊天室和对话记录</li>
              <li>改善产品体验和界面设计</li>
              <li>优化 AI 回复质量和系统稳定性</li>
              <li>进行安全检测、防止滥用和欺诈行为</li>
              <li>处理用户反馈和技术问题</li>
              <li>遵守适用法律法规要求</li>
            </ul>
          </section>

          <section class="space-y-4">
            <h2 class="text-2xl font-bold tracking-tight text-zinc-950 dark:text-white">
              3. AI 对话内容说明
            </h2>
            <p>
              你与 AI 角色的对话可能会被系统处理，以生成回复、保持上下文、改进体验和维护平台安全。
            </p>
            <p>
              我们不会故意公开你的私人对话内容。除非你主动分享、发布或法律要求，否则你的个人对话不会作为公开内容展示给其他用户。
            </p>
            <p class="font-medium text-zinc-900 dark:text-white">
              请不要在对话中输入高度敏感信息，例如身份证号、银行卡号、密码、精确住址、医疗隐私或其他不希望被处理的信息。
            </p>
          </section>

          <section class="space-y-4">
            <h2 class="text-2xl font-bold tracking-tight text-zinc-950 dark:text-white">
              4. 数据存储与安全
            </h2>
            <p>
              我们会采取合理的技术和管理措施保护你的信息，包括访问控制、加密存储、日志监控和权限限制。
            </p>
            <p>
              但你也理解，任何互联网服务都无法保证绝对安全。你应妥善保管自己的账户和密码，并避免在公共设备上保持登录状态。
            </p>
          </section>

          <section class="space-y-4">
            <h2 class="text-2xl font-bold tracking-tight text-zinc-950 dark:text-white">
              5. 我们是否出售数据
            </h2>
            <p>
              Idea Party 不会出售你的个人信息。
            </p>
            <p>
              我们不会将你的邮箱、账户信息或私人对话出售给广告商或第三方数据交易机构。
            </p>
          </section>

          <section class="space-y-4">
            <h2 class="text-2xl font-bold tracking-tight text-zinc-950 dark:text-white">
              6. 第三方服务
            </h2>
            <p>
              为了提供产品功能，我们可能会使用第三方基础服务，例如数据库、云存储、身份验证、模型接口、日志分析或部署平台。
            </p>
            <p>
              这些第三方服务仅在提供必要功能的范围内处理数据，并受到其各自隐私政策约束。
            </p>
          </section>

          <section class="space-y-4">
            <h2 class="text-2xl font-bold tracking-tight text-zinc-950 dark:text-white">
              7. Cookie 与本地存储
            </h2>
            <p>
              我们可能使用 Cookie 或浏览器本地存储来保存登录状态、主题偏好、语言偏好和基础使用设置。
            </p>
            <p>
              你可以通过浏览器设置清除这些数据，但清除后部分功能可能需要重新登录或重新设置。
            </p>
          </section>

          <section class="space-y-4">
            <h2 class="text-2xl font-bold tracking-tight text-zinc-950 dark:text-white">
              8. 你的选择权
            </h2>
            <p>你可以：</p>
            <ul class="list-disc space-y-2 pl-6 marker:text-zinc-400">
              <li>查看和修改账户信息</li>
              <li>删除部分对话或聊天室</li>
              <li>退出登录</li>
              <li>请求删除账户</li>
              <li>请求了解我们如何处理你的数据</li>
            </ul>
            <p>
              如果未来产品提供更多隐私设置，我们会在界面中提供相应入口。
            </p>
          </section>

          <section class="space-y-4">
            <h2 class="text-2xl font-bold tracking-tight text-zinc-950 dark:text-white">
              9. 未成年人使用
            </h2>
            <p>
              Idea Party 面向具备相应理解能力的用户使用。如果你是未成年人，请在监护人同意和指导下使用本服务。
            </p>
            <p>
              我们不鼓励未成年人在平台中输入敏感个人信息。
            </p>
          </section>

          <section class="space-y-4">
            <h2 class="text-2xl font-bold tracking-tight text-zinc-950 dark:text-white">
              10. 数据保留
            </h2>
            <p>
              我们会在提供服务所需期间保留相关数据。
            </p>
            <p>
              当你删除账户或请求删除数据时，我们会在合理时间内处理相关请求。但出于安全、备份、法律合规或争议处理需要，部分信息可能会在有限时间内保留。
            </p>
          </section>

          <section class="space-y-4">
            <h2 class="text-2xl font-bold tracking-tight text-zinc-950 dark:text-white">
              11. 政策更新
            </h2>
            <p>
              我们可能会根据产品变化、法律要求或数据处理方式的调整更新本隐私政策。
            </p>
            <p>
              更新后，我们会修改页面中的更新时间。继续使用 Idea Party，即表示你理解更新后的政策。
            </p>
          </section>

          <section class="space-y-4">
            <h2 class="text-2xl font-bold tracking-tight text-zinc-950 dark:text-white">
              12. 联系我们
            </h2>
            <p>
              如果你对隐私政策、数据处理或账户数据删除有任何问题，可以通过以下方式联系我们：
            </p>
            <p>
              <a href="mailto:privacy@ideaparty.ai" class="text-sky-600 dark:text-sky-400 hover:text-sky-500 dark:hover:text-sky-300 underline-offset-4 hover:underline transition-colors">
                privacy@ideaparty.ai
              </a>
            </p>
          </section>
        </article>
      </section>
    </main>

    <!-- Footer -->
    <footer class="mx-auto w-full max-w-4xl px-6 pb-12 text-center text-sm text-zinc-400 dark:text-zinc-500">
      © 2025 Idea Party. Built for imagination and conversation.
    </footer>
  </div>
</template>
