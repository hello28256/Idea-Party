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
      <h1 class="text-3xl font-bold text-gray-900 dark:text-white mb-2">Idea Party 服务条款</h1>
      <p class="text-sm text-gray-500 dark:text-gray-400 mb-10">最后更新于 2025</p>

      <article class="mt-10 space-y-8 leading-8 text-zinc-700 dark:text-zinc-300">
        <p>欢迎使用 Idea Party。</p>
        <p>Idea Party 是一个 AI 角色对话与群聊创作平台。你可以创建聊天室，邀请不同年代、不同领域、不同背景的 AI 角色一起讨论问题、碰撞观点、展开故事，或陪你进行灵感探索。</p>
        <p>在使用 Idea Party 前，请阅读并理解以下条款。使用本产品即表示你同意这些条款。</p>

        <section>
          <h2 class="text-xl font-semibold text-gray-900 dark:text-white mb-4">1. 服务说明</h2>
          <p>Idea Party 提供基于人工智能生成的角色对话服务。平台中的角色可能参考历史人物、现代公众人物、文学角色、虚构角色、职业身份或用户自定义设定。</p>
          <p class="mt-2">这些 AI 角色的回复由模型生成，并不代表真实人物本人、相关机构、作者，品牌或任何现实组织的真实观点。</p>
        </section>

        <section>
          <h2 class="text-xl font-semibold text-gray-900 dark:text-white mb-4">2. 用户账户</h2>
          <p>你需要提供必要的信息来创建账户，例如用户名、邮箱和密码。你应确保账户信息真实、准确，并妥善保管自己的登录凭证。</p>
          <p class="mt-2">因你主动泄露账户信息、共享账户或使用不安全设备导致的损失，由你自行承担。</p>
        </section>

        <section>
          <h2 class="text-xl font-semibold text-gray-900 dark:text-white mb-4">3. 可接受的使用方式</h2>
          <p class="mb-3">你可以使用 Idea Party 进行以下活动：</p>
          <ul class="list-disc list-inside space-y-1 ml-4">
            <li>创建 AI 角色聊天室</li>
            <li>与单个或多个 AI 角色对话</li>
            <li>让不同角色围绕一个主题进行讨论</li>
            <li>进行学习、创作、写作、头脑风暴和娱乐探索</li>
            <li>创建你自己的角色设定和对话场景</li>
          </ul>
          <p class="mt-4 mb-3">你同意不会使用 Idea Party 从事以下行为：</p>
          <ul class="list-disc list-inside space-y-1 ml-4">
            <li>传播违法、欺诈、威胁、骚扰、仇恨或恶意内容</li>
            <li>侵犯他人的隐私、名誉、知识产权或其他合法权益</li>
            <li>生成或传播恶意误导信息</li>
            <li>冒充他人或暗示 AI 回复来自真实人物本人</li>
            <li>攻击、干扰、破坏平台系统或绕过安全限制</li>
            <li>使用平台进行垃圾信息、自动化滥用或未经许可的数据抓取</li>
          </ul>
        </section>

        <section>
          <h2 class="text-xl font-semibold text-gray-900 dark:text-white mb-4">4. AI 内容免责声明</h2>
          <p>Idea Party 中的 AI 回复仅供参考、娱乐、灵感探索和一般信息交流。</p>
          <p class="mt-2">AI 生成内容可能存在错误、不完整、过时、偏见或虚构信息。你不应将 AI 回复作为法律、医疗、金融、心理，投资或其他专业领域的唯一依据。</p>
          <p class="mt-2">如涉及重要决策，请咨询具备资质的专业人士。</p>
        </section>

        <section>
          <h2 class="text-xl font-semibold text-gray-900 dark:text-white mb-4">5. 角色与真实人物说明</h2>
          <p>Idea Party 可能允许用户与基于历史人物、公众人物或虚构人物风格创建的 AI 角色互动。</p>
          <p class="mt-2">这些角色是模拟表达，并不代表真实人物本人授权、认可或参与。平台不会声称 AI 角色的回复等同于真实人物的观点。</p>
          <p class="mt-2">用户在分享相关内容时，也应避免误导他人认为这些内容来自真实人物本人。</p>
        </section>

        <section>
          <h2 class="text-xl font-semibold text-gray-900 dark:text-white mb-4">6. 用户内容</h2>
          <p>你在平台中输入的文字、创建的角色设定、发起的聊天室和对话内容，属于你的用户内容。</p>
          <p class="mt-2">你仍然保留对自己原创内容的权利。但为了提供服务、改进体验，维护安全和运行平台，你授予 Idea Party 在必要范围内处理、存储、展示和分析这些内容的权限。</p>
        </section>

        <section>
          <h2 class="text-xl font-semibold text-gray-900 dark:text-white mb-4">7. 平台内容与知识产权</h2>
          <p>Idea Party 的界面设计、产品功能，品牌名称、图形元素、系统逻辑和相关技术由平台或相关权利方拥有。</p>
          <p class="mt-2">未经许可，你不得复制、修改、反向工程、出售、出租、再分发或以其他方式滥用平台内容。</p>
        </section>

        <section>
          <h2 class="text-xl font-semibold text-gray-900 dark:text-white mb-4">8. 服务变更与中断</h2>
          <p>Idea Party 仍在持续迭代中。我们可能会不时修改、暂停、增加或删除部分功能。</p>
          <p class="mt-2">我们会尽力保持服务稳定，但不保证服务始终不间断、无错误或完全满足你的所有需求。</p>
        </section>

        <section>
          <h2 class="text-xl font-semibold text-gray-900 dark:text-white mb-4">9. 违规处理</h2>
          <p class="mb-2">如果你违反本条款，Idea Party 有权根据情况采取以下措施：</p>
          <ul class="list-disc list-inside space-y-1 ml-4">
            <li>删除相关内容</li>
            <li>限制部分功能</li>
            <li>暂停或终止账户</li>
            <li>拒绝继续提供服务</li>
            <li>在法律允许范围内采取进一步措施</li>
          </ul>
        </section>

        <section>
          <h2 class="text-xl font-semibold text-gray-900 dark:text-white mb-4">10. 责任限制</h2>
          <p>在法律允许的最大范围内，Idea Party 不对因使用或无法使用本服务而产生的间接损失、附带损失、特殊损失或后果性损失承担责任。</p>
          <p class="mt-2">你理解并同意，使用 AI 生成内容需要自行判断其准确性、适用性和风险。</p>
        </section>

        <section>
          <h2 class="text-xl font-semibold text-gray-900 dark:text-white mb-4">11. 条款更新</h2>
          <p>我们可能会根据产品变化、法律要求或运营需要更新本服务条款。更新后，我们会在页面中调整"最后更新"时间。</p>
          <p class="mt-2">继续使用 Idea Party，即表示你接受更新后的条款。</p>
        </section>

        <section>
          <h2 class="text-xl font-semibold text-gray-900 dark:text-white mb-4">12. 联系我们</h2>
          <p>如果你对本服务条款有任何问题，可以通过以下方式联系我们：</p>
          <p class="mt-2">support@ideaparty.ai</p>
        </section>
      </article>
    </main>
  </div>
</template>
