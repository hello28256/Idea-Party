import { createRouter, createWebHistory } from 'vue-router'

// Placeholder views - will be implemented in future plans
const RoomListView = () => import('@/views/RoomListView.vue')
const ChatView = () => import('@/views/ChatView.vue')

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
      path: '/chat/:roomId',
      name: 'chat',
      component: ChatView,
      meta: { requiresAuth: true }
    },
    {
      path: '/',
      redirect: '/rooms'
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/rooms'
    }
  ]
})

// Auth guard - redirect to login if accessing protected route without token
router.beforeEach((to, from, next) => {
  const publicPaths = ['/login', '/register']
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
