import { ref } from 'vue'

const KEY = 'bnb.theme'
const media = window.matchMedia('(prefers-color-scheme: dark)')
const mode = ref(localStorage.getItem(KEY) || 'auto')

function isDark() {
  return mode.value === 'dark' || (mode.value === 'auto' && media.matches)
}

function apply() {
  document.documentElement.classList.toggle('dark', isDark())
}

// auto 档要跟随系统实时切换，否则用户在系统里换了主题页面不跟
media.addEventListener('change', () => {
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
