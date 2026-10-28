import axios from 'axios'
import { ElMessage } from 'element-plus'

const http = axios.create({
  baseURL: '/api',
  withCredentials: true,
  timeout: 15000
})

http.interceptors.response.use(
  (res) => {
    const body = res.data
    if (body && typeof body.code !== 'undefined' && body.code !== 0) {
      ElMessage.error(body.message || '请求失败')
      return Promise.reject(new Error(body.message))
    }
    return body
  },
  (err) => {
    if (err.response && err.response.status === 401) {
      ElMessage.warning('请先登录')
      window.location.hash = '#/login'
    } else if (err.response) {
      ElMessage.error(err.response.data?.message || `HTTP ${err.response.status}`)
    } else {
      ElMessage.error('后端不可达，确认 8080 已启动')
    }
    return Promise.reject(err)
  }
)

export default http
