import { computed, ref } from 'vue'

const KEY = 'bnb.theme'
const DENSITY_KEY = 'bnb.density'
const LABELS_KEY = 'bnb.navLabels'
const NAV_FONT_KEY = 'bnb.navFont'
const BODY_FONT_KEY = 'bnb.bodyFont'
const media = window.matchMedia('(prefers-color-scheme: dark)')
const mode = ref(localStorage.getItem(KEY) || 'auto')
const density = ref(localStorage.getItem(DENSITY_KEY) === 'compact' ? 'compact' : 'comfortable')
const navLabels = ref(localStorage.getItem(LABELS_KEY) !== '0')
const navFont = ref(localStorage.getItem(NAV_FONT_KEY) || 'standard')
const bodyFont = ref(localStorage.getItem(BODY_FONT_KEY) || 'standard')

// 字号只覆盖"这一族"令牌，standard 档什么都不写 —— 让 tokens.css 保持唯一默认值来源。
// 正文字号改的是 --bnb-fs-subheadline，而 Element 的 --el-font-size-base 是 var() 引用它，
// 自定义属性在使用点才解析，所以表格/输入框会跟着一起变（这就是为什么不用逐个组件改）
const NAV_FONTS = {
  small: { '--bnb-nav-fs': '12px' },
  standard: null,
  large: { '--bnb-nav-fs': '15px' }
}
const BODY_FONTS = {
  small: { '--bnb-fs-body': '15px', '--bnb-fs-subheadline': '14px', '--bnb-fs-callout': '12px' },
  standard: null,
  large: { '--bnb-fs-body': '19px', '--bnb-fs-subheadline': '17px', '--bnb-fs-callout': '14px' }
}
const FONT_VARS = ['--bnb-nav-fs', '--bnb-fs-body', '--bnb-fs-subheadline', '--bnb-fs-callout']

// 系统偏好本身不是响应式的，用一个 ref 跟着 media 事件走 ——
// 这样"跟随系统"档下换系统主题，依赖 isDark 的地方（比如图表的配色重绘）才会被触发
const systemDark = ref(media.matches)
const isDark = computed(() => mode.value === 'dark' || (mode.value === 'auto' && systemDark.value))

/** 配色与字号都落在 <html> 上：一处应用，全站跟着变（密度靠 tokens.css 的 .bnb-dense 覆盖块） */
function apply() {
  const root = document.documentElement
  root.classList.toggle('dark', isDark.value)
  root.classList.toggle('bnb-dense', density.value === 'compact')
  const merged = { ...(NAV_FONTS[navFont.value] || {}), ...(BODY_FONTS[bodyFont.value] || {}) }
  FONT_VARS.forEach((v) => {
    if (merged[v]) root.style.setProperty(v, merged[v])
    else root.style.removeProperty(v)
  })
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

export const FONT_SIZES = [
  { value: 'small', label: '小' },
  { value: 'standard', label: '标准' },
  { value: 'large', label: '大' }
]

/** 偏好只存这台浏览器的 localStorage：这是单机部署的前台端，没有账号级服务端配置可言 */
export const PREF_KEYS = [KEY, DENSITY_KEY, LABELS_KEY, NAV_FONT_KEY, BODY_FONT_KEY]

function persist(key, value) {
  localStorage.setItem(key, value)
  apply()
}

export function useTheme() {
  function setMode(next) {
    mode.value = next
    persist(KEY, next)
  }
  function setDensity(next) {
    density.value = next === 'compact' ? 'compact' : 'comfortable'
    persist(DENSITY_KEY, density.value)
  }
  function setNavLabels(next) {
    navLabels.value = !!next
    localStorage.setItem(LABELS_KEY, navLabels.value ? '1' : '0')
  }
  function setNavFont(next) {
    navFont.value = NAV_FONTS[next] ? next : 'standard'
    persist(NAV_FONT_KEY, navFont.value)
  }
  function setBodyFont(next) {
    bodyFont.value = BODY_FONTS[next] ? next : 'standard'
    persist(BODY_FONT_KEY, bodyFont.value)
  }
  return {
    mode, setMode, isDark,
    density, setDensity,
    navLabels, setNavLabels,
    navFont, setNavFont,
    bodyFont, setBodyFont
  }
}

// 模块加载即应用，避免首屏闪一下错误配色
apply()
