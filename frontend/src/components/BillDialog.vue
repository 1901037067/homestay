<template>
  <el-dialog v-model="visible" :title="bill && bill.snapshot ? '账单快照' : '费用试算'" width="620">
    <div v-loading="loading">
      <template v-if="bill">
        <div class="bill-head">
          <div>
            <div class="bill-total bnb-num">¥{{ money(bill.totalAmount) }}</div>
            <div class="bnb-muted bill-sub">
              {{ bill.roomNo }} · {{ bill.guestName }} · {{ fmtLocal(bill.calcStart) }} →
              {{ fmtLocal(bill.calcEnd) }}
            </div>
          </div>
          <span class="bnb-pill" :class="bill.snapshot ? 'bnb-pill--success' : 'bnb-pill--warning'">
            <span class="bnb-pill__dot" />{{ bill.snapshot ? '已结算快照' : '实时试算' }}
          </span>
        </div>

        <el-alert
          v-if="bill.notice"
          :title="bill.notice"
          type="info"
          :closable="false"
          show-icon
          class="bill-notice"
        />

        <el-table :data="bill.lines" size="small" class="bill-table">
          <el-table-column prop="label" label="项目" min-width="190" />
          <el-table-column label="时长" width="90" align="right">
            <template #default="{ row }">
              <span class="bnb-num">{{ humanMinutes(row.chargeMinutes) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="单价" width="110" align="right">
            <template #default="{ row }">
              <span class="bnb-num">¥{{ money(row.unitPrice) }}</span>
              <span class="bnb-muted unit-hint">{{ unitSuffix(row.unitHint) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="规则" min-width="110">
            <template #default="{ row }">
              <el-tag v-if="row.ruleName" size="small" effect="plain">{{ row.ruleName }}</el-tag>
              <span v-else class="bnb-muted">平时价</span>
            </template>
          </el-table-column>
          <el-table-column label="金额" width="100" align="right">
            <template #default="{ row }">
              <span class="bnb-num strong">¥{{ money(row.amount) }}</span>
            </template>
          </el-table-column>
        </el-table>

        <div class="bill-foot bnb-muted">
          起步 ¥{{ money(bill.baseAmount) }} + 超时 ¥{{ money(bill.overageAmount) }}
          · 超时按 {{ bill.chargeUnitMinutes }} 分钟为一档向上取整
          <template v-if="bill.snapshot">
            · 结算于 {{ fmtLocal(bill.settledAt) }}{{ bill.operatorName ? ' · ' + bill.operatorName : '' }}
          </template>
        </div>
      </template>
    </div>

    <template #footer>
      <div class="dlg-actions">
        <el-button @click="visible = false">关闭</el-button>
        <el-button v-if="bill && !bill.snapshot" plain :loading="loading" @click="load">
          按最新规则重算
        </el-button>
        <!-- 结算就在这张单上看完了再点，省得回到列表里再找一次 -->
        <el-button v-if="bill && bill.status === 'CHECKED_OUT'" type="primary" :loading="settling" @click="settle">
          结算收款
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api'
import { humanMinutes } from '../utils/dtime'

const emit = defineEmits(['settled'])
const visible = defineModel({ type: Boolean })

const bill = ref(null)
const loading = ref(false)
const settling = ref(false)
const bookingId = ref(null)

const money = (v) => (v === null || typeof v === 'undefined' ? '—' : Number(v).toFixed(2))

/**
 * 后端给的是完整的量纲（"打包" / "元/晚" / "元/小时"），这里只把它压成后缀。
 *
 * 原来这一列写死 `/h`：连住一晚一行的账单会显示成"¥388 /h"，客人第一眼就以为
 * 388 是小时价 —— 而这一列存在的唯一理由就是防止把打包价误读成小时价。
 * 兜底分支保留 hint 原文，后端将来加新量纲也不会静默退回 `/h`。
 */
function unitSuffix(hint) {
  if (!hint || hint === '打包') return ''
  return hint.startsWith('元/') ? `/${hint.slice(2)}` : `/${hint}`
}
const fmtLocal = (s) => (s ? `${s.slice(5, 10)} ${s.slice(11, 16)}` : '—')

// 连着点两张单时，前一张的响应可能后到：用序号把它丢掉，
// 否则界面上会出现"A 的金额、B 的单号"，点结算就成了给 B 收 A 的钱
let loadSeq = 0
let settledSeq = 0

async function load() {
  if (!bookingId.value) return
  const seq = ++loadSeq
  loading.value = true
  try {
    const res = (await http.get(`/bookings/${bookingId.value}/bill`)).data
    if (seq !== loadSeq) return
    bill.value = res
  } catch {
    if (seq === loadSeq) bill.value = null
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

async function open(id) {
  bookingId.value = id
  bill.value = null
  visible.value = true
  await load()
}

async function settle() {
  const seq = ++settledSeq
  settling.value = true
  try {
    await http.post(`/bookings/${bookingId.value}/settle`)
    if (seq !== settledSeq) return
    ElMessage.success('已结算')
    await load()          // 读回来的一定是快照，而不是刚才那份试算
    emit('settled')
  } catch {
    /* 拦截器已提示 */
  } finally {
    settling.value = false
  }
}

defineExpose({ open })
</script>

<style scoped>
.bill-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--bnb-space-4);
  margin-bottom: var(--bnb-space-4);
}
.bill-total {
  font-size: 34px;
  font-weight: 600;
  letter-spacing: -0.02em;
  line-height: 1.1;
}
.bill-sub {
  margin-top: 2px;
  font-size: var(--bnb-fs-footnote);
}
.bill-notice {
  margin-bottom: var(--bnb-space-3);
}
.bill-table {
  margin-bottom: var(--bnb-space-3);
}
.unit-hint {
  font-size: var(--bnb-fs-footnote);
}
.strong {
  font-weight: 600;
}
.bill-foot {
  font-size: var(--bnb-fs-footnote);
  line-height: 1.5;
}
.dlg-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--bnb-space-2);
}
</style>
