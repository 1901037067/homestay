<template>
  <div class="bnb-page">
    <div class="bnb-page__head">
      <div class="bnb-page__title">账号</div>
      <el-button type="primary" @click="openCreate">
        <el-icon><Plus /></el-icon>新建账号
      </el-button>
    </div>

    <div class="bnb-card">
      <el-table v-loading="loading" :data="rows" style="width: 100%">
        <el-table-column label="用户名" min-width="140">
          <template #default="{ row }">
            <span class="bnb-num">{{ row.username }}</span>
            <span v-if="row.id === meId" class="bnb-muted you">（当前登录）</span>
          </template>
        </el-table-column>
        <el-table-column prop="displayName" label="显示名" min-width="120" />
        <el-table-column label="角色" min-width="180">
          <template #default="{ row }">
            <span class="bnb-pill" :class="row.manager ? 'bnb-pill--info' : 'bnb-pill--success'">
              <span class="bnb-pill__dot" />{{ row.manager ? '店长 · 全部门店' : row.propertyName + ' 前台' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <span class="bnb-pill" :class="row.enabled ? 'bnb-pill--success' : 'bnb-pill--danger'">
              <span class="bnb-pill__dot" />{{ row.enabled ? '可用' : '已停用' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" align="right" fixed="right">
          <template #default="{ row }">
            <el-button text @click="openEdit(row)">编辑</el-button>
            <el-button v-if="row.enabled" text class="danger" @click="toggle(row, false)">停用</el-button>
            <el-button v-else text @click="toggle(row, true)">启用</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="还没有账号" />
        </template>
      </el-table>
      <div class="note bnb-muted">
        账号没有"删除"，只有停用 —— 操作日志里的操作人只记了 user_id，删号会让历史审计指向幽灵用户。
        停用对已登录的人<b>立即生效</b>：每个请求都会回读一次账号，下一次点击就是 401。
      </div>
    </div>

    <el-dialog v-model="dialog" :title="form.id ? '编辑账号' : '新建账号'" width="480">
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="front01" autocomplete="off" />
        </el-form-item>
        <el-form-item label="显示名" prop="displayName">
          <el-input v-model="form.displayName" placeholder="前台小王" />
        </el-form-item>
        <el-form-item :label="form.id ? '重置密码（留空则不改）' : '初始密码'">
          <el-input v-model="form.password" type="password" show-password autocomplete="new-password"
                    placeholder="至少 8 位" />
        </el-form-item>
        <el-form-item label="归属民宿">
          <el-select v-model="form.propertyId" placeholder="留空 = 店长（看全部门店）" clearable style="width: 100%">
            <el-option v-for="p in properties" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
          <div class="tip">
            留空就是店长：能看全部门店，也能进这一页管账号。选了某家民宿，这个人登录后只看得到那一家 ——
            别的店的房间、订单、报表和操作日志都不会出现在他的界面里。
          </div>
        </el-form-item>
        <el-form-item label="可用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import http from '../../api'

const rows = ref([])
const properties = ref([])
const loading = ref(false)
const saving = ref(false)
const dialog = ref(false)
const formRef = ref()
const form = reactive(empty())
const meId = ref(null)

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  displayName: [{ required: true, message: '请输入显示名', trigger: 'blur' }]
}

function empty() {
  return { id: null, username: '', displayName: '', password: '', propertyId: null, enabled: true }
}

async function load() {
  loading.value = true
  try {
    rows.value = (await http.get('/users')).data
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  meId.value = JSON.parse(localStorage.getItem('bnb_user') || 'null')?.id ?? null
  properties.value = (await http.get('/properties')).data
  await load()
})

function openCreate() {
  Object.assign(form, empty())
  dialog.value = true
}

function openEdit(row) {
  // 先回空表单再合并：后端配了 non_null，null 字段会整个从 JSON 里消失，
  // 不先清空就会把上一次编辑残留的值带进这一条
  Object.assign(form, empty())
  Object.assign(form, {
    id: row.id,
    username: row.username,
    displayName: row.displayName,
    propertyId: row.propertyId ?? null,
    enabled: row.enabled
  })
  dialog.value = true
}

async function save() {
  await formRef.value.validate()
  if (!form.id && !form.password) {
    ElMessage.warning('新建账号必须设置初始密码')
    return
  }
  saving.value = true
  try {
    const body = {
      username: form.username,
      displayName: form.displayName,
      password: form.password || null,
      propertyId: form.propertyId || null,
      enabled: form.enabled
    }
    if (form.id) {
      await http.put(`/users/${form.id}`, body)
    } else {
      await http.post('/users', body)
    }
    dialog.value = false
    ElMessage.success('已保存')
    await load()
  } finally {
    saving.value = false
  }
}

/** 停用/启用走同一个 PUT，只是把 enabled 翻一下 —— 后端把"不能停用自己""不能没有店长"两道守卫放在服务层 */
async function toggle(row, enabled) {
  await http.put(`/users/${row.id}`, {
    username: row.username,
    displayName: row.displayName,
    propertyId: row.propertyId ?? null,
    enabled
  })
  ElMessage.success(enabled ? '已启用' : '已停用')
  await load()
}
</script>

<style scoped>
.you {
  font-size: var(--bnb-fs-caption);
}

.note {
  padding: var(--bnb-space-3) var(--bnb-space-2) 0;
  font-size: var(--bnb-fs-footnote);
  line-height: 1.6;
}

.tip {
  font-size: var(--bnb-fs-caption);
  color: var(--bnb-text-secondary);
  line-height: 1.5;
  margin-top: var(--bnb-space-1);
}
</style>
