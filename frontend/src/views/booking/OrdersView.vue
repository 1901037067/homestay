<template>
  <div class="bnb-page">
    <div class="bnb-page__head">
      <div>
        <div class="bnb-page__title">订单</div>
        <div class="bnb-page__sub">
          房态日历只看今天，这里看所有 —— 退房后没来得及结算的单，靠这一页找回
        </div>
      </div>
      <el-button :loading="loading" @click="load">
        <el-icon><Refresh /></el-icon>刷新
      </el-button>
    </div>

    <div class="bnb-card">
      <div class="bnb-card__head">
        <div class="bnb-toolbar">
          <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 150px" @change="reload">
            <el-option v-for="s in STATUSES" :key="s.value" :label="s.label" :value="s.value" />
          </el-select>
          <el-input
            v-model="query.keyword"
            placeholder="客人或单号"
            clearable
            style="width: 200px"
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
const query = reactive({ page: 1, size: 20, status: null, keyword: '' })

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
.bnb-toolbar button.bnb-segmented__item {
  border: 1px solid var(--el-border-color);
  cursor: pointer;
  font: inherit;
}
</style>
