// 全链路 naive 本地时间（实施计划 7.1）：后端给的是 "2026-12-01T06:00:00" 这种
// 不带时区偏移的 ISO 串。JS 按规范把无偏移的 date-time 当本地时间解析，
// 和后端同源，所以这里刻意不引入 dayjs/moment —— 引了反而容易把偏移算进来。

export function parse(s) {
  if (!s) return null
  const [d, t = '00:00:00'] = String(s).split('T')
  const [y, m, day] = d.split('-').map(Number)
  const [hh, mm, ss = 0] = t.split(':').map(Number)
  return new Date(y, m - 1, day, hh, mm, ss)
}

const pad = (n) => String(n).padStart(2, '0')

/** LocalDateTime 形态：YYYY-MM-DDTHH:mm:ss */
export function fmt(dt) {
  return `${dt.getFullYear()}-${pad(dt.getMonth() + 1)}-${pad(dt.getDate())}T`
    + `${pad(dt.getHours())}:${pad(dt.getMinutes())}:${pad(dt.getSeconds())}`
}

/** 日期形态，给 el-date-picker 用 */
export function fmtDate(dt) {
  return `${dt.getFullYear()}-${pad(dt.getMonth() + 1)}-${pad(dt.getDate())}`
}

export function addMinutes(dt, mins) {
  return new Date(dt.getTime() + mins * 60000)
}

export function diffMinutes(a, b) {
  return Math.round((b.getTime() - a.getTime()) / 60000)
}

export function clock(dt) {
  return `${pad(dt.getHours())}:${pad(dt.getMinutes())}`
}

/** 125 → "2小时5分钟"；0 → "0分钟" */
export function humanMinutes(mins) {
  const h = Math.floor(mins / 60)
  const m = mins % 60
  if (h === 0) return `${m}分钟`
  return m === 0 ? `${h}小时` : `${h}小时${m}分`
}
