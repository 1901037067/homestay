<template>
  <div class="bnb-page">
    <div class="bnb-page__head">
      <div class="bnb-page__title">经营报表</div>
      <div class="head-actions">
        <el-button :loading="loading" @click="load">
          <el-icon><Refresh /></el-icon>刷新
        </el-button>
        <el-button type="primary" :disabled="!report" @click="exportCsv">
          <el-icon><Download /></el-icon>导出 CSV
        </el-button>
      </div>
    </div>

    <div class="bnb-card bar">
      <el-select v-model="propertyId" placeholder="选择民宿" style="width: 190px" @change="load">
        <el-option v-for="p in properties" :key="p.id" :label="p.name" :value="p.id" />
      </el-select>
      <el-date-picker
        v-model="range"
        type="daterange"
        value-format="YYYY-MM-DD"
        range-separator="→"
        start-placeholder="开始营业日"
        end-placeholder="结束营业日"
        style="width: 280px"
        @change="load"
      />
      <div class="rotate">
        <span class="bnb-muted">热度横轴</span>
        <el-radio-group v-model="rotate" size="small">
          <el-radio-button :value="true">从切割点起（正确）</el-radio-button>
          <el-radio-button :value="false">从 00:00 起（对照）</el-radio-button>
        </el-radio-group>
      </div>
      <span class="bnb-muted hint">
        {{ report ? `${report.from} → ${report.to} · ${report.daily.length} 个营业日` : '' }}
      </span>
    </div>

    <div class="stats">
      <div v-for="s in cards" :key="s.label" class="bnb-card stat">
        <div class="bnb-stat__label">{{ s.label }}</div>
        <div class="bnb-stat__value">{{ s.value }}</div>
        <div class="bnb-stat__note bnb-muted">{{ s.note }}</div>
      </div>
    </div>

    <div class="bnb-card chart-card">
      <div class="bnb-card__head">
        <span class="bnb-card__title">逐营业日：营收与出租率</span>
        <span class="bnb-muted hint">
          出租率 = 当天<b>有房晚</b>的不同房间数 ÷ 在售房间数（分母是<b>当前</b>在售房间数，
          房间下架会让历史出租率跟着变 —— 已知简化）。
          连住 N 晚的<b>房晚与营收都摊到预订的那 N 个营业日</b>（营收按账单明细的起点归日），
          只有<b>订单数</b>仍只算入住那天 —— 一张单不能数两次。
          房晚按<b>卖出去的</b>算而不是实际住了几天：提前退房仍计满原单晚数（不退差价），
          超时住到第 N+1 晚也不会多计一晚
        </span>
      </div>
      <div ref="trendEl" class="chart" />
    </div>

    <div class="bnb-card chart-card">
      <div class="bnb-card__head">
        <span class="bnb-card__title">分时段热度</span>
        <span class="bnb-muted hint">
          格子是"该小时有几间房被占住"（含预占与已确认）；跨零点的单会落在两个营业日里
        </span>
      </div>
      <div ref="heatEl" class="chart chart--heat" />
      <div v-if="!rotate" class="warn bnb-muted">
        对照模式：横轴从 00:00 起。这时凌晨 2 点的单会被画在最左边，
        和同一天 23:00 的单看起来"隔了一整行" —— 正是实施计划 7.2 要求旋转的原因。
      </div>
    </div>

    <div class="bnb-card chart-card">
      <div class="bnb-card__head">
        <span class="bnb-card__title">房间营收排行</span>
        <span class="bnb-muted hint">只统计所选区间内已结算的金额</span>
      </div>
      <div ref="rankEl" class="chart chart--rank" />
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Download, Refresh } from '@element-plus/icons-vue'
import * as echarts from 'echarts/core'
import { BarChart, HeatmapChart, LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent, VisualMapComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import http from '../../api'
import { useTheme } from '../../composables/useTheme'

// 按需注册：整包 echarts 有 1MB，这里只用三种图 + 四个组件
echarts.use([BarChart, LineChart, HeatmapChart,
  GridComponent, TooltipComponent, LegendComponent, VisualMapComponent, CanvasRenderer])

const { isDark } = useTheme()

const properties = ref([])
const propertyId = ref(null)
const range = ref(null)
const rotate = ref(true)
const report = ref(null)
const loading = ref(false)

const trendEl = ref(null)
const heatEl = ref(null)
const rankEl = ref(null)
let trendChart = null
let heatChart = null
let rankChart = null

/** 颜色跟着设计令牌走，别在图表里另立一套色 —— 换主题时图表要跟着变。 */
const cssVar = (name, fallback) => {
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return v || fallback
}

const cards = computed(() => {
  const t = report.value?.totals
  if (!t) return []
  return [
    { label: '已结算营收', value: `¥${Number(t.revenue).toFixed(2)}`, note: `待结算 ${t.pendingBookings} 单` },
    { label: '平均出租率', value: `${t.avgOccupancy.toFixed(2)}%`, note: `售出 ${t.soldRoomDays} 个房晚（按预订住宿日计）` },
    { label: '订单数', value: t.bookings, note: `在售房间 ${report.value.sellableRooms} 间` },
    { label: '异常退房', value: t.overstay + t.earlyLeave, note: `超时 ${t.overstay} · 早退 ${t.earlyLeave}` }
  ]
})

async function load() {
  if (!propertyId.value) return
  loading.value = true
  try {
    const params = new URLSearchParams({ propertyId: propertyId.value })
    if (range.value?.length === 2) {
      params.set('from', range.value[0])
      params.set('to', range.value[1])
    }
    report.value = (await http.get(`/stats/report?${params.toString()}`)).data
    // 后端给了缺省区间（最近 14 个营业日），回填到日期控件里，让"看到的就是查的"
    if (!range.value) range.value = [report.value.from, report.value.to]
    await nextTick()
    render()
  } finally {
    loading.value = false
  }
}

function render() {
  if (!report.value) return
  const text = cssVar('--bnb-text-secondary', '#6e6e73')
  const hair = cssVar('--bnb-hairline', 'rgba(0,0,0,0.06)')
  const accent = cssVar('--bnb-accent', '#0071e3')
  const success = cssVar('--bnb-success', '#248a3d')
  const empty = cssVar('--bnb-fill-secondary', 'rgba(120,120,128,0.12)')

  const days = report.value.daily.map((d) => d.businessDay.slice(5))   // MM-DD
  const axisStyle = {
    axisLine: { lineStyle: { color: hair } },
    axisTick: { show: false },
    axisLabel: { color: text, fontSize: 11 }
  }

  // ---------------------------------------------------------------- 趋势
  trendChart ??= echarts.init(trendEl.value)
  trendChart.setOption({
    grid: { left: 52, right: 56, top: 40, bottom: 34 },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    legend: { right: 0, top: 0, textStyle: { color: text, fontSize: 11 }, itemWidth: 10, itemHeight: 10 },
    xAxis: { type: 'category', data: days, ...axisStyle },
    yAxis: [
      { type: 'value', name: '营收', nameTextStyle: { color: text, fontSize: 11 }, splitLine: { lineStyle: { color: hair } }, axisLabel: { color: text, fontSize: 11 } },
      { type: 'value', name: '出租率%', max: 100, nameTextStyle: { color: text, fontSize: 11 }, splitLine: { show: false }, axisLabel: { color: text, fontSize: 11 } }
    ],
    series: [
      {
        name: '已结算营收',
        type: 'bar',
        barMaxWidth: 22,
        itemStyle: { color: accent, borderRadius: [6, 6, 0, 0] },
        data: report.value.daily.map((d) => Number(d.revenue))
      },
      {
        name: '出租率',
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        symbolSize: 6,
        lineStyle: { width: 2, color: success },
        itemStyle: { color: success },
        data: report.value.daily.map((d) => Number(d.occupancyRate))
      }
    ]
  }, true)

  // ---------------------------------------------------------------- 热度
  // 横轴按"从哪儿起算"重排：rotate=true 用切割点，false 用 00:00（对照，看错误长什么样）
  const startHour = rotate.value ? report.value.dayCutTime.slice(0, 2) * 1 : 0
  const hours = Array.from({ length: 24 }, (_, i) => (startHour + i) % 24)
  const dayIndex = new Map(days.map((d, i) => [d, i]))
  const cells = []
  let peak = 1
  for (const h of report.value.heat) {
    const y = dayIndex.get(h.businessDay.slice(5))
    if (y === undefined) continue
    const x = hours.indexOf(h.hour)
    if (x < 0) continue
    cells.push([x, y, h.occupiedRooms])
    peak = Math.max(peak, h.occupiedRooms)
  }
  heatChart ??= echarts.init(heatEl.value)
  heatChart.setOption({
    grid: { left: 56, right: 20, top: 12, bottom: 42 },
    tooltip: {
      formatter: (p) => `${days[p.value[1]]} ${String(hours[p.value[0]]).padStart(2, '0')}:00<br/>占用 ${p.value[2]} 间`
    },
    xAxis: {
      type: 'category',
      data: hours.map((h) => String(h).padStart(2, '0')),
      splitArea: { show: false },
      ...axisStyle
    },
    yAxis: { type: 'category', data: days, ...axisStyle },
    visualMap: {
      min: 0,
      max: peak,
      calculable: false,
      orient: 'horizontal',
      left: 'center',
      bottom: -2,
      itemWidth: 12,
      itemHeight: 90,
      textStyle: { color: text, fontSize: 11 },
      inRange: { color: [empty, accent] }
    },
    series: [{
      type: 'heatmap',
      data: cells,
      itemStyle: { borderRadius: 5, borderWidth: 2, borderColor: 'transparent' },
      emphasis: { itemStyle: { shadowBlur: 6, shadowColor: 'rgba(0,0,0,0.18)' } }
    }]
  }, true)

  // ---------------------------------------------------------------- 排行
  const top = report.value.ranking.slice(0, 10).reverse()   // 横向柱：大到小要反过来画
  rankChart ??= echarts.init(rankEl.value)
  rankChart.setOption({
    grid: { left: 64, right: 48, top: 12, bottom: 24 },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: { type: 'value', splitLine: { lineStyle: { color: hair } }, axisLabel: { color: text, fontSize: 11 } },
    yAxis: { type: 'category', data: top.map((r) => r.roomNo), ...axisStyle },
    series: [{
      type: 'bar',
      barMaxWidth: 16,
      itemStyle: { color: accent, borderRadius: [0, 6, 6, 0] },
      label: { show: true, position: 'right', color: text, fontSize: 11, formatter: '¥{c}' },
      data: top.map((r) => Number(r.revenue))
    }]
  }, true)
}

function resize() {
  trendChart?.resize()
  heatChart?.resize()
  rankChart?.resize()
}

/** 导出走浏览器原生下载：链接带上当前筛选条件，和屏幕上的那份数据同源。 */
function exportCsv() {
  if (!report.value) return
  const params = new URLSearchParams({
    propertyId: propertyId.value,
    from: report.value.from,
    to: report.value.to
  })
  const a = document.createElement('a')
  a.href = `/api/stats/export?${params.toString()}`
  a.download = `stats-${report.value.from}_${report.value.to}.csv`
  a.click()
  ElMessage.success('已导出（UTF-8 BOM，Excel 双击可直接打开）')
}

onMounted(async () => {
  properties.value = (await http.get('/properties')).data
  propertyId.value = properties.value[0]?.id ?? null
  await load()
  window.addEventListener('resize', resize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  trendChart?.dispose()
  heatChart?.dispose()
  rankChart?.dispose()
})

// 换主题要重画：图里的颜色是初始化时从 CSS 变量读的，不重画就会留在旧主题。
// 这里看的是"实际是不是深色"而不是"用户选了哪一档"——选"跟随系统"时 mode 一直是 auto，
// 系统换主题不会改变 mode，图表就不会重绘
watch(isDark, () => nextTick(render))
watch(rotate, () => nextTick(render))
</script>

<style scoped>
.bar {
  display: flex;
  align-items: center;
  gap: var(--bnb-space-4);
  flex-wrap: wrap;
  margin-bottom: var(--bnb-space-4);
  padding: var(--bnb-space-4) var(--bnb-space-5);
}
.head-actions {
  display: flex;
  gap: var(--bnb-space-2);
}
.rotate {
  display: flex;
  align-items: center;
  gap: var(--bnb-space-2);
  font-size: var(--bnb-fs-callout);
}
.hint {
  font-size: var(--bnb-fs-footnote);
}
.stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--bnb-space-4);
  margin-bottom: var(--bnb-space-4);
}
.stat {
  padding: var(--bnb-space-4) var(--bnb-space-5);
}
.chart-card {
  margin-bottom: var(--bnb-space-4);
}
.chart {
  width: 100%;
  height: 280px;
}
.chart--heat {
  height: 320px;
}
.chart--rank {
  height: 300px;
}
.warn {
  margin-top: var(--bnb-space-2);
  font-size: var(--bnb-fs-footnote);
  line-height: 1.5;
}
@media (max-width: 980px) {
  .stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
