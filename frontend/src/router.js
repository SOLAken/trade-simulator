import { createRouter, createWebHistory } from 'vue-router'
import { useAuth } from './stores/auth.js'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('./views/Login.vue') },
    { path: '/', component: () => import('./views/Market.vue'), meta: { auth: true } },
  ],
})

router.beforeEach((to) => {
  if (to.meta.auth && !useAuth().token) return '/login'
})

export default router
