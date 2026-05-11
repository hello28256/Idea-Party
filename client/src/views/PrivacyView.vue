<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()

function goBack() {
  router.push('/login')
}
</script>

<template>
  <div class="min-h-screen bg-zinc-50 text-zinc-950 dark:bg-[#0f1115] dark:text-white">
    <!-- Header -->
    <header class="border-b border-zinc-200 bg-white dark:border-white/10 dark:bg-[#111315]">
      <div class="h-[72px] px-6 flex items-center justify-between max-w-6xl mx-auto">
        <router-link to="/login" class="flex items-center gap-3">
          <img src="/image.svg" alt="Idea Party" class="w-8 h-8" />
          <span class="text-lg font-bold text-gray-900 dark:text-white">Idea Party</span>
        </router-link>
        <button
          @click="goBack"
          class="text-sm font-medium text-gray-600 hover:text-gray-900 dark:text-gray-400 dark:hover:text-white transition-colors"
        >
          返回登录
        </button>
      </div>
    </header>

    <!-- Main Content -->
    <main class="mx-auto max-w-4xl px-6 py-12">
      <h1 class="text-3xl font-bold text-gray-900 dark:text-white mb-2">Idea Party 隐私政策</h1>
      <p class="text-sm text-gray-500 dark:text-gray-400 mb-10">我们如何保护你的数据</p>

      <article class="mt-10 space-y-8 leading-8 text-zinc-700 dark:text-zinc-300">
        <p>Idea Party 重视你的隐私与数据安全。</p>
        <p>本隐私政策说明我们会收集哪些信息、如何使用这些信息，以及你对自己数据拥有的选择权。使用 Idea Party 即表示你理解并同意本政策中描述的做法。</p>

        <section>
          <h2 class="text-xl font-semibold text-gray-900 dark:text-white mb-4">1. 我们收集的信息</h2>
          <p class="mb-3">为了提供服务，我们可能会收集以下信息：</p>
          <p class="font-medium text-gray-900 dark:text-white mt-4">账户信息：</p>
          <p>包括用户名、邮箱地址、密码加密信息以及账户创建时间。</p>
          <p class="font-medium text-gray-900 dark:text-white mt-4">使用信息：</p>
          <p>包括你访问的页面、创建的聊天室、使用的功能、登录状态、设备类型和基础日志信息。</p>
          <p class="font-medium text-gray-900 dark:text-white mt-4">用户输入内容：</p>
          <p>包括你发送给 AI 角色的消息、创建的角色设定、聊天室标题、场景描述和其他你主动输入的内容。</p>
          <p class="font-medium text-gray-900 dark:text-white mt-4">技术信息：</p>
          <p>包括浏览器类型、设备信息、IP 地址、错误日志、性能数据和 Cookie 或本地存储信息。</p>
        </section>

        <section>
          <h2 class="text-xl font-semibold text-gray-900 dark:text-white mb-4">2. 我们如何使用信息</h2>
          <p class="mb-3">我们使用收集的信息来：</p>
          <ul class="list-disc list-inside space-y-1 ml-4">
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

        <section>
          <h2 class="text-xl font-semibold text-gray-900 dark:text-white mb-4">3. AI 对话内容说明</h2>
          <p>你与 AI 角色的对话可能会被系统处理，以生成回复、保持上下文、改进体验和维护平台安全。</p>
          <p class="mt-2">我们不会故意公开你的私人对话内容。除非你主动分享、发布或法律要求，否则你的个人对话不会作为公开内容展示给其他用户。</p>
          <p class="mt-2 font-medium text-gray-900 dark:text-white">请不要在对话中输入高度敏感信息，例如身份证号、银行卡号、密码、精确住址、医疗隐私或其他不希望被处理的信息。</p>
        </section>

        <section>
          <h2 class="text-xl font-semibold text-gray-900 dark:text-white mb-4">4. 数据存储与安全</h2>
          <p>我们会采取合理的技术和管理措施保护你的信息，包括访问控制、加密存储、日志监控和权限限制。</p>
          <p class="mt-2">但你也理解，任何互联网服务都无法保证绝对安全。你应妥善保管自己的账户和密码，并避免在公共设备上保持登录状态。</p>
        </section>

        <section>
          <h2 class="text-xl font-semibold text-gray-900 dark:text-white mb-4">5. 我们是否出售数据</h2>
          <p>Idea Party 不会出售你的个人信息。</p>
          <p class="mt-2">我们不会将你的邮箱、账户信息或私人对话出售给广告商或第三方数据交易机构。</p>
        </section>

        <section>
          <h2 class="text-xl font-semibold text-gray-900 dark:text-white mb-4">6. 第三方服务</h2>
          <p>为了提供产品功能，我们可能会使用第三方基础服务，例如数据库、云存储、身份验证、模型接口、日志分析或部署平台。</p>
          <p class="mt-2">这些第三方服务仅在提供必要功能的范围内处理数据，并受到其各自隐私政策约束。</p>
        </section>

        <section>
          <h2 class="text-xl font-semibold text-gray-900 dark:text-white mb-4">7. Cookie 与本地存储</h2>
          <p>我们可能使用 Cookie 或浏览器本地存储来保存登录状态、主题偏好、语言偏好和基础使用设置。</p>
          <p class="mt-2">你可以通过浏览器设置清除这些数据，但清除后部分功能可能需要重新登录或重新设置。</p>
        </section>

        <section>
          <h2 class="text-xl font-semibold text-gray-900 dark:text-white mb-4">8. 你的选择权</h2>
          <p class="mb-3">你可以：</p>
          <ul class="list-disc list-inside space-y-1 ml-4">
            <li>查看和修改账户信息</li>
            <li>删除部分对话或聊天室</li>
            <li>退出登录</li>
            <li>请求删除账户</li>
            <li>请求了解我们如何处理你的数据</li>
          </ul>
          <p class="mt-4">如果未来产品提供更多隐私设置，我们会在界面中提供相应入口。</p>
        </section>

        <section>
          <h2 class="text-xl font-semibold text-gray-900 dark:text-white mb-4">9. 未成年人使用</h2>
          <p>Idea Party 面向具备相应理解能力的用户使用。如果你是未成年人，请在监护人同意和指导下使用本服务。</p>
          <p class="mt-2">我们不鼓励未成年人在平台中输入敏感个人信息。</p>
        </section>

        <section>
          <h2 class="text-xl font-semibold text-gray-900 dark:text-white mb-4">10. 数据保留</h2>
          <p>我们会在提供服务所需期间保留相关数据。</p>
          <p class="mt-2">当你删除账户或请求删除数据时，我们会在合理时间内处理相关请求。但出于安全、备份、法律合规或争议处理需要，部分信息可能会在有限时间内保留。</p>
        </section>

        <section>
          <h2 class="text-xl font-semibold text-gray-900 dark:text-white mb-4">11. 政策更新</h2>
          <p>我们可能会根据产品变化、法律要求或数据处理方式的调整更新本隐私政策。</p>
          <p class="mt-2">更新后，我们会修改页面中的更新时间。继续使用 Idea Party，即表示你理解更新后的政策。</p>
        </section>

        <section>
          <h2 class="text-xl font-semibold text-gray-900 dark:text-white mb-4">12. 联系我们</h2>
          <p>如果你对隐私政策、数据处理或账户数据删除有任何问题，可以通过以下方式联系我们：</p>
          <p class="mt-2">privacy@ideaparty.ai</p>
        </section>
      </article>
    </main>
  </div>
</template>
