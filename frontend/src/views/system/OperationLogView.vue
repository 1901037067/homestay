<template>
  <div class="bnb-page">
    <div class="bnb-page__head">
      <div class="bnb-page__title">操作日志</div>
      <el-button :loading="loading" @click="load">
        <el-icon><Refresh /></el-icon>刷新
      </el-button>
    </div>

    <div class="bnb-card">
      <div class="bnb-card__head">
        <div class="bnb-toolbar">
          <el-select v-model="query.action" placeholder="全部动作" clearable style="width: 190px" @change="reload">
            <el-option v-for="a in ACTIONS" :key="a.value" :label="a.label" :value="a.value" />
          </el-select>
          <!-- 这里原来是写死的 admin / staff 两项。账号体系早就能建 front01 这类新账号，
               按操作人筛选却选不到他们 —— 而且 /api/users 是店长专属，前台看日志会吃 1003，
               所以用输入框而不是拉账号列表 -->
          <el-input
            v-model="query.username"
            placeholder="操作人用户名"
            clearable
            style="width: 150px"
            @keyup.enter="reload"
            @clear="reload"
          />
        </div>
        <span class="bnb-muted count">共 {{ total }} 条</span>
      </div>

      <el-table v-loading="loading" :data="rows" style="width: 100%">
        <el-table-column label="时间" width="160">
          <template #default="{ row }">
            <span class="bnb-num">{{ fmtTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作人" width="120">
          <template #default="{ row }">
            <span v-if="row.username">{{ row.username }}</span>
            <span v-else class="bnb-muted">系统</span>
          </template>
        </el-table-column>
        <el-table-column label="动作" width="170">
          <template #default="{ row }">
            <span class="bnb-pill" :class="pillClass(row.action)">{{ actionLabel(row.action) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="summary" label="内容" min-width="280" />
        <el-table-column label="前后值" width="90" align="right">
          <template #default="{ row }">
            <el-button v-if="row.beforeJson || row.afterJson" text @click="showDiff(row)">查看</el-button>
            <span v-else class="bnb-muted">—</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="还没有操作记录" />
        </template>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="query.page"
          :page-size="query.size"
          :total="total"
          layout="prev, pager, next"
          background
          @current-change="load"
        />
      </div>
    </div>

    <el-dialog v-model="diffDlg" title="变更详情" width="620">
      <div class="diff">
        <div>
          <h4>变更前</h4>
          <pre>{{ pretty(diffRow?.beforeJson) }}</pre>
        </div>
        <div>
          <h4>变更后</h4>
          <pre>{{ pretty(diffRow?.afterJson) }}</pre>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import http from '../../api'

const ACTIONS = [
  { value: 'BOOKING_CREATE', label: '开单' },
  { value: 'BOOKING_CONFIRM', label: '确认预占' },
  { value: 'BOOKING_CHECK_IN', label: '入住' },
  { value: 'BOOKING_CHECK_OUT', label: '退房' },
  { value: 'BOOKING_EXTEND', label: '加时' },
  { value: 'BOOKING_ADD_NIGHT', label: '加一晚' },
  { value: 'BOOKING_RESCHEDULE', label: '改期' },
  { value: 'BOOKING_CANCEL', label: '取消' },
  { value: 'CLEANING_DONE', label: '清扫完成' },
  { value: 'BOOKING_SETTLE', label: '结算' },
  // 登录审计：成功与失败都留痕，失败那条的摘要会写清是"账号不存在 / 密码错误 / 已停用"
  { value: 'LOGIN_SUCCESS', label: '登录成功' },
  { value: 'LOGIN_FAILED', label: '登录失败' },
  { value: 'USER_PASSWORD_CHANGE', label: '改自己的密码' },
  // 改价会直接改变账单金额，是最该被审计的动作之一
  { value: 'ROOM_TYPE_CREATE', label: '新建房型' },
  { value: 'ROOM_TYPE_UPDATE', label: '房型改价' },
  { value: 'RATE_RULE_CREATE', label: '新建价格规则' },
  { value: 'RATE_RULE_UPDATE', label: '修改价格规则' },
  { value: 'RATE_RULE_DELETE', label: '删除价格规则' },
  { value: 'ROOM_STATUS', label: '房间状态变更' }
]

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const diffDlg = ref(false)
const diffRow = ref(null)
const query = reactive({ page: 1, size: 20, action: null, username: null })

const actionLabel = (a) => ACTIONS.find(x => x.value === a)?.label || a

function pillClass(a) {
  if (a === 'BOOKING_CANCEL') return 'bnb-pill--danger'
  if (a === 'BOOKING_CHECK_OUT' || a === 'CLEANING_DONE') return 'bnb-pill--warning'
  if (a === 'BOOKING_CHECK_IN') return 'bnb-pill--success'
  return 'bnb-pill--accent'
}

function fmtTime(s) {
  return s ? s.replace('T', ' ').slice(0, 19) : '—'
}

function pretty(json) {
  if (!json) return '—'
  try {
    return JSON.stringify(JSON.parse(json), null, 2)
  } catch {
    return json
  }
}

function showDiff(row) {
  diffRow.value = row
  diffDlg.value = true
}

async function load() {
  loading.value = true
  try {
    const params = new URLSearchParams({ page: query.page, size: query.size })
    if (query.action) params.set('action', query.action)
    if (query.username) params.set('username', query.username)
    const res = await http.get(`/operation-logs?${params.toString()}`)
    rows.value = res.data.records
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

function reload() {
  query.page = 1
  load()
}

onMounted(load)
</script>

<style scoped>
.count {
  font-size: var(--bnb-fs-callout);
}
.pager {
  padding: var(--bnb-space-3) var(--bnb-space-6) var(--bnb-space-5);
}
.diff {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--bnb-space-4);
}
.diff h4 {
  font-size: var(--bnb-fs-callout);
  color: var(--bnb-text-secondary);
  margin-bottom: var(--bnb-space-2);
}
.diff pre {
  margin: 0;
  padding: var(--bnb-space-3);
  border-radius: var(--bnb-radius-sm);
  background: var(--bnb-bg-inset);
  font-size: var(--bnb-fs-footnote);
  line-height: 1.6;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
