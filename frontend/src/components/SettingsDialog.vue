<template>
  <el-dialog v-model="visible" title="设置" width="480" append-to-body>
    <div class="grp">
      <div class="grp__title">外观</div>
      <div class="row">
        <div class="row__text">
          <span>配色模式</span>
          <small class="bnb-muted">跟随系统会实时响应操作系统的深/浅色切换</small>
        </div>
        <el-radio-group v-model="modePick" size="small">
          <el-radio-button v-for="m in THEME_MODES" :key="m.value" :value="m.value">
            {{ m.label }}
          </el-radio-button>
        </el-radio-group>
      </div>
      <div class="row">
        <div class="row__text">
          <span>界面密度</span>
          <small class="bnb-muted">紧凑只收间距与控件高度，字号不变</small>
        </div>
        <el-radio-group v-model="densityPick" size="small">
          <el-radio-button v-for="d in DENSITIES" :key="d.value" :value="d.value">
            {{ d.label }}
          </el-radio-button>
        </el-radio-group>
      </div>
      <div class="row">
        <div class="row__text">
          <span>侧栏显示导航文字</span>
          <small class="bnb-muted">关掉后侧栏收成一条图标栏，给表格多留宽度</small>
        </div>
        <el-switch v-model="labelsPick" />
      </div>
    </div>

    <div class="grp">
      <div class="grp__title">关于</div>
      <dl class="about">
        <div><dt>系统</dt><dd>民宿房源管理与分时预订系统</dd></div>
        <div><dt>形态</dt><dd>单机部署 · Vue 3 + Spring Boot 3 + MySQL 8</dd></div>
        <div><dt>演示账号</dt><dd class="bnb-num">admin / staff · Admin@123</dd></div>
        <div>
          <dt>偏好存储</dt>
          <dd>
            只写在这台浏览器的 localStorage，不上传服务端 —— 换浏览器或清缓存后需要重设一次
          </dd>
        </div>
      </dl>
    </div>

    <template #footer>
      <div class="dlg-actions">
        <el-button text @click="reset">恢复默认</el-button>
        <el-button type="primary" @click="visible = false">完成</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed } from 'vue'
import { DENSITIES, PREF_KEYS, THEME_MODES, useTheme } from '../composables/useTheme'

const visible = defineModel({ type: Boolean })
const { mode, setMode, density, setDensity, navLabels, setNavLabels } = useTheme()

// 界面上直接改这几个 ref 也能生效，但那样 localStorage 就不写了 ——
// 刷新一下"设置就没了"。所以三档都走 setXxx，把持久化当成唯一的写入口
const modePick = computed({ get: () => mode.value, set: (v) => setMode(v) })
const densityPick = computed({ get: () => density.value, set: (v) => setDensity(v) })
const labelsPick = computed({ get: () => navLabels.value, set: (v) => setNavLabels(v) })

function reset() {
  PREF_KEYS.forEach((k) => localStorage.removeItem(k))
  setMode('auto')
  setDensity('comfortable')
  setNavLabels(true)
}
</script>

<style scoped>
.grp + .grp {
  margin-top: var(--bnb-space-6);
  padding-top: var(--bnb-space-5);
  border-top: 1px solid var(--bnb-separator);
}

.grp__title {
  font-size: var(--bnb-fs-caption);
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--bnb-text-tertiary);
  margin-bottom: var(--bnb-space-3);
}

.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--bnb-space-4);
  padding: var(--bnb-space-2) 0;
}

.row__text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.row__text span {
  font-size: var(--bnb-fs-callout);
  font-weight: 500;
  color: var(--bnb-text-primary);
}
.row__text small {
  font-size: var(--bnb-fs-caption);
  line-height: 1.4;
}

.about {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: var(--bnb-space-2);
}
.about > div {
  display: flex;
  gap: var(--bnb-space-3);
  font-size: var(--bnb-fs-callout);
}
.about dt {
  flex-shrink: 0;
  width: 68px;
  color: var(--bnb-text-tertiary);
  font-weight: 400;
}
.about dd {
  margin: 0;
  color: var(--bnb-text-primary);
  line-height: 1.5;
}

.dlg-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--bnb-space-2);
}
</style>
