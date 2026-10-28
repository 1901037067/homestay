<template>
  <div class="bnb-page">
    <div class="bnb-page__head">
      <div>
        <div class="bnb-page__title">房态日历</div>
        <div class="bnb-page__sub">
          纵轴房间 × 横轴 24 小时，横轴从营业日切割点起转；点空白格直接开单
        </div>
      </div>
      <el-button :loading="loading" @click="load">
        <el-icon><Refresh /></el-icon>刷新
      </el-button>
    </div>

    <div class="bnb-card bar">
      <div class="bnb-toolbar">
        <el-button-group>
          <el-button @click="shiftDay(-1)"><el-icon><ArrowLeft /></el-icon></el-button>
          <el-button @click="goToday">今天</el-button>
          <el-button @click="shiftDay(1)"><el-icon><ArrowRight /></el-icon></el-button>
        </el-button-group>
        <el-date-picker
          v-model="date"
          type="date"
          value-format="YYYY-MM-DD"
          :clearable="false"
          style="width: 150px"
          @change="load"
        />
        <el-select v-model="propertyId" placeholder="选择民宿" style="width: 170px" @change="load">
          <el-option v-for="p in properties" :key="p.id" :label="p.name" :value="p.id" />
        </el-select>
      </div>

      <div class="legend">
        <span class="legend__item"><i class="sw sw--held" />预占</span>
        <span class="legend__item"><i class="sw sw--confirmed" />已确认</span>
        <span class="legend__item"><i class="sw sw--inuse" />在住</span>
        <span class="legend__item"><i class="sw sw--overstay" />超时未退房</span>
        <span class="legend__item"><i class="sw sw--cleaning" />待清扫（点一下释放）</span>
        <span class="legend__item"><i class="sw sw--free" />空闲（可点击开单）</span>
      </div>
    </div>

    <div v-if="cal" class="bnb-card grid-card">
      <div class="grid">
        <!-- 时间刻度 -->
        <div class="grid__corner">
          <span class="bnb-muted">房间</span>
        </div>
        <div class="scale">
          <div v-for="h in 24" :key="h" class="scale__cell">
            <span v-if="(h + dayCutHour) % 2 === 1" class="scale__label">
              {{ pad((h - 1 + dayCutHour) % 24) }}
            </span>
          </div>
        </div>

        <!-- 每间房一行 -->
        <template v-for="room in cal.rooms" :key="room.roomId">
          <div class="grid__label" :class="{ 'is-dim': room.status !== 'ON_SALE' }">
            <div class="grid__roomno">{{ room.roomNo }}</div>
            <div class="grid__type">{{ room.roomTypeName || '未配房型' }}</div>
            <span v-if="room.status !== 'ON_SALE'" class="bnb-pill" :class="room.status === 'MAINTENANCE' ? 'bnb-pill--warning' : ''">
              {{ room.status === 'MAINTENANCE' ? '维修中' : '已下架' }}
            </span>
          </div>

          <div class="track" :class="{ 'is-dim': room.status !== 'ON_SALE' }">
            <div
              v-for="h in 24"
              :key="h"
              class="track__cell"
              :class="{ 'is-blocked': !cellSellable(room, h - 1) }"
              @click="onCellClick(room, h - 1)"
            />
            <div
              v-for="(b, i) in room.blocks"
              :key="`${room.roomId}-${i}`"
              class="blk"
              :class="blkClass(b)"
              :style="blkStyle(b)"
              @click.stop="openDetail(b)"
            >
              <el-tooltip :content="tip(b)" placement="top" :show-after="180">
                <div class="blk__inner">
                  <span class="blk__guest">{{ b.guestName }}</span>
                  <span class="blk__time">{{ rangeText(b) }}</span>
                </div>
              </el-tooltip>
            </div>
            <div v-if="nowOffset !== null" class="now" :style="{ left: nowOffset + '%' }" />
          </div>
        </template>
      </div>

      <p class="foot bnb-muted">
        窗口 {{ cal.windowStart.replace('T', ' ') }} → {{ cal.windowEnd.replace('T', ' ') }}
        （切割点 {{ cal.dayCutTime }}）。块上画的是<b>含清扫缓冲</b>的 block 区间，
        所以一段没人住的空档也可能是不可售的。
      </p>
    </div>

    <el-empty v-else description="请选择一家民宿" />

    <!-- 开单 -->
    <el-dialog v-model="bookDlg" title="开单" width="480">
      <div v-if="form.room" class="form-room">
        <strong>{{ form.room.roomNo }}</strong>
        <span class="bnb-muted">{{ form.room.roomTypeName }}</span>
      </div>
      <el-form label-position="top">
        <el-form-item label="方式">
          <div class="bnb-segmented" role="group" aria-label="开单方式">
            <button
              type="button"
              class="bnb-segmented__item"
              :class="{ 'is-active': form.mode === 'hourly' }"
              @click="setMode('hourly')"
            >按小时</button>
            <button
              v-if="form.room?.fullDayStart"
              type="button"
              class="bnb-segmented__item"
              :class="{ 'is-active': form.mode === 'fullday' }"
              @click="setMode('fullday')"
            >全日房</button>
          </div>
        </el-form-item>

        <div class="pair">
          <el-form-item label="开始">
            <el-time-select
              v-model="form.start"
              :clearable="false"
              start="00:00"
              step="01:00"
              end="23:00"
              style="width: 100%"
              :disabled="form.mode === 'fullday'"
            />
          </el-form-item>
          <el-form-item v-if="form.mode === 'hourly'" label="时长">
            <el-select v-model="form.hours" style="width: 100%">
              <el-option v-for="h in [1, 2, 3, 4, 6, 8, 10, 12]" :key="h" :label="`${h} 小时`" :value="h" />
            </el-select>
          </el-form-item>
        </div>

        <div class="preview">
          {{ previewText }}
          <span v-if="form.mode === 'fullday'" class="bnb-muted">（次日结束）</span>
        </div>

        <el-form-item label="客人姓名" required>
          <el-input v-model="form.guest" placeholder="张三" />
        </el-form-item>
        <el-form-item label="电话">
          <el-input v-model="form.phone" placeholder="选填" />
        </el-form-item>
      </el-form>

      <!-- 冲突要落在弹窗里，只弹个 toast 前台会以为"没保存上"而反复点 -->
      <el-alert v-if="formError" :title="formError" type="error" :closable="false" show-icon class="err" />

      <template #footer>
        <el-button @click="bookDlg = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">确认开单</el-button>
      </template>
    </el-dialog>

    <!-- 占用详情与流转操作 -->
    <el-dialog v-model="detailDlg" title="占用详情" width="460">
      <el-descriptions v-if="detail" :column="1" border size="small">
        <el-descriptions-item label="订单号">{{ detail.bookingNo }}</el-descriptions-item>
        <el-descriptions-item label="客人">{{ detail.guestName }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <span class="bnb-pill" :class="blkClass(detail)">
            <span class="bnb-pill__dot" />{{ statusLabel(detail.bookingStatus) }}
          </span>
          <span v-if="detail.kind === 'CLEANING'" class="bnb-pill bnb-pill--warning">
            <span class="bnb-pill__dot" />待清扫
          </span>
        </el-descriptions-item>
        <el-descriptions-item label="业务时段">
          {{ fmtLocal(detail.startTime) }} → {{ fmtLocal(detail.endTime) }}
        </el-descriptions-item>
        <el-descriptions-item label="含缓冲占用">
          {{ fmtLocal(detail.blockStart) }} → {{ fmtLocal(detail.blockEnd) }}
        </el-descriptions-item>
      </el-descriptions>

      <!-- 改期 / 加时：改完就地显示新的时段预览，别让人盲填 -->
      <div v-if="editing" class="edit-box">
        <el-date-picker
          v-model="editStart"
          type="datetime"
          value-format="YYYY-MM-DDTHH:mm:ss"
          placeholder="开始"
          :disabled="editing === 'extend'"
        />
        <el-date-picker
          v-model="editEnd"
          type="datetime"
          value-format="YYYY-MM-DDTHH:mm:ss"
          placeholder="结束"
        />
        <p class="edit-hint bnb-muted">
          {{ editing === 'extend' ? '加时只能往后推，且需重跑冲突检测' : '改期会删旧占用行后重新校验' }}
        </p>
        <p v-if="editError" class="edit-err">{{ editError }}</p>
      </div>

      <template #footer>
        <div class="dlg-actions">
          <el-button @click="detailDlg = false">关闭</el-button>
          <template v-if="!editing">
            <el-button v-for="a in actionsFor(detail)" :key="a.cmd" :type="a.type" plain @click="run(a)">
              {{ a.label }}
            </el-button>
          </template>
          <template v-else>
            <el-button @click="editing = null">返回</el-button>
            <el-button type="primary" :loading="submitting" @click="submitEdit">确定</el-button>
          </template>
        </div>
      </template>
    </el-dialog>

    <!-- 账单：与订单列表共用同一个组件，试算/快照的区别由后端 snapshot 标志说，不在这里各写一份 -->
    <BillDialog ref="billRef" v-model="billDlg" @settled="load" />
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, ArrowRight, Refresh } from '@element-plus/icons-vue'
import http from '../../api'
import BillDialog from '../../components/BillDialog.vue'
import { cellIsSellable } from '../../utils/sellable'
import { addMinutes, clock, diffMinutes, fmt, humanMinutes, parse } from '../../utils/dtime'

const properties = ref([])
const propertyId = ref(null)
const date = ref(null)
const cal = ref(null)
const loading = ref(false)
const submitting = ref(false)

const bookDlg = ref(false)
const detailDlg = ref(false)
const detail = ref(null)
const formError = ref('')
const editing = ref(null)          // null | 'reschedule' | 'extend'
const editStart = ref('')
const editEnd = ref('')
const editError = ref('')
const form = reactive({
  room: null, mode: 'hourly', start: '14:00', hours: 3, guest: '', phone: '', requestId: ''
})

// 内容一变就换幂等键。retry 时内容没变 → 同一个键 → 后端回放原单；
// 前台在弹窗里改了时段再提交 → 新键 → 开新单，而不是"返回旧单却提示新时段成功"。
watch(
  () => [form.room?.roomId, form.mode, form.start, form.hours],
  () => {
    if (bookDlg.value) form.requestId = newRequestId()
  }
)

function newRequestId() {
  return crypto.randomUUID ? crypto.randomUUID() : `REQ-${Date.now()}-${Math.random()}`
}

const pad = (n) => String(n).padStart(2, '0')
// 切割点小时直接从 windowStart 取，别去 parse(dayCutTime) —— 那是 "06:00:00"，
// 再拼一次 ":00" 会变成 "06:00:00:00"，看着能用其实是在赌解析器的宽容度
const dayCutHour = computed(() => (cal.value ? parse(cal.value.windowStart).getHours() : 6))

const STATUS_LABEL = {
  HELD: '预占', CONFIRMED: '已确认', IN_USE: '在住',
  CHECKED_OUT: '已退房', SETTLED: '已结算', CANCELLED: '已取消', RELEASED: '已释放'
}
const statusLabel = (s) => STATUS_LABEL[s] || s

/** 第 offset 格（0~23）对应的钟点，用于把点击位置换成时间 */
function cellHour(offset) {
  return pad((dayCutHour.value + offset) % 24) + ':00'
}

function cellStart(offset) {
  return addMinutes(parse(cal.value.windowStart), offset * 60)
}

/**
 * 该格能不能开出一张**最小合法单**。
 *
 * <p>和后端 SellPolicy 同口径：入住时刻落在可售窗口内，且按该房型的起步时长 +
 * 自己的入住前/退房后缓冲试算一遍不与别人重叠。
 * 少了这一步就会出现"格子看着能点、点了被拒"—— 审阅第 4 条举的例子是
 * 已有块到 12:45、新单 13:00 入住、入住前缓冲 30 分钟 → 新 block 从 12:30 起，实际冲突。
 */
function cellSellable(room, offset) {
  return cellIsSellable(room, cellStart(offset), cal.value.openTime, cal.value.closeTime)
}

function blkClass(b) {
  return {
    'blk--held': b.kind !== 'CLEANING' && b.bookingStatus === 'HELD',
    'blk--confirmed': b.kind !== 'CLEANING' && b.bookingStatus === 'CONFIRMED',
    'blk--inuse': b.kind !== 'CLEANING' && b.bookingStatus === 'IN_USE',
    'blk--cleaning': b.kind === 'CLEANING',
    // 超时未退房：红色必须有实际含义（仍有人住、已超时、后续单会被挡），
    // 不是单纯换个颜色
    'blk--overstay': isOverstay(b)
  }
}

function isOverstay(b) {
  return b.bookingStatus === 'IN_USE' && parse(b.endTime) < new Date()
}

/** 按分钟偏移换算成百分比；跨窗口右界的画到边，文案上标"延续次日" */
function blkStyle(b) {
  const ws = parse(cal.value.windowStart)
  const s = Math.max(0, diffMinutes(ws, parse(b.blockStart)))
  const e = Math.min(1440, diffMinutes(ws, parse(b.blockEnd)))
  // 后端会把"整段落在窗口之左"的异常行（超时在住、未确认清扫）也一并返回，
  // 否则那些行从今天消失 = 处理入口消失。这里夹到左缘画出一小段。
  // 30 分钟不是随手取的：再窄就点不中，而点开这块正是前台要办的事（去办退房/清扫）。
  const w = Math.max(e - s, 30)
  return { left: `${(s / 1440) * 100}%`, width: `${(w / 1440) * 100}%` }
}

function rangeText(b) {
  const crosses = parse(b.endTime).getDate() !== parse(b.startTime).getDate()
  return `${clock(parse(b.startTime))}–${crosses ? '次日 ' : ''}${clock(parse(b.endTime))}`
}

function tip(b) {
  const mins = diffMinutes(parse(b.startTime), parse(b.endTime))
  let text = `${b.guestName} · ${statusLabel(b.bookingStatus)} · ${rangeText(b)} · ${humanMinutes(mins)}`
  if (isOverstay(b)) {
    // 在住且已过计划离店时刻：block_end 被顶到"退房才知道"的哨兵值，不能拿它算缓冲
    text += ` ｜ 已超时 ${humanMinutes(diffMinutes(parse(b.endTime), new Date()))}未退房，需人工沟通`
  } else if (b.bookingStatus === 'IN_USE') {
    text += ' ｜ 在住中，退房后才释放'
  } else {
    const buf = diffMinutes(parse(b.endTime), parse(b.blockEnd))
    if (buf > 0) text += `（含退房缓冲 ${buf} 分）`
  }
  return text
}

const nowOffset = computed(() => {
  if (!cal.value) return null
  const ws = parse(cal.value.windowStart)
  const we = parse(cal.value.windowEnd)
  const now = new Date()
  if (now < ws || now >= we) return null
  return (diffMinutes(ws, now) / 1440) * 100
})

const previewEnd = computed(() => {
  if (!form.room) return null
  const base = parse(date.value)
  if (form.mode === 'fullday') {
    const [h, m] = form.room.fullDayStart.split(':').map(Number)
    const start = new Date(base.getFullYear(), base.getMonth(), base.getDate(), h, m)
    const [eh, em] = form.room.fullDayEnd.split(':').map(Number)
    const end = new Date(start.getFullYear(), start.getMonth(), start.getDate() + 1, eh, em)
    return { start, end }
  }
  // 开始时间理论上不可清空（选择器关掉了 clearable），但这里仍然兜一层：
  // 清空后 split 会抛错，整页白屏 —— 一个输入框不该能把房态图打没
  if (!form.start) return null
  const [h, m] = form.start.split(':').map(Number)
  if (Number.isNaN(h) || Number.isNaN(m)) return null
  const start = new Date(base.getFullYear(), base.getMonth(), base.getDate(), h, m)
  // 开始时刻在切割点之前，说明它属于窗口里的"次日"那半段
  if (h < dayCutHour.value) start.setDate(start.getDate() + 1)
  return { start, end: addMinutes(start, form.hours * 60) }
})

const previewText = computed(() => {
  const p = previewEnd.value
  if (!p) return ''
  return `${clock(p.start)} → ${clock(p.end)}（${humanMinutes(diffMinutes(p.start, p.end))}）`
})

const fmtLocal = (s) => (s ? `${s.slice(5, 10)} ${s.slice(11, 16)}` : '—')

function setMode(mode) {
  form.mode = mode
}

function onCellClick(room, offset) {
  if (room.status !== 'ON_SALE') {
    ElMessage.warning('维修中或已下架的房间不可开单，请先改状态')
    return
  }
  if (!cellSellable(room, offset)) return
  Object.assign(form, {
    room,
    mode: 'hourly',
    start: cellHour(offset),
    hours: 3,
    guest: '',
    phone: '',
    requestId: newRequestId()
  })
  formError.value = ''
  bookDlg.value = true
}

async function openDetail(b) {
  detail.value = b
  editing.value = null
  editError.value = ''
  editStart.value = b.startTime
  editEnd.value = b.endTime
  detailDlg.value = true
}

/** 按状态给出可做的动作。不在这里做判断，服务端状态机才是裁判。 */
function actionsFor(b) {
  if (!b) return []
  if (b.kind === 'CLEANING') {
    const acts = [{ label: '清扫完成', cmd: 'clean', type: 'primary' }]
    // 退房瞬间 ROOM 行换成了 CLEANING 行，这张单在日历上就只剩这一块。
    // 结算必须挂在这里，否则前台刚点完退房就找不到"收钱"这个按钮了
    if (b.bookingStatus === 'CHECKED_OUT') {
      acts.push({ label: '账单', cmd: 'bill', type: 'default' })
      acts.push({ label: '结算', cmd: 'settle', type: 'success' })
    } else if (b.bookingStatus === 'SETTLED') {
      acts.push({ label: '账单', cmd: 'bill', type: 'default' })
    }
    return acts
  }
  const map = {
    HELD: [
      { label: '确认', cmd: 'confirm', type: 'primary' },
      { label: '取消', cmd: 'cancel', type: 'danger' }
    ],
    CONFIRMED: [
      { label: '入住', cmd: 'check-in', type: 'primary' },
      { label: '改期', cmd: 'edit-reschedule', type: 'default' },
      { label: '报价', cmd: 'bill', type: 'default' },
      { label: '取消', cmd: 'cancel', type: 'danger' }
    ],
    IN_USE: [
      { label: '加时', cmd: 'edit-extend', type: 'default' },
      { label: '报价', cmd: 'bill', type: 'default' },
      { label: '退房', cmd: 'check-out', type: 'primary' }
    ],
    // 退房之后才结得了账：住了多久，到这一刻才定下来
    CHECKED_OUT: [
      { label: '账单', cmd: 'bill', type: 'default' },
      { label: '结算', cmd: 'settle', type: 'primary' }
    ],
    SETTLED: [{ label: '账单', cmd: 'bill', type: 'default' }]
  }
  return map[b.bookingStatus] || []
}

async function run(a) {
  if (a.cmd === 'edit-reschedule') {
    editing.value = 'reschedule'
    return
  }
  if (a.cmd === 'edit-extend') {
    editing.value = 'extend'
    return
  }
  if (a.cmd === 'bill') {
    await openBill()
    return
  }
  if (a.cmd === 'settle') {
    await doSettle()
    return
  }
  // 取消/退房都会改变占用与账目，点错没有撤销：加一道确认
  if (a.cmd === 'cancel' || a.cmd === 'check-out') {
    try {
      await ElMessageBox.confirm(
        a.cmd === 'cancel' ? '取消这张订单？房源会立即释放。' : '确认给客人办退房？',
        '确认' + a.label,
        { type: 'warning', confirmButtonText: a.label, cancelButtonText: '再想想' }
      )
    } catch {
      return
    }
  }
  submitting.value = true
  try {
    if (a.cmd === 'clean') {
      const res = await http.post(`/rooms/${detail.value.roomId}/cleaning-done`)
      ElMessage.success(res.data > 0 ? '已释放，房间恢复可售' : '没有待清扫的占用')
    } else {
      await http.post(`/bookings/${detail.value.bookingId}/${a.cmd}`)
      ElMessage.success(`${a.label}完成`)
    }
    detailDlg.value = false
    await load()
  } catch {
    /* 拦截器已提示 */
  } finally {
    submitting.value = false
  }
}

// ------------------------------------------------------------ 账单与结算

const billDlg = ref(false)
const billRef = ref()

function openBill() {
  billDlg.value = true
  return billRef.value.open(detail.value.bookingId)
}

async function doSettle() {
  submitting.value = true
  try {
    const id = detail.value.bookingId
    await http.post(`/bookings/${id}/settle`)
    ElMessage.success('已结算')
    detailDlg.value = false
    await load()
    billDlg.value = true
    billRef.value.open(id)   // 结算完顺手把那张快照摊开，前台不用回列表再点一次
  } catch {
    /* 拦截器已提示 */
  } finally {
    submitting.value = false
  }
}

async function submitEdit() {
  editError.value = ''
  if (!editStart.value || !editEnd.value) {
    editError.value = '开始与结束时间都要填'
    return
  }
  submitting.value = true
  try {
    if (editing.value === 'reschedule') {
      await http.put(`/bookings/${detail.value.bookingId}/times`, {
        startTime: editStart.value, endTime: editEnd.value
      })
      ElMessage.success('已改期')
    } else {
      await http.post(`/bookings/${detail.value.bookingId}/extend`, { newEndTime: editEnd.value })
      ElMessage.success('已加时')
    }
    detailDlg.value = false
    await load()
  } catch (e) {
    // 冲突、状态不允许、超出可售窗口都留在弹窗里
    editError.value = e?.message || '操作失败'
  } finally {
    submitting.value = false
  }
}

async function submit() {
  if (!form.guest.trim()) {
    formError.value = '请填写客人姓名'
    return
  }
  formError.value = ''
  submitting.value = true
  try {
    await http.post('/bookings', {
      roomId: form.room.roomId,
      guestName: form.guest.trim(),
      guestPhone: form.phone.trim() || null,
      startTime: fmt(previewEnd.value.start),
      endTime: fmt(previewEnd.value.end),
      // 售卖方式必须显式声明。原先后端按"是否跨天"猜，22:00→次日00:30 这种
      // 2.5 小时的单子就能绕过起步时长校验。
      saleType: form.mode === 'fullday' ? 'FULL_DAY' : 'HOURLY',
      // 幂等键：双击或超时重试带同一个值，服务端返回原单而不是再造一单
      requestId: form.requestId
    })
    ElMessage.success(`已开单：${form.room.roomNo} ${previewText.value}`)
    bookDlg.value = false
    await load()
  } catch (e) {
    // 冲突、起步时长不足、超出可售窗口都留在弹窗里说清楚
    formError.value = e?.message || '开单失败，请调整时段后重试'
  } finally {
    submitting.value = false
  }
}

function shiftDay(delta) {
  const d = parse(date.value)
  d.setDate(d.getDate() + delta)
  date.value = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
  load()
}

function goToday() {
  // 按营业日算，不是按自然日：01:00 且切割点 06:00 时，"今天"这张图覆盖的是
  // [今天 06:00, 明天 06:00)，此刻的住客（昨天的单）会从图上消失。归属规则与后端一致：
  // 营业日 = 当前时刻往前推切割点后取日期
  const d = new Date()
  if (d.getHours() < dayCutHour.value) d.setDate(d.getDate() - 1)
  date.value = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
  load()
}

async function load() {
  if (!propertyId.value || !date.value) return
  loading.value = true
  try {
    cal.value = (await http.get(`/calendar?propertyId=${propertyId.value}&date=${date.value}`)).data
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  properties.value = (await http.get('/properties')).data
  propertyId.value = properties.value[0]?.id ?? null
  goToday()
})
</script>

<style scoped>
.bar {
  padding: var(--bnb-space-4) var(--bnb-space-5);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--bnb-space-4);
  flex-wrap: wrap;
  margin-bottom: var(--bnb-space-4);
}

.legend {
  display: flex;
  gap: var(--bnb-space-4);
  font-size: var(--bnb-fs-footnote);
  color: var(--bnb-text-secondary);
  flex-wrap: wrap;
}
.legend__item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.sw {
  width: 12px;
  height: 12px;
  border-radius: 4px;
  display: inline-block;
}
.sw--held { background: var(--bnb-warning); }
.sw--confirmed { background: var(--bnb-accent); }
.sw--inuse { background: var(--bnb-success); }
.sw--overstay { background: repeating-linear-gradient(135deg, var(--bnb-danger), var(--bnb-danger) 3px, #a8000b 3px, #a8000b 6px); }
.sw--cleaning { background: repeating-linear-gradient(45deg, var(--bnb-fill-secondary), var(--bnb-fill-secondary) 3px, var(--bnb-fill-tertiary) 3px, var(--bnb-fill-tertiary) 6px); }
.sw--free { background: var(--bnb-fill-secondary); }

.grid-card {
  padding: var(--bnb-space-5) var(--bnb-space-6) var(--bnb-space-4);
  overflow-x: auto;
}

.grid {
  display: grid;
  grid-template-columns: 132px minmax(660px, 1fr);
  gap: var(--bnb-space-2) var(--bnb-space-3);
  min-width: 800px;
}

.grid__corner {
  display: flex;
  align-items: flex-end;
  font-size: var(--bnb-fs-footnote);
}

.grid__label {
  padding: var(--bnb-space-2) 0;
  border-top: 1px solid var(--bnb-separator);
  min-width: 0;
}
.grid__roomno {
  font-weight: 600;
  font-size: var(--bnb-fs-subheadline);
}
.grid__type {
  font-size: var(--bnb-fs-footnote);
  color: var(--bnb-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.scale {
  display: flex;
  border-bottom: 1px solid var(--bnb-separator);
  height: 22px;
}
.scale__cell {
  flex: 1;
  border-left: 1px solid var(--bnb-hairline);
  position: relative;
}
.scale__label {
  position: absolute;
  top: 0;
  left: 2px;
  font-size: var(--bnb-fs-caption);
  color: var(--bnb-text-tertiary);
  font-variant-numeric: tabular-nums;
}

.track {
  display: flex;
  position: relative;
  height: 44px;
  border-top: 1px solid var(--bnb-separator);
}
.track__cell {
  flex: 1;
  border-left: 1px solid var(--bnb-hairline);
  background: transparent;
  cursor: pointer;
  transition: background-color var(--bnb-duration-fast) var(--bnb-ease);
}
.track__cell:hover {
  background: var(--bnb-accent-soft);
}
.track__cell.is-blocked {
  cursor: default;
}
.track__cell.is-blocked:hover {
  background: transparent;
}

.track.is-dim,
.grid__label.is-dim {
  opacity: 0.45;
}
.track.is-dim .track__cell {
  cursor: not-allowed;
  background: repeating-linear-gradient(
    135deg, transparent, transparent 6px, var(--bnb-fill-tertiary) 6px, var(--bnb-fill-tertiary) 12px);
}

.blk {
  position: absolute;
  top: 5px;
  bottom: 5px;
  border-radius: 7px;
  overflow: hidden;
  cursor: pointer;
  min-width: 4px;
}
.blk__inner {
  display: flex;
  flex-direction: column;
  justify-content: center;
  height: 100%;
  padding: 0 8px;
  font-size: var(--bnb-fs-caption);
  line-height: 1.35;
  color: #fff;
  white-space: nowrap;
  overflow: hidden;
}
.blk__guest {
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
}
.blk__time {
  opacity: 0.85;
  font-variant-numeric: tabular-nums;
}
.blk--held { background: var(--bnb-warning); }
.blk--confirmed { background: var(--bnb-accent); }
.blk--inuse { background: var(--bnb-success); }
/* 超时未退房：红色 + 斜纹，一眼区别于"正常在住" */
.blk--overstay {
  background: repeating-linear-gradient(
    135deg, var(--bnb-danger), var(--bnb-danger) 8px,
    color-mix(in srgb, var(--bnb-danger) 78%, black) 8px,
    color-mix(in srgb, var(--bnb-danger) 78%, black) 16px);
}
/* 待清扫：中性斜纹 —— 它不是"有人住"，是"能住但还没好" */
.blk--cleaning {
  background: repeating-linear-gradient(
    45deg, var(--bnb-fill-secondary), var(--bnb-fill-secondary) 6px,
    var(--bnb-fill-tertiary) 6px, var(--bnb-fill-tertiary) 12px);
}
.blk--cleaning .blk__inner { color: var(--bnb-text-secondary); }

.now {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 2px;
  background: var(--bnb-danger);
  pointer-events: none;
  z-index: 2;
}

.foot {
  margin-top: var(--bnb-space-4);
  font-size: var(--bnb-fs-footnote);
  line-height: 1.6;
}

.form-room {
  display: flex;
  align-items: baseline;
  gap: var(--bnb-space-3);
  margin-bottom: var(--bnb-space-4);
  font-size: var(--bnb-fs-body);
}
.pair {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--bnb-space-4);
}
.preview {
  margin-bottom: var(--bnb-space-4);
  padding: var(--bnb-space-3) var(--bnb-space-4);
  border-radius: var(--bnb-radius-sm);
  background: var(--bnb-fill-tertiary);
  font-size: var(--bnb-fs-callout);
  font-variant-numeric: tabular-nums;
}
.err {
  margin-bottom: var(--bnb-space-2);
}
</style>
