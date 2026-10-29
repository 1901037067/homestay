<template>
  <div class="bnb-page">
    <div class="bnb-page__head">
      <div>
        <div class="bnb-page__title">房间</div>
        <div class="bnb-page__sub">下架（OFFLINE）是删除的替代动作 —— 有历史订单的房间不允许物理删除</div>
      </div>
      <el-button type="primary" @click="openCreate">
        <el-icon><Plus /></el-icon>新建房间
      </el-button>
    </div>

    <div class="bnb-card">
      <div class="bnb-card__head">
        <div class="bnb-toolbar">
          <el-input
            v-model="query.keyword"
            placeholder="搜索房号"
            clearable
            style="width: 170px"
            @keyup.enter="reload"
            @clear="reload"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-select v-model="query.propertyId" placeholder="全部民宿" clearable style="width: 170px" @change="reload">
            <el-option v-for="p in properties" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
          <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 140px" @change="reload">
            <el-option v-for="s in STATUSES" :key="s.value" :label="s.label" :value="s.value" />
          </el-select>
          <el-button text @click="reload">查询</el-button>
        </div>
        <span class="bnb-muted count">共 {{ total }} 间</span>
      </div>

      <el-table v-loading="loading" :data="rows" style="width: 100%">
        <el-table-column prop="roomNo" label="房号" width="110" />
        <el-table-column prop="propertyName" label="民宿" min-width="120" />
        <el-table-column prop="roomTypeName" label="房型" min-width="140" />
        <el-table-column label="楼层" width="90" align="right">
          <template #default="{ row }">
            <span class="bnb-num" :class="{ 'bnb-muted': row.floorNo == null }">
              {{ row.floorNo == null ? '—' : `${row.floorNo}F` }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <span class="bnb-pill" :class="pillClass(row.status)">
              <span class="bnb-pill__dot" />{{ statusLabel(row.status) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="230" align="right" fixed="right">
          <template #default="{ row }">
            <el-button text @click="openEdit(row)">编辑</el-button>
            <el-dropdown trigger="click" @command="(s) => changeStatus(row, s)">
              <el-button text>状态</el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item
                    v-for="s in STATUSES.filter((x) => x.value !== row.status)"
                    :key="s.value"
                    :command="s.value"
                  >
                    {{ s.label }}
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <el-button text class="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="没有符合条件的房间" />
        </template>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :page-sizes="[10, 20, 50]"
          :total="total"
          layout="sizes, prev, pager, next"
          background
          @current-change="load"
          @size-change="reload"
        />
      </div>
    </div>

    <el-dialog v-model="dialog" :title="form.id ? '编辑房间' : '新建房间'" width="480">
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <el-form-item label="所属民宿" prop="propertyId">
          <el-select v-model="form.propertyId" placeholder="请选择" style="width: 100%" @change="onPropertyChange">
            <el-option v-for="p in properties" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="房型" prop="roomTypeId">
          <el-select v-model="form.roomTypeId" placeholder="请选择" style="width: 100%">
            <el-option
              v-for="t in roomTypes.filter((x) => x.propertyId === form.propertyId)"
              :key="t.id"
              :label="`${t.name}（¥${t.basePrice}）`"
              :value="t.id"
            />
          </el-select>
        </el-form-item>
        <div class="pair">
          <el-form-item label="房号" prop="roomNo">
            <el-input v-model="form.roomNo" placeholder="1201" />
          </el-form-item>
          <el-form-item label="楼层">
            <el-input-number v-model="form.floorNo" :min="-5" :max="99" style="width: 100%" />
          </el-form-item>
        </div>
        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="form.status">
            <el-radio v-for="s in STATUSES" :key="s.value" :value="s.value">{{ s.label }}</el-radio>
          </el-radio-group>
        </el-form-item>
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
import { Plus, Search } from '@element-plus/icons-vue'
import http from '../../api'

const STATUSES = [
  { value: 'ON_SALE', label: '在售', pill: 'bnb-pill--success' },
  { value: 'MAINTENANCE', label: '维修中', pill: 'bnb-pill--warning' },
  { value: 'OFFLINE', label: '已下架', pill: '' }
]

const rows = ref([])
const total = ref(0)
const properties = ref([])
const roomTypes = ref([])
const loading = ref(false)
const saving = ref(false)
const dialog = ref(false)
const formRef = ref()
const query = reactive({ page: 1, size: 20, propertyId: null, status: null, keyword: '' })
const form = reactive(empty())

const rules = {
  propertyId: [{ required: true, message: '请选择民宿', trigger: 'change' }],
  roomTypeId: [{ required: true, message: '请选择房型', trigger: 'change' }],
  roomNo: [{ required: true, message: '请输入房号', trigger: 'blur' }],
  status: [{ required: true, message: '请选择状态', trigger: 'change' }]
}

function empty() {
  return { id: null, propertyId: null, roomTypeId: null, roomNo: '', floorNo: 1, status: 'ON_SALE' }
}

const statusLabel = (v) => STATUSES.find((s) => s.value === v)?.label ?? v
const pillClass = (v) => STATUSES.find((s) => s.value === v)?.pill ?? ''

async function load() {
  loading.value = true
  try {
    const params = new URLSearchParams({ page: query.page, size: query.size })
    if (query.propertyId) params.set('propertyId', query.propertyId)
    if (query.status) params.set('status', query.status)
    if (query.keyword) params.set('keyword', query.keyword)
    const res = await http.get(`/rooms?${params.toString()}`)
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

function openCreate() {
  Object.assign(form, empty(), { propertyId: query.propertyId ?? properties.value[0]?.id ?? null })
  dialog.value = true
}

function openEdit(row) {
  // 先回到空表单再合并：后端配了 non_null，值为 null 的字段会整个消失，
  // 不先清空的话"编辑一个没填楼层的房间"会沿用上一次编辑的那层楼
  Object.assign(form, empty(), { ...row })
  dialog.value = true
}

function onPropertyChange() {
  form.roomTypeId = null
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
      await http.put(`/rooms/${form.id}`, { ...form })
    } else {
      await http.post('/rooms', { ...form })
    }
    ElMessage.success('已保存')
    dialog.value = false
    await load()
  } catch {
    /* 拦截器已提示（含同民宿下房号重复） */
  } finally {
    saving.value = false
  }
}

async function changeStatus(row, status) {
  try {
    await http.patch(`/rooms/${row.id}/status`, { status })
    ElMessage.success(`「${row.roomNo}」已改为${statusLabel(status)}`)
    await load()
  } catch {
    /* 拦截器已提示 */
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(
      `删除房间「${row.roomNo}」？只要它有过订单就会被拒绝，建议改为下架。`,
      '确认删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await http.delete(`/rooms/${row.id}`)
    ElMessage.success('已删除')
    await load()
  } catch {
    /* ROOM_HAS_HISTORY 的提示由拦截器给出 */
  }
}

onMounted(async () => {
  properties.value = (await http.get('/properties')).data
  roomTypes.value = (await http.get('/room-types')).data
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
.pager {
  padding: var(--bnb-space-3) var(--bnb-space-6) var(--bnb-space-5);
}
.pair {
  display: grid;
  grid-template-columns: 2fr 3fr;
  gap: var(--bnb-space-4);
}
</style>
