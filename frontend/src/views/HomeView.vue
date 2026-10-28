<template>
  <el-container class="wrap">
    <el-header class="hd">
      <span class="title">民宿分时预订系统</span>
      <span class="who">{{ user?.displayName }}（{{ user?.username }}）</span>
      <el-button size="small" text @click="logout">退出</el-button>
    </el-header>

    <el-main>
      <el-alert
        v-if="health"
        title="W1 骨架已跑通：前端 → dev proxy → 后端 → Flyway → MySQL 全链路可达"
        type="success"
        :closable="false"
        show-icon
      />
      <el-descriptions v-if="health" :column="2" border class="desc">
        <el-descriptions-item label="后端时间">{{ health.time }}</el-descriptions-item>
        <el-descriptions-item label="property 表行数">{{ health.properties }}</el-descriptions-item>
      </el-descriptions>

      <el-empty description="房源域 CRUD 在 W2 落地，本屏只证明链路通" />
    </el-main>
  </el-container>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import http from '../api'

const router = useRouter()
const user = ref(JSON.parse(localStorage.getItem('bnb_user') || 'null'))
const health = ref(null)

onMounted(async () => {
  const me = await http.get('/me')
  user.value = me.data
  const ping = await http.get('/ping')
  health.value = ping.data
})

async function logout() {
  await http.get('/logout')
  localStorage.removeItem('bnb_user')
  router.push('/login')
}
</script>

<style scoped>
.wrap {
  min-height: 100vh;
}
.hd {
  display: flex;
  align-items: center;
  gap: 16px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
}
.title {
  font-weight: 600;
}
.who {
  margin-left: auto;
  color: #606266;
  font-size: 13px;
}
.desc {
  margin-top: 16px;
}
</style>
