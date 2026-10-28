import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'

// 顺序有讲究：组件库基础样式 → 它的暗色变量 → 我们的令牌覆盖 → 全局排版。
// 反过来写的话 tokens.css 会被 Element 自己的 css-vars 盖掉。
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import './styles/tokens.css'
import './styles/base.css'

import router from './router'
import App from './App.vue'

createApp(App).use(ElementPlus, { locale: zhCn }).use(router).mount('#app')
