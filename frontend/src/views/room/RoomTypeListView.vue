<template>
  <div class="bnb-page">
    <div class="bnb-page__head">
      <div class="bnb-page__title">房型</div>
      <el-button type="primary" @click="openCreate">
        <el-icon><Plus /></el-icon>新建房型
      </el-button>
    </div>

    <div class="bnb-card">
      <div class="bnb-card__head">
        <div class="bnb-toolbar">
          <el-select v-model="filterProperty" placeholder="全部民宿" clearable style="width: 190px" @change="load">
            <el-option v-for="p in properties" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
        </div>
        <span class="bnb-muted count">{{ rows.length }} 个房型</span>
      </div>

      <el-table v-loading="loading" :data="rows" style="width: 100%">
        <el-table-column prop="name" label="房型名称" min-width="150" />
        <el-table-column prop="propertyName" label="所属民宿" min-width="120" />
        <el-table-column label="基础价" width="110" align="right">
          <template #default="{ row }"><span class="bnb-num">¥{{ row.basePrice }}</span></template>
        </el-table-column>
        <el-table-column label="起步/超时" width="150" align="right">
          <template #default="{ row }">
            <span class="bnb-num">{{ row.minHours }}h · ¥{{ row.extraHourPrice }}/h</span>
          </template>
        </el-table-column>
        <el-table-column label="计费粒度" width="100" align="right">
          <template #default="{ row }">
            <span class="bnb-num">{{ row.chargeUnitMinutes }}′</span>
          </template>
        </el-table-column>
        <el-table-column label="清扫缓冲" width="130" align="right">
          <template #default="{ row }">
            <span class="bnb-num">前 {{ row.cleanBeforeMinutes }}′ / 后 {{ row.cleanAfterMinutes }}′</span>
          </template>
        </el-table-column>
        <el-table-column label="全日房时段" width="140" align="right">
          <template #default="{ row }">
            <span class="bnb-num">{{ row.fullDayStart }}–次日 {{ row.fullDayEnd }}</span>
          </template>
        </el-table-column>
        <el-table-column label="房间" width="80" align="right">
          <template #default="{ row }"><span class="bnb-num">{{ row.roomCount }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="140" align="right" fixed="right">
          <template #default="{ row }">
            <el-button text @click="openEdit(row)">编辑</el-button>
            <el-button text class="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="该民宿下还没有房型" />
        </template>
      </el-table>
    </div>

    <el-dialog v-model="dialog" :title="form.id ? '编辑房型' : '新建房型'" width="560">
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <div class="pair">
          <el-form-item label="所属民宿" prop="propertyId">
            <el-select v-model="form.propertyId" placeholder="请选择" style="width: 100%">
              <el-option v-for="p in properties" :key="p.id" :label="p.name" :value="p.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="房型名称" prop="name">
            <el-input v-model="form.name" placeholder="钟点房·大床" />
          </el-form-item>
        </div>

        <div class="pair">
          <el-form-item label="基础价（元）" prop="basePrice">
            <el-input-number v-model="form.basePrice" :min="0" :precision="2" :step="10" style="width: 100%" />
          </el-form-item>
          <el-form-item label="超时单价（元/小时）" prop="extraHourPrice">
            <el-input-number v-model="form.extraHourPrice" :min="0" :precision="2" :step="5" style="width: 100%" />
          </el-form-item>
        </div>

        <div class="pair">
          <el-form-item label="起步时长（小时）" prop="minHours">
            <el-input-number v-model="form.minHours" :min="1" :max="24" style="width: 100%" />
          </el-form-item>
          <el-form-item label="超时费计费粒度（分钟）" prop="chargeUnitMinutes">
            <el-select v-model="form.chargeUnitMinutes" style="width: 100%">
              <el-option v-for="u in UNITS" :key="u.v" :label="u.t" :value="u.v" />
            </el-select>
          </el-form-item>
        </div>
        <div class="tip grain-tip">
          粒度决定超时费怎么取整：60 = 不足一小时按一小时，30 = 不足半小时按半小时。
        </div>

        <div class="field">
          <label>全日房时段</label>
          <div class="pair-inner">
            <el-time-picker v-model="form.fullDayStart" value-format="HH:mm:ss" placeholder="14:00" />
            <el-time-picker v-model="form.fullDayEnd" value-format="HH:mm:ss" placeholder="次日 12:00" />
          </div>
          <div class="tip">结束时间早于开始时间即视为跨到次日。</div>
        </div>

        <div class="pair">
          <el-form-item label="清扫缓冲·入住前（分钟）" prop="cleanBeforeMinutes">
            <el-input-number v-model="form.cleanBeforeMinutes" :min="0" :max="240" :step="15" style="width: 100%" />
          </el-form-item>
          <el-form-item label="清扫缓冲·退房后（分钟）" prop="cleanAfterMinutes">
            <el-input-number v-model="form.cleanAfterMinutes" :min="0" :max="240" :step="15" style="width: 100%" />
          </el-form-item>
        </div>
        <div class="tip">
          缓冲会并进 block_start / block_end 参与冲突检测：两单边界相接时，靠它挡住下一单。
        </div>
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
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import http from '../../api'

const rows = ref([])
const properties = ref([])
const filterProperty = ref(null)
const loading = ref(false)
const saving = ref(false)
const dialog = ref(false)
const formRef = ref()
const form = reactive(empty())

const rules = {
  propertyId: [{ required: true, message: '请选择民宿', trigger: 'change' }],
  name: [{ required: true, message: '请输入房型名称', trigger: 'blur' }],
  basePrice: [{ required: true, message: '请输入基础价', trigger: 'blur' }],
  extraHourPrice: [{ required: true, message: '请输入超时单价', trigger: 'blur' }],
  minHours: [{ required: true, message: '请输入起步时长', trigger: 'blur' }],
  fullDayStart: [{ required: true, message: '请选择开始', trigger: 'change' }],
  fullDayEnd: [{ required: true, message: '请选择结束', trigger: 'change' }]
}

const UNITS = [
  { v: 60, t: '60 分钟（不足一小时按一小时）' },
  { v: 30, t: '30 分钟（半小时一档）' },
  { v: 15, t: '15 分钟' },
  { v: 10, t: '10 分钟' },
  { v: 1, t: '1 分钟（按分钟线性折算）' }
]

function empty() {
  return {
    id: null,
    propertyId: null,
    name: '',
    basePrice: 100,
    minHours: 3,
    extraHourPrice: 30,
    chargeUnitMinutes: 60,
    cleanBeforeMinutes: 0,
    cleanAfterMinutes: 45,
    fullDayStart: '14:00:00',
    fullDayEnd: '12:00:00'
  }
}

async function load() {
  loading.value = true
  try {
    const q = filterProperty.value ? `?propertyId=${filterProperty.value}` : ''
    rows.value = (await http.get(`/room-types${q}`)).data
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(form, empty(), { propertyId: filterProperty.value ?? properties.value[0]?.id ?? null })
  dialog.value = true
}

function openEdit(row) {
  Object.assign(form, { ...row })
  dialog.value = true
}

async function save() {
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  saving.value = true
  try {
    if (form.id) {
      await http.put(`/room-types/${form.id}`, { ...form })
    } else {
      await http.post('/room-types', { ...form })
    }
    ElMessage.success('已保存')
    dialog.value = false
    await load()
  } catch {
    /* 拦截器已提示 */
  } finally {
    saving.value = false
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(
      `删除房型「${row.name}」？该房型下若还有房间会被拒绝。`,
      '确认删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await http.delete(`/room-types/${row.id}`)
    ElMessage.success('已删除')
    await load()
  } catch {
    /* 拦截器已提示 */
  }
}

onMounted(async () => {
  properties.value = (await http.get('/properties')).data
  await load()
})
</script>

<style scoped>
.count {
  font-size: var(--bnb-fs-callout);
}
.danger {
  color: var(--bnb-danger);
}
.pair {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--bnb-space-4);
}
.pair-inner {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--bnb-space-2);
}
.field > label {
  display: block;
  margin-bottom: 8px;
  font-size: var(--bnb-fs-callout);
  font-weight: 500;
  color: var(--bnb-text-secondary);
}
.tip {
  margin-top: -4px;
  font-size: var(--bnb-fs-footnote);
  line-height: 1.5;
  color: var(--bnb-text-secondary);
}
</style>
