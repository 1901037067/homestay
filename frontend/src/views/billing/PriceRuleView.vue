<template>
  <div class="bnb-page">
    <div class="bnb-page__head">
      <div>
        <div class="bnb-page__title">价格规则</div>
        <div class="bnb-page__sub">
          按日期、星期、时段覆盖房型的平时价；一个时刻只取一条，不叠加
        </div>
      </div>
      <el-button type="primary" @click="openCreate">
        <el-icon><Plus /></el-icon>新建规则
      </el-button>
    </div>

    <div class="bnb-card">
      <div class="bnb-card__head">
        <div class="bnb-toolbar">
          <el-select v-model="filterProperty" placeholder="全部民宿" clearable style="width: 190px" @change="load">
            <el-option v-for="p in properties" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
        </div>
        <span class="bnb-muted count">{{ rows.length }} 条规则</span>
      </div>

      <!-- 操作列刻意不加 fixed="right"：窗口窄时固定列会浮在滚动区上，看着像把
           "计价/优先级"几列盖住了。这张表在 1280 前台机上不需要横向滚动，
           真到窄窗口，宁可让它老老实实滚。 -->
      <el-table v-loading="loading" :data="rows" style="width: 100%">
        <el-table-column prop="name" label="规则名称" min-width="130" />
        <el-table-column prop="propertyName" label="民宿" min-width="90" />
        <el-table-column label="作用房型" min-width="110">
          <template #default="{ row }">
            <span v-if="row.roomTypeName">{{ row.roomTypeName }}</span>
            <span v-else class="bnb-muted">民宿通用</span>
          </template>
        </el-table-column>
        <el-table-column label="命中条件" min-width="190">
          <template #default="{ row }"><span class="scope">{{ row.scope }}</span></template>
        </el-table-column>
        <el-table-column label="计价" min-width="120" align="right">
          <template #default="{ row }">
            <span class="bnb-num">
              {{ row.mode === 'OVERRIDE' ? `¥${row.value}` : `×${row.value}` }}
            </span>
            <span class="bnb-muted mode">{{ row.mode === 'OVERRIDE' ? '一口价' : '倍率' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="优先级" width="70" align="right">
          <template #default="{ row }"><span class="bnb-num">{{ row.priority }}</span></template>
        </el-table-column>
        <el-table-column label="启用" width="70" align="center">
          <template #default="{ row }">
            <el-switch :model-value="row.enabled" @change="toggle(row)" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" align="right">
          <template #default="{ row }">
            <el-button text @click="openEdit(row)">编辑</el-button>
            <el-button text class="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="还没有价格规则，所有房间都按房型的平时价计" />
        </template>
      </el-table>
    </div>

    <el-dialog v-model="dialog" :title="form.id ? '编辑价格规则' : '新建价格规则'" width="600">
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <div class="pair">
          <el-form-item label="规则名称" prop="name">
            <el-input v-model="form.name" placeholder="周末钟点上浮" />
          </el-form-item>
          <el-form-item label="所属民宿" prop="propertyId">
            <el-select v-model="form.propertyId" placeholder="请选择" style="width: 100%" @change="onProperty">
              <el-option v-for="p in properties" :key="p.id" :label="p.name" :value="p.id" />
            </el-select>
          </el-form-item>
        </div>

        <div class="pair">
          <el-form-item label="限定房型（留空 = 民宿通用）">
            <el-select v-model="form.roomTypeId" placeholder="民宿通用" clearable style="width: 100%">
              <el-option v-for="t in typeOptions" :key="t.id" :label="t.name" :value="t.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="优先级（小的赢）" prop="priority">
            <el-input-number v-model="form.priority" :min="1" :max="9999" style="width: 100%" />
          </el-form-item>
        </div>

        <el-divider content-position="left">命中条件</el-divider>
        <div class="pair">
          <el-form-item label="日期区间起">
            <el-date-picker
              v-model="form.validFrom"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="不限"
              style="width: 100%"
            />
          </el-form-item>
          <el-form-item label="日期区间止">
            <el-date-picker
              v-model="form.validTo"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="只覆盖起那天"
              style="width: 100%"
            />
          </el-form-item>
        </div>

        <div class="field">
          <label>星期（不勾 = 不限）</label>
          <el-checkbox-group v-model="form.weekdays" class="days">
            <el-checkbox v-for="(cn, i) in DAY_LABELS" :key="i + 1" :value="i + 1">
              {{ cn }}
            </el-checkbox>
          </el-checkbox-group>
        </div>

        <div class="pair">
          <el-form-item label="时段起（留空 = 全天）">
            <el-time-picker v-model="form.startHour" value-format="HH:mm:ss" placeholder="22:00" style="width: 100%" />
          </el-form-item>
          <el-form-item label="时段止">
            <el-time-picker v-model="form.endHour" value-format="HH:mm:ss" placeholder="次日 02:00" style="width: 100%" />
          </el-form-item>
        </div>
        <div class="tip">起 &gt; 止 表示跨零点窗口，例如 22:00 → 02:00。按半开区间判，止时刻整不命中。</div>

        <el-divider content-position="left">怎么改价</el-divider>
        <div class="pair">
          <el-form-item label="计价方式" prop="mode">
            <el-radio-group v-model="form.mode">
              <el-radio value="MULTIPLY">倍率</el-radio>
              <el-radio value="OVERRIDE">一口价</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item :label="form.mode === 'OVERRIDE' ? '单价（元）' : '倍率'" prop="value">
            <el-input-number
              v-model="form.value"
              :min="0"
              :precision="2"
              :step="form.mode === 'OVERRIDE' ? 10 : 0.1"
              style="width: 100%"
            />
          </el-form-item>
        </div>
        <el-form-item label="改的是哪一笔钱" prop="appliesTo">
          <el-radio-group v-model="form.appliesTo">
            <el-radio value="ALL">房费与超时单价都改</el-radio>
            <el-radio value="BASE">只改房费（起步价）</el-radio>
            <el-radio value="OVERAGE">只改超时单价</el-radio>
          </el-radio-group>
        </el-form-item>
        <div class="tip">
          倍率是"在平时价上乘"，1.20 = 上浮 20%、0.85 = 打 85 折；一口价直接把命中时段的单价改成这个数。
          <b>房费是打包价、超时是元/小时，两种量纲不能共用一个数</b> ——
          "假日一口价 488"若连小时价也变成 488，延迟退房一小时就会收 976，所以这类规则要选「只改房费」。
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

const DAY_LABELS = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']

const rows = ref([])
const properties = ref([])
const typeOptions = ref([])
const filterProperty = ref(null)
const loading = ref(false)
const saving = ref(false)
const dialog = ref(false)
const formRef = ref()
const form = reactive(empty())

const rules = {
  name: [{ required: true, message: '请输入规则名称', trigger: 'blur' }],
  propertyId: [{ required: true, message: '请选择民宿', trigger: 'change' }],
  mode: [{ required: true, message: '请选择计价方式', trigger: 'change' }],
  value: [{ required: true, message: '请输入数值', trigger: 'blur' }]
}

function empty() {
  return {
    id: null,
    propertyId: null,
    roomTypeId: null,
    name: '',
    validFrom: null,
    validTo: null,
    weekdays: [],
    startHour: null,
    endHour: null,
    mode: 'MULTIPLY',
    value: 1.2,
    appliesTo: 'ALL',
    priority: 100,
    enabled: true
  }
}

async function load() {
  loading.value = true
  try {
    const q = filterProperty.value ? `?propertyId=${filterProperty.value}` : ''
    rows.value = (await http.get(`/rate-rules${q}`)).data
  } finally {
    loading.value = false
  }
}

/** 只是把这家民宿的房型选项装进下拉框，不动已选值。 */
async function loadTypes(propertyId) {
  typeOptions.value = propertyId ? (await http.get(`/room-types?propertyId=${propertyId}`)).data : []
}

/** 用户主动换民宿时才必须清掉房型 —— 上一家的房型 id 拿到这一家没有意义。 */
async function onProperty(id) {
  form.roomTypeId = null
  await loadTypes(id)
}

function openCreate() {
  Object.assign(form, empty(), {
    propertyId: filterProperty.value ?? properties.value[0]?.id ?? null
  })
  loadTypes(form.propertyId)
  dialog.value = true
}

/**
 * 编辑回填。这里刻意调 loadTypes 而不是 onProperty：
 * 后者第一句就把 roomTypeId 清空，于是"打开编辑框"这个动作本身会悄悄改掉规则的作用域，
 * 保存之后一条房型级规则变成民宿通用 —— 审阅第 4 条抓的就是这个。
 */
function openEdit(row) {
  Object.assign(form, empty(), { ...row, weekdays: row.weekdays || [] })
  loadTypes(row.propertyId)
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
    const payload = { ...form, weekdays: form.weekdays || [] }
    if (form.id) {
      await http.put(`/rate-rules/${form.id}`, payload)
    } else {
      await http.post('/rate-rules', payload)
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

/** 启停只是改一个布尔，但仍旧走完整的 PUT —— 免得开一条"只改 enabled"的旁路接口，
 *  那条路会绕过所有参数校验。 */
async function toggle(row) {
  try {
    await http.put(`/rate-rules/${row.id}`, { ...row, enabled: !row.enabled })
    row.enabled = !row.enabled
    ElMessage.success(row.enabled ? '已启用' : '已停用，不再参与计价')
  } catch {
    /* 拦截器已提示，开关保持原值 */
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(
      `删除规则「${row.name}」？已结算的账单不受影响（账单里存了当时的单价与规则名）。`,
      '确认删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await http.delete(`/rate-rules/${row.id}`)
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
.field > label {
  display: block;
  margin-bottom: 8px;
  font-size: var(--bnb-fs-callout);
  font-weight: 500;
  color: var(--bnb-text-secondary);
}
.days {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 0;
  margin-bottom: var(--bnb-space-4);
}
.tip {
  margin: -8px 0 var(--bnb-space-4);
  font-size: var(--bnb-fs-footnote);
  line-height: 1.5;
  color: var(--bnb-text-secondary);
}
.scope {
  font-size: var(--bnb-fs-footnote);
  color: var(--bnb-text-secondary);
}
.mode {
  margin-left: 6px;
  font-size: var(--bnb-fs-footnote);
}
</style>
