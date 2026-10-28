// 前端判断"这一格能不能开单"的唯一出处。
//
// 为什么要单独成模块：这段逻辑原本写在 RoomCalendarView 的脚本里，是同一套规则的**第四份实现**
// （后端 lockOverlapping、RoomMapper.findAvailable、SellPolicy，再加前端）。第四份最容易漂：
// 审阅就抓到过一次——后端按"整点切割"算营业日、前端按"小时格子"画，切割点带分钟时两边对不上。
// 抽出来之后它至少能被单测钉住（sellable.test.js），不再靠"看着对"。
//
// 口径必须与后端 lockOverlapping 的**三个分支**一致：
//   ① 区间相交；② 超时未退房持续占用；③ 未确认的清扫持续挡房。
import { addMinutes, parse } from './dtime'

const pad = (n) => String(n).padStart(2, '0')

/**
 * 某个占用块是否与请求区间冲突。
 *
 * @param b   后端 /api/calendar 返回的块 { kind, bookingStatus, blockStart, blockEnd }
 * @param from 请求区间的起点（已含新单自己的入住前缓冲）
 * @param to   请求区间的终点（已含退房后缓冲）
 * @param now  当前时刻，测试时注入，生产用 new Date()
 */
export function conflicts(b, from, to, now = new Date()) {
  const start = parse(b.blockStart)
  const end = parse(b.blockEnd)
  // ③ 待清扫：没点"清扫完成"之前，从清扫起点起往后一直挡（包括今天晚些和以后的日子）
  if (b.kind === 'CLEANING' && to > start) return true
  // ② 超时未退房：已过计划离店块，且请求触到那之后 —— 未到期不拦，
  //    否则"客人 14:00 入住、先预卖今晚 20:00 之后"这种正常操作会被一起挡掉
  if (b.bookingStatus === 'IN_USE' && now >= end && to > end) return true
  // ① 区间相交（半开区间：边界相接不算冲突）
  return start < to && end > from
}

/**
 * 某个格子（入住时刻 = from）能不能开单。
 *
 * @param room     { status, blocks, cleanBeforeMinutes, cleanAfterMinutes, minHours }
 * @param from     格子对应的入住时刻
 * @param openTime 该店可售窗口起（"08:00:00"）
 * @param closeTime 该店可售窗口止
 */
export function cellIsSellable(room, from, openTime, closeTime, now = new Date()) {
  if (room.status !== 'ON_SALE') return false
  const t = `${pad(from.getHours())}:${pad(from.getMinutes())}`
  // 可售窗口约束的是"入住时刻"，与后端的 SellPolicy 一致
  if (t < openTime.slice(0, 5) || t > closeTime.slice(0, 5)) return false

  // 新单自己的缓冲也要算进来：只比 from/to 会漏判
  // （已有块到 12:45、新单 13:00 入住、入住前缓冲 30 分钟 → 新 block 从 12:30 起，真冲突）
  const effStart = addMinutes(from, -(room.cleanBeforeMinutes || 0))
  const effEnd = addMinutes(from, (room.minHours || 1) * 60 + (room.cleanAfterMinutes || 0))
  return !room.blocks.some((b) => conflicts(b, effStart, effEnd, now))
}
