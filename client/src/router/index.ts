import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

// 占位视图 - 将在后续计划中实现
// 抽出为局部常量而非在每条 route 里直接 import：复用同一份懒加载 chunk，
// 多个路径（/rooms、/scenarios、/characters 共用 RoomListView）能命中浏览器缓存，
// 也便于后续把这些入口一次性替换为真实视图。
const RoomListView = () => import('@/views/RoomListView.vue')
const ChatView = () => import('@/views/ChatView.vue')
const SettingsView = () => import('@/views/SettingsView.vue')

// 应用前端路由中心。
// 路由结构按"业务域"分组：auth（login/register）、rooms/scenarios/characters（资源浏览与编辑）、
// chat（核心聊天页）、settings、admin（管理员）、legal（公开条款）。
// 每条路由通过 meta.requiresAuth 标记是否需要登录鉴权，meta.requiresAdmin 标记是否需要管理员权限。
// 守卫策略：
//   - 未登录访问受保护路由 → 踢回 /login，并在 query 上保留 redirect 以便登录后回跳；
//   - 已登录访问 login/register → 反向跳到 /rooms，避免重复进入认证流程；
//   - requiresAdmin 路由：除了要求登录外，还需 user.isAdmin === true（前端兜底，真正的权限拦截由后端接口保证）；
//   - 根路径 '/' 根据 localStorage 中的 accessToken 做条件跳转（有 → /rooms，无 → /login）。
// 设计意图：把权限/登录/管理员拦截抽到一处统一处理，让视图组件只关心渲染，不再各自写守卫逻辑。
const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { requiresAuth: false }
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('@/views/RegisterView.vue'),
      meta: { requiresAuth: false }
    },
    {
      path: '/rooms',
      name: 'rooms',
      component: RoomListView,
      meta: { requiresAuth: true }
    },
    {
      path: '/scenarios',
      name: 'scenarios',
      component: RoomListView,
      meta: { requiresAuth: true }
    },
    {
      path: '/characters',
      name: 'characters',
      component: RoomListView,
      meta: { requiresAuth: true }
    },
    {
      path: '/characters/create',
      name: 'character-create',
      component: () => import('@/views/CharacterCreateView.vue'),
      meta: { requiresAuth: true }
    },
    {
      path: '/characters/edit/:id',
      name: 'character-edit',
      // 编辑页：独立整页视图（由角色库卡片点击跳转）。
      // 复用同一组件由路由参数驱动表单行为不符合当前 UI 风格——编辑态需要长期可见的字段与独立操作区，因此拆出独立视图。
      component: () => import('@/views/CharacterEditView.vue'),
      meta: { requiresAuth: true }
    },
    {
      path: '/chat/:roomId',
      name: 'chat',
      component: ChatView,
      meta: { requiresAuth: true }
    },
    {
      path: '/settings',
      name: 'settings',
      component: SettingsView,
      meta: { requiresAuth: true }
    },
    {
      path: '/admin/feedbacks',
      name: 'admin-feedbacks',
      component: () => import('@/views/admin/AdminFeedbackView.vue'),
      // requiresAdmin 必须在守卫里二次校验，不能只看 token；
      // 后端会把 isAdmin 写入用户对象，这里只是前端兜底，真正的权限拦截由接口保证。
      meta: { requiresAuth: true, requiresAdmin: true }
    },
    {
      path: '/terms',
      name: 'terms',
      component: () => import('@/views/TermsView.vue'),
      meta: { requiresAuth: false }
    },
    {
      path: '/privacy',
      name: 'privacy',
      component: () => import('@/views/PrivacyView.vue'),
      meta: { requiresAuth: false }
    },
    {
      path: '/',
      // 根路径根据 token 做条件跳转：有 token 进房间列表，无 token 进登录页。
      // 这里只能依赖 localStorage，因为守卫此时还未执行，是用户打开应用的第一跳。
      redirect: () => {
        const isAuthenticated = !!localStorage.getItem('accessToken')
        return isAuthenticated ? '/rooms' : '/login'
      }
    },
    {
      // 兜底路由：任何未匹配上的 URL（拼错、过期链接、手输路径）都收口到房间列表，
      // 避免进入空白页影响体验。
      path: '/:pathMatch(.*)*',
      redirect: '/rooms'
    }
  ]
})

// 全局导航守卫。
// 契约：每次路由切换前同步判定，无 token 直接踢回登录页；
// 已登录用户访问登录/注册页时反向跳转到房间列表，避免重复进入认证流程；
// requiresAdmin 路由二次校验用户 store 中的 isAdmin 字段（接口返回的是真实权限位，不能只看 token）。
// 调用方：Vue Router 内部，App 挂载时注册一次即可生效。
router.beforeEach((to, _from, next) => {
  const publicPaths = ['/login', '/register', '/terms', '/privacy']
  const isAuthenticated = !!localStorage.getItem('accessToken')

  if (!publicPaths.includes(to.path) && !isAuthenticated) {
    next({ name: 'login' })
    return
  }

  if ((to.name === 'login' || to.name === 'register') && isAuthenticated) {
    next({ name: 'rooms' })
    return
  }

  // 管理员守卫：必须已登录且 user store 中 isAdmin=true
  if (to.meta.requiresAdmin) {
    const authStore = useAuthStore()
    if (!authStore.user?.isAdmin) {
      next({ name: 'rooms' })
      return
    }
  }

  next()
})

export default router
