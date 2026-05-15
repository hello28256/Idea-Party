import { createRouter, createWebHistory } from 'vue-router'

// Placeholder views - will be implemented in future plans
const RoomListView = () => import('@/views/RoomListView.vue')
const ChatView = () => import('@/views/ChatView.vue')
const SettingsView = () => import('@/views/SettingsView.vue')

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
      component: () => import('@/views/CharacterCreateView.vue'),
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
      redirect: () => {
        const isAuthenticated = !!localStorage.getItem('accessToken')
        return isAuthenticated ? '/rooms' : '/login'
      }
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/rooms'
    }
  ]
})

// Auth guard - redirect to login if accessing protected route without token
router.beforeEach((to, _from, next) => {
  const publicPaths = ['/login', '/register', '/terms', '/privacy']
  const isAuthenticated = !!localStorage.getItem('accessToken')

  if (!publicPaths.includes(to.path) && !isAuthenticated) {
    next({ name: 'login' })
  } else if ((to.name === 'login' || to.name === 'register') && isAuthenticated) {
    next({ name: 'rooms' })
  } else {
    next()
  }
})

export default router
