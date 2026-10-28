import { createRouter, createWebHashHistory } from 'vue-router'
// 路由级懒加载：Element Plus 全量引入后主包 1.1MB，首屏只该拿到登录页要的那部分
const AppShell = () => import('./layout/AppShell.vue')
const LoginView = () => import('./views/LoginView.vue')
const DashboardView = () => import('./views/DashboardView.vue')
const RoomListView = () => import('./views/room/RoomListView.vue')
const RoomTypeListView = () => import('./views/room/RoomTypeListView.vue')
const PropertyListView = () => import('./views/room/PropertyListView.vue')
const RoomCalendarView = () => import('./views/calendar/RoomCalendarView.vue')
const OperationLogView = () => import('./views/system/OperationLogView.vue')

// hash 模式：将来前端是静态资源托管，不需要服务端 rewrite
const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/login', component: LoginView, meta: { public: true } },
    {
      path: '/',
      component: AppShell,
      children: [
        { path: '', component: DashboardView },
        { path: 'calendar', component: RoomCalendarView },
        { path: 'rooms', component: RoomListView },
        { path: 'room-types', component: RoomTypeListView },
        { path: 'properties', component: PropertyListView },
        { path: 'logs', component: OperationLogView }
      ]
    }
  ]
})

// 守卫只看 localStorage 是不够的（它只是缓存），真正的判定在后端 session：
// 任何请求 401 时由 api.js 的拦截器统一踢回登录页。
router.beforeEach((to) => {
  if (to.meta.public) return true
  return localStorage.getItem('bnb_user') ? true : '/login'
})

export default router
