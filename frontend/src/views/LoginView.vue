<template>
  <div class="wrap">
    <div class="theme-switch">
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
    </div>

    <div class="panel">
      <div class="mark"><el-icon :size="26"><House /></el-icon></div>
      <h1>民宿分时预订系统</h1>
      <p class="sub">前台管理端 · 单机部署</p>

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent>
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" size="large" autocomplete="username" placeholder="admin" />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            size="large"
            type="password"
            autocomplete="current-password"
            show-password
            placeholder="请输入密码"
            @keyup.enter="submit"
          />
        </el-form-item>

        <el-button type="primary" size="large" class="submit" :loading="loading" @click="submit">
          登录
        </el-button>
      </el-form>

      <p class="hint">演示账号 admin / Admin@123</p>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { House } from '@element-plus/icons-vue'
import http from '../api'
import { THEME_MODES, useTheme } from '../composables/useTheme'

const router = useRouter()
const { mode, setMode } = useTheme()
const formRef = ref()
const loading = ref(false)
const form = reactive({ username: 'admin', password: '' })
const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

async function submit() {
  try {
    await formRef.value.validate()
  } catch {
    return // 校验失败时不要发请求，否则会看到两个错误提示叠在一起
  }
  loading.value = true
  try {
    const res = await http.post('/login', { ...form })
    localStorage.setItem('bnb_user', JSON.stringify(res.data))
    ElMessage.success(`欢迎回来，${res.data.displayName}`)
    router.push('/')
  } catch {
    /* api.js 拦截器已经提示过 */
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.wrap {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: var(--bnb-space-6);
  background:
    radial-gradient(1100px 620px at 50% -12%, var(--bnb-accent-soft), transparent 62%),
    var(--bnb-bg-page);
}

.theme-switch {
  position: fixed;
  top: var(--bnb-space-5);
  right: var(--bnb-space-5);
}

.panel {
  width: 100%;
  max-width: 400px;
  padding: var(--bnb-space-10) var(--bnb-space-8);
  background: var(--bnb-bg-card);
  border: 1px solid var(--bnb-hairline);
  border-radius: var(--bnb-radius-xl);
  box-shadow: var(--bnb-shadow-pop);
}

.mark {
  display: grid;
  place-items: center;
  width: 54px;
  height: 54px;
  margin-bottom: var(--bnb-space-5);
  border-radius: var(--bnb-radius-md);
  background: var(--bnb-accent);
  color: var(--bnb-accent-on);
}

h1 {
  font-size: var(--bnb-fs-title1);
  letter-spacing: var(--bnb-ls-display);
}

.sub {
  margin: 6px 0 var(--bnb-space-8);
  font-size: var(--bnb-fs-callout);
  color: var(--bnb-text-secondary);
}

.submit {
  width: 100%;
  height: 48px;
  margin-top: var(--bnb-space-2);
  border-radius: var(--bnb-radius-pill);
  font-size: var(--bnb-fs-body);
}

.hint {
  margin-top: var(--bnb-space-6);
  text-align: center;
  font-size: var(--bnb-fs-footnote);
  color: var(--bnb-text-tertiary);
}

:deep(.el-form-item__label) {
  font-size: var(--bnb-fs-callout);
  font-weight: 500;
  color: var(--bnb-text-secondary);
}
</style>
