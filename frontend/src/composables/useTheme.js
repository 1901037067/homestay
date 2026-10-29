import { computed, ref } from 'vue'

const KEY = 'bnb.theme'
const media = window.matchMedia('(prefers-color-scheme: dark)')
const mode = ref(localStorage.getItem(KEY) || 'auto')

// 系统偏好本身不是响应式的，用一个 ref 跟着 media 事件走 ——
// 这样"跟随系统"档下换系统主题，依赖 isDark 的地方（比如图表的配色重绘）才会被触发
const systemDark = ref(media.matches)
const isDark = computed(() => mode.value === 'dark' || (mode.value === 'auto' && systemDark.value))

function apply() {
  document.documentElement.classList.toggle('dark', isDark.value)
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

export function useTheme() {
  function setMode(next) {
    mode.value = next
    localStorage.setItem(KEY, next)
    apply()
  }
  return { mode, setMode, isDark }
}

// 模块加载即应用，避免首屏闪一下错误配色
apply()
