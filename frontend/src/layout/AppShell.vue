<template>
  <div class="shell">
    <aside class="side" :class="{ 'side--icon': !navLabels }">
      <div class="brand">
        <!-- 品牌位直接用标签页那枚 favicon.svg：同一张图，页面与标签头不会长两个样 -->
        <img class="brand__mark" src="/favicon.svg" alt="" width="34" height="34" />
        <span v-if="navLabels" class="brand__text">
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
          :title="item.label"
        >
          <el-icon :size="16"><component :is="item.icon" /></el-icon>
          <span v-if="navLabels">{{ item.label }}</span>
        </router-link>
      </nav>

      <div class="side__foot">
        <el-dropdown trigger="click" placement="top-start" @command="onCommand">
          <button class="user" aria-label="账号菜单">
            <span class="user__avatar">{{ initial }}</span>
            <span v-if="navLabels" class="user__name">{{ user?.displayName }}</span>
            <el-icon v-if="navLabels" :size="12"><ArrowDown /></el-icon>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="settings">
                <el-icon><Setting /></el-icon>设置
              </el-dropdown-item>
              <el-dropdown-item command="logout" divided>
                <el-icon><SwitchButton /></el-icon>退出登录
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>

        <div class="tools">
          <button class="tool" :aria-label="themeHint" :title="themeHint" @click="toggleTheme">
            <el-icon :size="16"><component :is="isDark ? Sunny : Moon" /></el-icon>
          </button>
          <button class="tool" aria-label="设置" title="设置" @click="settings = true">
            <el-icon :size="16"><Setting /></el-icon>
          </button>
        </div>
      </div>
    </aside>

    <main class="body">
      <router-view />
    </main>

    <SettingsDialog v-model="settings" />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  House, Grid, Tickets, Ticket, OfficeBuilding, ArrowDown, SwitchButton, Calendar, Document,
  PriceTag, TrendCharts, Sunny, Moon, Setting
} from '@element-plus/icons-vue'
import { THEME_MODES, useTheme } from '../composables/useTheme'
import SettingsDialog from '../components/SettingsDialog.vue'
import http from '../api'

const route = useRoute()
const router = useRouter()
const { mode, setMode, isDark, navLabels } = useTheme()

const settings = ref(false)

const nav = [
  { path: '/', label: '概览', icon: Grid },
  { path: '/calendar', label: '房态日历', icon: Calendar },
  { path: '/orders', label: '订单', icon: Tickets },
  { path: '/rooms', label: '房间', icon: OfficeBuilding },
  { path: '/room-types', label: '房型', icon: Ticket },
  { path: '/properties', label: '民宿', icon: House },
  { path: '/rate-rules', label: '价格规则', icon: PriceTag },
  { path: '/stats', label: '报表', icon: TrendCharts },
  { path: '/logs', label: '操作日志', icon: Document }
]

const user = computed(() => JSON.parse(localStorage.getItem('bnb_user') || 'null'))
const initial = computed(() => (user.value?.displayName || '?').slice(0, 1))

// 三档配色收进设置里，侧栏这一枚只管"把当前看到的翻过来"。
// auto 档下按钮显示的是系统此刻解析出来的那一档，点一下就把模式钉死成相反档
const themeHint = computed(() => {
  const label = THEME_MODES.find((m) => m.value === mode.value)?.label
  return `配色：${label}（当前${isDark.value ? '深色' : '浅色'}）· 点按切到${isDark.value ? '浅色' : '深色'}，三档在设置里`
})

function toggleTheme() {
  setMode(isDark.value ? 'light' : 'dark')
}

onMounted(async () => {
  const res = await http.get('/me')
  localStorage.setItem('bnb_user', JSON.stringify(res.data))
})

async function onCommand(cmd) {
  if (cmd === 'settings') {
    settings.value = true
  } else if (cmd === 'logout') {
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

/* 设置里关掉"显示导航文字"就收成图标栏 */
.side--icon {
  width: var(--bnb-nav-w-compact);
  padding: var(--bnb-space-5) var(--bnb-space-2);
}
.side--icon .brand {
  justify-content: center;
  padding: 0;
}
.side--icon .nav__item {
  justify-content: center;
  padding: 8px 0;
}
.side--icon .user,
.side--icon .tools {
  justify-content: center;
}

.brand {
  display: flex;
  align-items: center;
  gap: var(--bnb-space-3);
  padding: 0 var(--bnb-space-2);
}

.brand__mark {
  display: block;
  width: 34px;
  height: 34px;
  border-radius: 10px;
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
  font-size: var(--bnb-nav-fs);
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

/* ---------- 侧栏底部：账号 + 配色 + 设置 同一行（原来在右侧顶栏上） ---------- */
.side__foot {
  margin-top: auto;
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: var(--bnb-space-2);
}
/* el-dropdown 自己包了一层 span，账号要撑满剩余宽度就得指挥它 */
.side__foot :deep(.el-dropdown) {
  flex: 1;
  min-width: 0;
}

/* 收成图标栏时这一行放不下，改为竖排 */
.side--icon .side__foot {
  flex-direction: column;
}
.side--icon .side__foot :deep(.el-dropdown) {
  flex: none;
}

.user {
  display: flex;
  align-items: center;
  gap: var(--bnb-space-2);
  width: 100%;
  padding: 4px 10px 4px 4px;
  border: 1px solid var(--bnb-separator);
  border-radius: var(--bnb-radius-pill);
  background: var(--bnb-bg-card);
  color: var(--bnb-text-primary);
  font-size: var(--bnb-fs-callout);
  cursor: pointer;
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
  flex-shrink: 0;
}
.user__name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  text-align: left;
}

.tools {
  display: flex;
  gap: var(--bnb-space-2);
}

.tool {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border: 1px solid var(--bnb-separator);
  border-radius: var(--bnb-radius-sm);
  background: var(--bnb-bg-card);
  color: var(--bnb-text-secondary);
  cursor: pointer;
  transition: color var(--bnb-duration-fast) var(--bnb-ease),
    border-color var(--bnb-duration-fast) var(--bnb-ease);
}
.tool:hover {
  color: var(--bnb-accent);
  border-color: var(--bnb-accent);
}

/* ---------- 主区 ---------- */
.body {
  flex: 1;
  min-width: 0;
}

/* ---------- 窄屏：侧栏收成顶部横向导航 ---------- */
@media (max-width: 900px) {
  .shell {
    flex-direction: column;
  }
  .side,
  .side--icon {
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
  .nav__item span,
  .brand__text {
    display: none;
  }
  .side__foot {
    margin-top: 0;
    margin-left: auto;
    flex-direction: row;
    align-items: center;
  }
  .user {
    width: auto;
  }
}
</style>
