import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 用 dev proxy 而不是 CORS：前后端在浏览器眼里同源，JSESSIONID 才能正常带着走，
// 后端也不用为此开放 CORS 白名单。
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
