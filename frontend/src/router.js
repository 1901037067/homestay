import { createRouter, createWebHashHistory } from 'vue-router'
import LoginView from './views/LoginView.vue'
import HomeView from './views/HomeView.vue'

// hash 模式：将来 docker compose 里前端是静态资源，不需要服务端 rewrite
const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/login', component: LoginView, meta: { public: true } },
    { path: '/', component: HomeView }
  ]
})

router.beforeEach((to) => {
  if (to.meta.public) return true
  return localStorage.getItem('bnb_user') ? true : '/login'
})

export default router
