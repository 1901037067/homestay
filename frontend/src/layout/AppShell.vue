<template>
  <div class="shell">
    <aside class="side">
      <div class="brand">
        <span class="brand__mark"><el-icon :size="17"><House /></el-icon></span>
        <span class="brand__text">
          <strong>云栖民宿</strong>
          <small>分时预订系统</small>
        </span>
      </div>

      <nav class="nav">
        <router-link
          v-for="item in nav"
          :key="item.path"
          :to="item.path"
          class="nav__item"
          :class="{ 'is-active': route.path === item.path }"
          :aria-label="item.label"
        >
          <el-icon :size="16"><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </router-link>
      </nav>

      <div class="side__foot">
        <span class="bnb-muted">W2 · 房源域</span>
      </div>
    </aside>

    <div class="main">
      <header class="top">
        <div class="top__title">
          <h1>{{ current?.label }}</h1>
          <p class="bnb-muted">{{ current?.desc }}</p>
        </div>

        <div class="top__actions">
          <div class="bnb-segmented" role="group" aria-label="配色模式">
            <button
              v-for="m in THEME_MODES"
              :key="m.value"
              class="bnb-segmented__item"
              :class="{ 'is-active': mode === m.value }"
              :aria-pressed="mode === m.value"
              @click="setMode(m.value)"
            >
              {{ m.label }}
            </button>
          </div>

          <el-dropdown trigger="click" @command="onCommand">
            <button class="user" aria-label="账号菜单">
              <span class="user__avatar">{{ initial }}</span>
              <span class="user__name">{{ user?.displayName }}</span>
              <el-icon :size="12"><ArrowDown /></el-icon>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">
                  <el-icon><SwitchButton /></el-icon>退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main class="body">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  House, Grid, Tickets, OfficeBuilding, ArrowDown, SwitchButton, Calendar
} from '@element-plus/icons-vue'
import { THEME_MODES, useTheme } from '../composables/useTheme'
import http from '../api'

const route = useRoute()
const router = useRouter()
const { mode, setMode } = useTheme()

const nav = [
  { path: '/', label: '概览', icon: Grid, desc: '今日房态与经营概况' },
  { path: '/calendar', label: '房态日历', icon: Calendar, desc: '点空白格开单，块上画的是含清扫缓冲的占用' },
  { path: '/rooms', label: '房间', icon: OfficeBuilding, desc: '具体房间的增删改与上下架' },
  { path: '/room-types', label: '房型', icon: Tickets, desc: '定价、起步时长与清扫缓冲' },
  { path: '/properties', label: '民宿', icon: House, desc: '门店与营业日切割点' }
]

const current = computed(() => nav.find((n) => n.path === route.path))
const user = computed(() => JSON.parse(localStorage.getItem('bnb_user') || 'null'))
const initial = computed(() => (user.value?.displayName || '?').slice(0, 1))

onMounted(async () => {
  const res = await http.get('/me')
  localStorage.setItem('bnb_user', JSON.stringify(res.data))
})

async function onCommand(cmd) {
  if (cmd === 'logout') {
    await http.get('/logout')
    localStorage.removeItem('bnb_user')
    router.push('/login')
  }
}
</script>

<style scoped>
.shell {
  display: flex;
  min-height: 100vh;
}

/* ---------- 侧栏：macOS 系统设置那种半透明侧栏 ---------- */
.side {
  width: var(--bnb-nav-w);
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: var(--bnb-space-6);
  padding: var(--bnb-space-5) var(--bnb-space-4);
  background: var(--bnb-material);
  backdrop-filter: var(--bnb-material-blur);
  border-right: 1px solid var(--bnb-separator);
  position: sticky;
  top: 0;
  height: 100vh;
}

.brand {
  display: flex;
  align-items: center;
  gap: var(--bnb-space-3);
  padding: 0 var(--bnb-space-2);
}

.brand__mark {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 10px;
  background: var(--bnb-accent);
  color: var(--bnb-accent-on);
  flex-shrink: 0;
}

.brand__text {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
  min-width: 0;
}
.brand__text strong {
  font-size: var(--bnb-fs-subheadline);
  font-weight: 600;
  letter-spacing: var(--bnb-ls-body);
}
.brand__text small {
  font-size: var(--bnb-fs-caption);
  color: var(--bnb-text-secondary);
}

.nav {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.nav__item {
  display: flex;
  align-items: center;
  gap: var(--bnb-space-3);
  padding: 8px var(--bnb-space-3);
  border-radius: var(--bnb-radius-sm);
  font-size: var(--bnb-fs-callout);
  font-weight: 500;
  color: var(--bnb-text-secondary);
  text-decoration: none;
  transition: background-color var(--bnb-duration-fast) var(--bnb-ease),
    color var(--bnb-duration-fast) var(--bnb-ease);
}

.nav__item:hover {
  background: var(--bnb-fill-tertiary);
  color: var(--bnb-text-primary);
}

.nav__item.is-active {
  background: var(--bnb-accent-soft);
  color: var(--bnb-accent);
}

.side__foot {
  margin-top: auto;
  padding: 0 var(--bnb-space-2);
  font-size: var(--bnb-fs-caption);
}

/* ---------- 主区 ---------- */
.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.top {
  position: sticky;
  top: 0;
  z-index: var(--bnb-z-sticky);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--bnb-space-4);
  min-height: var(--bnb-header-h);
  padding: var(--bnb-space-3) var(--bnb-space-8);
  background: var(--bnb-material);
  backdrop-filter: var(--bnb-material-blur);
  border-bottom: 1px solid var(--bnb-separator);
}

.top__title h1 {
  font-size: var(--bnb-fs-title3);
}
.top__title p {
  font-size: var(--bnb-fs-footnote);
}

.top__actions {
  display: flex;
  align-items: center;
  gap: var(--bnb-space-4);
}

.user {
  display: flex;
  align-items: center;
  gap: var(--bnb-space-2);
  padding: 4px 10px 4px 4px;
  border: 1px solid var(--bnb-separator);
  border-radius: var(--bnb-radius-pill);
  background: var(--bnb-bg-card);
  color: var(--bnb-text-primary);
  font-size: var(--bnb-fs-callout);
  transition: border-color var(--bnb-duration-fast) var(--bnb-ease);
}
.user:hover {
  border-color: var(--bnb-text-tertiary);
}
.user__avatar {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: var(--bnb-fill-secondary);
  font-weight: 600;
}
.user__name {
  max-width: 90px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.body {
  flex: 1;
}

/* ---------- 窄屏：侧栏收成顶部横向导航 ---------- */
@media (max-width: 900px) {
  .shell {
    flex-direction: column;
  }
  .side {
    width: auto;
    height: auto;
    position: static;
    flex-direction: row;
    align-items: center;
    gap: var(--bnb-space-4);
    padding: var(--bnb-space-3) var(--bnb-space-4);
    border-right: 0;
    border-bottom: 1px solid var(--bnb-separator);
  }
  .nav {
    flex-direction: row;
    overflow-x: auto;
  }
  .nav__item span {
    display: none;
  }
  .side__foot,
  .brand__text {
    display: none;
  }
  .top {
    padding: var(--bnb-space-3) var(--bnb-space-4);
    flex-wrap: wrap;
  }
}
</style>
