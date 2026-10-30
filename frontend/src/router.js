import { createRouter, createWebHashHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
// 路由级懒加载：Element Plus 全量引入后主包 1.1MB，首屏只该拿到登录页要的那部分
const AppShell = () => import('./layout/AppShell.vue')
const LoginView = () => import('./views/LoginView.vue')
const DashboardView = () => import('./views/DashboardView.vue')
const RoomListView = () => import('./views/room/RoomListView.vue')
const RoomTypeListView = () => import('./views/room/RoomTypeListView.vue')
const PropertyListView = () => import('./views/room/PropertyListView.vue')
const RoomCalendarView = () => import('./views/calendar/RoomCalendarView.vue')
const OrdersView = () => import('./views/booking/OrdersView.vue')
const PriceRuleView = () => import('./views/billing/PriceRuleView.vue')
const StatsView = () => import('./views/stats/StatsView.vue')
const OperationLogView = () => import('./views/system/OperationLogView.vue')
const UserAdminView = () => import('./views/system/UserAdminView.vue')

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
        { path: 'orders', component: OrdersView },
        { path: 'rooms', component: RoomListView },
        { path: 'room-types', component: RoomTypeListView },
        { path: 'properties', component: PropertyListView },
        { path: 'rate-rules', component: PriceRuleView },
        { path: 'stats', component: StatsView },
        { path: 'logs', component: OperationLogView },
        // 账号页只对店长开放。真正的拒绝在服务层（AccessScope.requireManager），手敲 URL 也吃 1003；
        // 这里的 meta 只负责体验：别让前台进到一个每点一下都报错的页面
        { path: 'users', component: UserAdminView, meta: { manager: true } }
      ]
    }
  ]
})

// 守卫只看 localStorage 是不够的（它只是缓存），真正的判定在后端 session：
// 任何请求 401 时由 api.js 的拦截器统一踢回登录页。
// 这里唯一多管的是一件体验上的事：店长专属页面前台不该进得去。
router.beforeEach((to) => {
  if (to.meta.public) return true
  const raw = localStorage.getItem('bnb_user')
  if (!raw) return '/login'
  if (to.meta.manager) {
    let me = null
    try {
      me = JSON.parse(raw)
    } catch {
      return '/login' // 缓存坏了当没登录，别让脏数据把路由卡住
    }
    if (!me?.manager) {
      ElMessage.warning('「账号」只有店长可以访问')
      return '/'
    }
  }
  return true
})

export default router
