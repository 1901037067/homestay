<template>
  <div class="bnb-page">
    <div class="bnb-page__head">
      <div class="bnb-page__title">订单</div>
      <el-button :loading="loading" @click="load">
        <el-icon><Refresh /></el-icon>刷新
      </el-button>
    </div>

    <div class="bnb-card">
      <div class="bnb-card__head">
        <div class="bnb-toolbar">
          <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 170px" @change="reload">
            <el-option v-for="s in STATUSES" :key="s.value" :label="s.label" :value="s.value" />
          </el-select>
          <!-- 筛的是「入住时刻」，不是"这几天在住" —— 后端 from/to 就是打在 start_time 上的半开区间 -->
          <el-date-picker
            v-model="query.range"
            type="daterange"
            style="width: 240px; flex: 0 0 240px"
            range-separator="→"
            start-placeholder="入住起"
            end-placeholder="入住止"
            value-format="YYYY-MM-DD"
            unlink-panels
            @change="reload"
          />
          <el-input
            v-model="query.keyword"
            placeholder="客人或单号"
            clearable
            style="width: 170px"
            @keyup.enter="reload"
            @clear="reload"
          />
          <button class="bnb-segmented__item" :class="{ 'is-active': pendingOnly }" @click="togglePending">
            只看待结算
          </button>
        </div>
        <span class="bnb-muted count">共 {{ total }} 单</span>
      </div>

      <el-table v-loading="loading" :data="rows" style="width: 100%">
        <el-table-column label="单号" min-width="150">
          <template #default="{ row }"><span class="bnb-num">{{ row.bookingNo }}</span></template>
        </el-table-column>
        <el-table-column label="房间" width="90">
          <template #default="{ row }">
            <span class="bnb-num">{{ row.roomNo }}</span>
            <span class="bnb-muted prop">{{ row.propertyName }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="guestName" label="客人" min-width="90" />
        <el-table-column label="售卖" width="60">
          <template #default="{ row }">
            <span class="bnb-muted">{{ row.saleType === 'FULL_DAY' ? '全日' : '钟点' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="业务时段" min-width="180">
          <template #default="{ row }">
            <span class="bnb-num">{{ fmtLocal(row.startTime) }} → {{ fmtLocal(row.endTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="实际退房" min-width="120">
          <template #default="{ row }">
            <span v-if="row.actualCheckOut" class="bnb-num" :class="{ late: isLate(row) }">
              {{ fmtLocal(row.actualCheckOut) }}
            </span>
            <span v-else class="bnb-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <span class="bnb-pill" :class="pillClass(row.status)">
              <span class="bnb-pill__dot" />{{ statusLabel(row.status) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="金额" width="90" align="right">
          <template #default="{ row }">
            <span v-if="Number(row.amount) > 0" class="bnb-num">¥{{ money(row.amount) }}</span>
            <span v-else class="bnb-muted">未结算</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" align="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 'CONFIRMED'" text @click="act(row, 'check-in')">入住</el-button>
            <el-button v-if="row.status === 'IN_USE'" text @click="act(row, 'check-out')">退房</el-button>
            <el-button text @click="openBill(row)">账单</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="没有符合条件的订单" />
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

    <BillDialog ref="billRef" v-model="billDlg" @settled="load" />
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import http from '../../api'
import BillDialog from '../../components/BillDialog.vue'

const STATUSES = [
  { value: 'HELD', label: '预占' },
  { value: 'CONFIRMED', label: '已确认' },
  { value: 'IN_USE', label: '在住' },
  { value: 'CHECKED_OUT', label: '已退房' },
  { value: 'SETTLED', label: '已结算' },
  { value: 'CANCELLED', label: '已取消' },
  { value: 'RELEASED', label: '已释放' }
]
const statusLabel = (s) => STATUSES.find((x) => x.value === s)?.label || s

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const billDlg = ref(false)
const billRef = ref()
const pendingOnly = ref(false)
const query = reactive({ page: 1, size: 20, status: null, keyword: '', range: null })

/** 'YYYY-MM-DD' → 次日零点。把"选中的那两天"变成半开区间 [起, 次日 00:00)，与后端口径一致 */
function nextDay(day) {
  const [y, m, d] = day.split('-').map(Number)
  const t = new Date(y, m - 1, d + 1)
  const p = (n) => String(n).padStart(2, '0')
  return `${t.getFullYear()}-${p(t.getMonth() + 1)}-${p(t.getDate())}`
}

const money = (v) => Number(v).toFixed(2)
const fmtLocal = (s) => (s ? `${s.slice(5, 10)} ${s.slice(11, 16)}` : '—')

/** 实际退房晚于计划离店 —— 这一列要变红，它是"要补超时费"的信号，不是错误 */
const isLate = (row) => row.actualCheckOut && row.actualCheckOut > row.endTime

function pillClass(s) {
  if (s === 'CHECKED_OUT') return 'bnb-pill--warning'
  if (s === 'IN_USE') return 'bnb-pill--success'
  if (s === 'SETTLED') return 'bnb-pill--accent'
  if (s === 'CANCELLED' || s === 'RELEASED') return 'bnb-pill--danger'
  return 'bnb-pill'
}

function togglePending() {
  pendingOnly.value = !pendingOnly.value
  // "只看待结算"就是 status=CHECKED_OUT，借这一个筛选把两个入口的关系说清楚，
  // 不再另加一个后端参数
  query.status = pendingOnly.value ? 'CHECKED_OUT' : null
  reload()
}

async function load() {
  loading.value = true
  try {
    const params = new URLSearchParams({ page: query.page, size: query.size })
    if (query.status) params.set('status', query.status)
    if (query.keyword) params.set('keyword', query.keyword)
    if (query.range?.length === 2) {
      params.set('from', `${query.range[0]}T00:00:00`)
      params.set('to', `${nextDay(query.range[1])}T00:00:00`)
    }
    const res = await http.get(`/bookings?${params.toString()}`)
    rows.value = res.data.records
    total.value = res.data.total
    pendingOnly.value = query.status === 'CHECKED_OUT'
  } finally {
    loading.value = false
  }
}

function reload() {
  query.page = 1
  load()
}

function openBill(row) {
  billDlg.value = true
  billRef.value.open(row.id)
}

/**
 * 入住/退房也放在这里，不只是日历的附庸：
 * 昨天该退房却没退的客人，他的占用块整段落在今天窗口之左，
 * 日历上只剩左缘一小条；而"改天再来办"是前台真实会做的事。
 */
async function act(row, cmd) {
  try {
    await http.post(`/bookings/${row.id}/${cmd}`)
    ElMessage.success(cmd === 'check-in' ? '已入住' : '已退房')
    await load()
  } catch {
    /* 拦截器已提示 */
  }
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
.prop {
  display: block;
  font-size: var(--bnb-fs-footnote);
}
.late {
  color: var(--bnb-danger);
  font-weight: 500;
}
/* 这一枚筛选开关和旁边的 el-select / el-input 排在同一条工具栏里，
   尺寸必须按 Element 的控件档位对齐（--el-component-size=40px），
   否则同一行会出现"两个高盒子夹一个矮胶囊"的错位 */
.bnb-toolbar button.bnb-segmented__item {
  height: var(--el-component-size);
  padding: 0 16px;
  border: 1px solid var(--el-border-color);
  border-radius: var(--bnb-radius-sm);
  background: var(--el-fill-color-blank);
  color: var(--el-text-color-regular);
  font-size: var(--el-font-size-base);
  cursor: pointer;
}

.bnb-toolbar button.bnb-segmented__item.is-active {
  border-color: var(--bnb-accent);
  background: color-mix(in srgb, var(--bnb-accent) 10%, transparent);
  color: var(--bnb-accent);
  box-shadow: none;
}
</style>
