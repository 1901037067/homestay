import { computed, ref } from 'vue'

const KEY = 'bnb.theme'
const DENSITY_KEY = 'bnb.density'
const LABELS_KEY = 'bnb.navLabels'
const media = window.matchMedia('(prefers-color-scheme: dark)')
const mode = ref(localStorage.getItem(KEY) || 'auto')
const density = ref(localStorage.getItem(DENSITY_KEY) === 'compact' ? 'compact' : 'comfortable')
const navLabels = ref(localStorage.getItem(LABELS_KEY) !== '0')

// 系统偏好本身不是响应式的，用一个 ref 跟着 media 事件走 ——
// 这样"跟随系统"档下换系统主题，依赖 isDark 的地方（比如图表的配色重绘）才会被触发
const systemDark = ref(media.matches)
const isDark = computed(() => mode.value === 'dark' || (mode.value === 'auto' && systemDark.value))

/** 三档外观偏好都落在 <html> 上：一处应用，全站跟着变（密度靠 tokens.css 的 .bnb-dense 覆盖块） */
function apply() {
  document.documentElement.classList.toggle('dark', isDark.value)
  document.documentElement.classList.toggle('bnb-dense', density.value === 'compact')
}

// auto 档要跟随系统实时切换，否则用户在系统里换了主题页面不跟
media.addEventListener('change', (e) => {
  systemDark.value = e.matches
  if (mode.value === 'auto') apply()
})

export const THEME_MODES = [
  { value: 'light', label: '浅色' },
  { value: 'dark', label: '深色' },
  { value: 'auto', label: '跟随系统' }
]

export const DENSITIES = [
  { value: 'comfortable', label: '舒适' },
  { value: 'compact', label: '紧凑' }
]

/** 偏好只存这台浏览器的 localStorage：这是单机部署的前台端，没有账号级服务端配置可言 */
export const PREF_KEYS = [KEY, DENSITY_KEY, LABELS_KEY]

export function useTheme() {
  function setMode(next) {
    mode.value = next
    localStorage.setItem(KEY, next)
    apply()
  }
  function setDensity(next) {
    density.value = next === 'compact' ? 'compact' : 'comfortable'
    localStorage.setItem(DENSITY_KEY, density.value)
    apply()
  }
  function setNavLabels(next) {
    navLabels.value = !!next
    localStorage.setItem(LABELS_KEY, navLabels.value ? '1' : '0')
  }
  return { mode, setMode, isDark, density, setDensity, navLabels, setNavLabels }
}

// 模块加载即应用，避免首屏闪一下错误配色
apply()
