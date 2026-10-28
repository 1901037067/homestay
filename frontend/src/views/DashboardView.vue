<template>
  <div class="bnb-page">
    <div class="bnb-page__head">
      <div>
        <div class="bnb-page__title">概览</div>
        <div class="bnb-page__sub">房源目录视角。经营数据（出租率/营收/热度）在「报表」页，按营业日切割统计</div>
      </div>
      <el-button :loading="loading" @click="load">
        <el-icon><Refresh /></el-icon>刷新
      </el-button>
    </div>

    <div class="stats">
      <div v-for="s in stats" :key="s.label" class="bnb-card stat">
        <div class="bnb-stat__label">{{ s.label }}</div>
        <div class="bnb-stat__value">{{ s.value }}</div>
        <div v-if="s.note" class="bnb-stat__note bnb-muted">{{ s.note }}</div>
      </div>
    </div>

    <div class="bnb-card">
      <div class="bnb-card__head">
        <span class="bnb-card__title">按民宿分布</span>
        <span class="bnb-muted hint">营业日切割点决定凌晨订单算哪一天，报表口径以它为准</span>
      </div>
      <el-table v-loading="loading" :data="byProperty" style="width: 100%">
        <el-table-column prop="name" label="民宿" min-width="140" />
        <el-table-column label="切割点" width="110" align="right">
          <template #default="{ row }"><span class="bnb-num">{{ row.dayCutTime }}</span></template>
        </el-table-column>
        <el-table-column label="可售窗口" width="150" align="right">
          <template #default="{ row }">
            <span class="bnb-num">{{ row.openTime }}–{{ row.closeTime }}</span>
          </template>
        </el-table-column>
        <el-table-column label="房型" width="90" align="right">
          <template #default="{ row }"><span class="bnb-num">{{ row.types }}</span></template>
        </el-table-column>
        <el-table-column label="房间" width="90" align="right">
          <template #default="{ row }"><span class="bnb-num">{{ row.rooms }}</span></template>
        </el-table-column>
        <el-table-column label="在售 / 维修 / 下架" min-width="170" align="right">
          <template #default="{ row }">
            <span class="bnb-num">{{ row.onSale }} / {{ row.maintenance }} / {{ row.offline }}</span>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import http from '../api'

const loading = ref(false)
const properties = ref([])
const roomTypes = ref([])
const rooms = ref([])

const byProperty = computed(() =>
  properties.value.map((p) => {
    const rs = rooms.value.filter((r) => r.propertyId === p.id)
    return {
      ...p,
      types: roomTypes.value.filter((t) => t.propertyId === p.id).length,
      rooms: rs.length,
      onSale: rs.filter((r) => r.status === 'ON_SALE').length,
      maintenance: rs.filter((r) => r.status === 'MAINTENANCE').length,
      offline: rs.filter((r) => r.status === 'OFFLINE').length
    }
  })
)

const stats = computed(() => [
  { label: '民宿', value: properties.value.length },
  { label: '房型', value: roomTypes.value.length },
  { label: '房间', value: rooms.value.length },
  {
    label: '在售',
    value: rooms.value.filter((r) => r.status === 'ON_SALE').length,
    note: `维修 ${rooms.value.filter((r) => r.status === 'MAINTENANCE').length} · 下架 ${
      rooms.value.filter((r) => r.status === 'OFFLINE').length}`
  }
])

async function load() {
  loading.value = true
  try {
    const [p, t, r] = await Promise.all([
      http.get('/properties'),
      http.get('/room-types'),
      http.get('/rooms?page=1&size=200')
    ])
    properties.value = p.data
    roomTypes.value = t.data
    rooms.value = r.data.records
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: var(--bnb-space-4);
  margin-bottom: var(--bnb-space-6);
}

.stat {
  padding: var(--bnb-space-5) var(--bnb-space-6);
}

.bnb-stat__note {
  margin-top: 2px;
  font-size: var(--bnb-fs-footnote);
}

.hint {
  font-size: var(--bnb-fs-footnote);
}
</style>
