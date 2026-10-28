import { describe, expect, it } from 'vitest'
import { cellIsSellable, conflicts } from './sellable'

// 这些用例和后端 OccupancyTruthIT / lockOverlapping 的三条分支一一对应。
// 前端的判定是"第四份实现"，唯一能防止它漂的办法就是让它也被测住。

const at = (s) => new Date(s)
const block = (kind, status, start, end) => ({
  kind, bookingStatus: status, blockStart: start, blockEnd: end
})
const room = (blocks, over = {}) => ({
  status: 'ON_SALE',
  minHours: 2,
  cleanBeforeMinutes: 0,
  cleanAfterMinutes: 0,
  blocks,
  ...over
})

describe('conflicts：与后端 lockOverlapping 同口径', () => {
  it('区间相交算冲突', () => {
    const b = block('ROOM', 'CONFIRMED', '2026-12-01T10:00:00', '2026-12-01T12:00:00')
    expect(conflicts(b, at('2026-12-01T11:00:00'), at('2026-12-01T13:00:00'))).toBe(true)
  })

  it('边界相接不算冲突（半开区间）', () => {
    const b = block('ROOM', 'CONFIRMED', '2026-12-01T10:00:00', '2026-12-01T12:00:00')
    expect(conflicts(b, at('2026-12-01T12:00:00'), at('2026-12-01T14:00:00'))).toBe(false)
    expect(conflicts(b, at('2026-12-01T08:00:00'), at('2026-12-01T10:00:00'))).toBe(false)
  })

  it('待清扫：预计时长已过也继续挡（确认式闸门）', () => {
    // 12:00 退房、30 分钟清扫窗，13:00 之后仍然不可售
    const b = block('CLEANING', 'CHECKED_OUT', '2026-12-01T12:00:00', '2026-12-01T12:30:00')
    expect(conflicts(b, at('2026-12-01T14:00:00'), at('2026-12-01T16:00:00'))).toBe(true)
    // 明天也挡 —— 保洁没点确认之前这间房没有"可售"这个信号
    expect(conflicts(b, at('2026-12-02T14:00:00'), at('2026-12-02T16:00:00'))).toBe(true)
  })

  it('超时未退房：已过计划离店块才持续占用，未到期不拦', () => {
    const b = block('ROOM', 'IN_USE', '2026-12-01T09:00:00', '2026-12-01T12:00:00')
    // 现在还不到 12:00 → 只按固定区间算，20:00 之后的新单照样能卖
    expect(conflicts(b, at('2026-12-01T20:00:00'), at('2026-12-01T22:00:00'), at('2026-12-01T10:00:00')))
      .toBe(false)
    // 已经过了 12:00 客人还没走 → 13:00 之后的新单必须被挡
    expect(conflicts(b, at('2026-12-01T20:00:00'), at('2026-12-01T22:00:00'), at('2026-12-01T13:00:00')))
      .toBe(true)
  })
})

describe('cellIsSellable：格子能不能点', () => {
  const open = '08:00:00'
  const close = '22:00:00'

  it('在售、窗口内、无冲突 → 可售', () => {
    expect(cellIsSellable(room([]), at('2026-12-01T10:00:00'), open, close)).toBe(true)
  })

  it('非在售房间一律不可售', () => {
    expect(cellIsSellable(room([], { status: 'MAINTENANCE' }), at('2026-12-01T10:00:00'), open, close))
      .toBe(false)
  })

  it('可售窗口只约束入住时刻', () => {
    expect(cellIsSellable(room([]), at('2026-12-01T07:00:00'), open, close)).toBe(false)
    expect(cellIsSellable(room([]), at('2026-12-01T23:00:00'), open, close)).toBe(false)
  })

  it('结束时刻也要落在窗口内：20:00 起默认卖 3 小时就撞上 22:00 关窗', () => {
    // 修复前只校验入住时刻 → 20:00 的格子可点，提交后被后端拒（"结束超出窗口"）
    expect(cellIsSellable(room([]), at('2026-12-01T20:00:00'), open, close)).toBe(false)
    // 19:00 起 3 小时正好 22:00，落在窗口内（半开区间，等于关窗时刻仍可）
    expect(cellIsSellable(room([]), at('2026-12-01T19:00:00'), open, close)).toBe(true)
    // 起步 6 小时的房型：17:00 起 6 小时 = 23:00，同样超窗
    expect(cellIsSellable(room([], { minHours: 6 }), at('2026-12-01T17:00:00'), open, close)).toBe(false)
  })

  it('新单自己的入住前缓冲也要算进来', () => {
    // 已有块到 12:45；13:00 入住 + 30 分钟入住前缓冲 → 12:30 起就撞上了
    const blocks = [block('ROOM', 'CONFIRMED', '2026-12-01T10:00:00', '2026-12-01T12:45:00')]
    expect(cellIsSellable(room(blocks, { cleanBeforeMinutes: 30 }), at('2026-12-01T13:00:00'), open, close))
      .toBe(false)
    // 14:00 入住 + 缓冲 13:30 → 不撞
    expect(cellIsSellable(room(blocks, { cleanBeforeMinutes: 30 }), at('2026-12-01T14:00:00'), open, close))
      .toBe(true)
  })
})
