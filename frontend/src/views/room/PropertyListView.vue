<template>
  <div class="bnb-page">
    <div class="bnb-page__head">
      <div>
        <div class="bnb-page__title">民宿</div>
        <div class="bnb-page__sub">营业日切割点决定报表把凌晨算到哪一天，可售窗口只约束钟点房</div>
      </div>
      <el-button type="primary" @click="openCreate">
        <el-icon><Plus /></el-icon>新建民宿
      </el-button>
    </div>

    <div class="bnb-card">
      <el-table v-loading="loading" :data="rows" style="width: 100%">
        <el-table-column prop="name" label="名称" min-width="150" />
        <el-table-column prop="address" label="地址" min-width="200">
          <template #default="{ row }">
            <span :class="{ 'bnb-muted': !row.address }">{{ row.address || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="营业日切割" width="120">
          <template #default="{ row }"><span class="bnb-num">{{ row.dayCutTime }}</span></template>
        </el-table-column>
        <el-table-column label="可售窗口" width="150">
          <template #default="{ row }">
            <span class="bnb-num">{{ row.openTime }}–{{ row.closeTime }}</span>
          </template>
        </el-table-column>
        <el-table-column label="房型" width="80" align="right">
          <template #default="{ row }"><span class="bnb-num">{{ row.roomTypeCount }}</span></template>
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
          <el-empty description="还没有民宿，先建一家" />
        </template>
      </el-table>
    </div>

    <el-dialog v-model="dialog" :title="form.id ? '编辑民宿' : '新建民宿'" width="480">
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="云栖民宿" />
        </el-form-item>
        <el-form-item label="地址">
          <el-input v-model="form.address" placeholder="杭州市西湖区龙井路 18 号" />
        </el-form-item>
        <el-form-item label="营业日切割点" prop="dayCutTime">
          <el-time-picker
            v-model="form.dayCutTime"
            value-format="HH:mm:ss"
            :step="60"
            placeholder="06:00:00"
            style="width: 100%"
          />
          <div class="tip">
            此刻之前的订单算前一天。只能选整点：报表的一格就是一小时，
            带分钟的切割点会让"归哪一天"和"画在第几格"对不上。全日房退房在中午，切在凌晨 6 点最稳。
          </div>
        </el-form-item>
        <div class="pair">
          <el-form-item label="开窗时间" prop="openTime">
            <el-time-picker v-model="form.openTime" value-format="HH:mm:ss" style="width: 100%" />
          </el-form-item>
          <el-form-item label="关窗时间" prop="closeTime">
            <el-time-picker v-model="form.closeTime" value-format="HH:mm:ss" style="width: 100%" />
          </el-form-item>
        </div>
        <div class="tip">可售窗口只约束钟点房，全日房模板不受它限制。</div>
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
const loading = ref(false)
const saving = ref(false)
const dialog = ref(false)
const formRef = ref()
const form = reactive(empty())

const rules = {
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
  dayCutTime: [{ required: true, message: '请选择切割点', trigger: 'change' }],
  openTime: [{ required: true, message: '请选择开窗时间', trigger: 'change' }],
  closeTime: [{ required: true, message: '请选择关窗时间', trigger: 'change' }]
}

function empty() {
  return {
    id: null,
    name: '',
    address: '',
    dayCutTime: '06:00:00',
    openTime: '08:00:00',
    closeTime: '22:00:00'
  }
}

async function load() {
  loading.value = true
  try {
    rows.value = (await http.get('/properties')).data
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(form, empty())
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
    const body = { ...form }
    if (form.id) {
      await http.put(`/properties/${form.id}`, body)
    } else {
      await http.post('/properties', body)
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
      `删除「${row.name}」？该民宿下若还有房型或房间会被拒绝。`,
      '确认删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await http.delete(`/properties/${row.id}`)
    ElMessage.success('已删除')
    await load()
  } catch {
    /* 409/2013 的提示由拦截器给出 */
  }
}

onMounted(load)
</script>

<style scoped>
.danger {
  color: var(--bnb-danger);
}
.pair {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--bnb-space-4);
}
.tip {
  margin-top: -4px;
  font-size: var(--bnb-fs-footnote);
  line-height: 1.5;
  color: var(--bnb-text-secondary);
}
</style>
