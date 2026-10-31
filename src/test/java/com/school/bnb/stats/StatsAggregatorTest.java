package com.school.bnb.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.school.bnb.vo.StatRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 报表聚合的表驱动单测 —— 和 {@code PriceCalculatorTest} 一样，<b>不连库、不读时钟</b>。
 *
 * <p>报表最容易错的地方从来不是"求和"，而是归属：这一笔算哪一天、跨零点怎么摊、
 * 取消的要不算、退了房没结账的算不算营收。每条都在这儿钉一遍，
 * 而且都能被答辩追问时当场演示。
 */
class StatsAggregatorTest {

    private static final LocalTime CUT_0600 = LocalTime.of(6, 0);
    private static final LocalDate D1 = LocalDate.of(2026, 11, 10);
    private static final LocalDate D2 = LocalDate.of(2026, 11, 11);

    // ---------------------------------------------------------------- 归一化

    @ParameterizedTest(name = "{0} 在切割点 {1} 下属于营业日 {2}（横轴第 {3} 格）")
    @CsvSource({
            // 入住时刻,          切割点,  归属营业日,        横轴下标
            "2026-11-11T02:00,   06:00,  2026-11-10,      20",
            "2026-11-11T05:59,   06:00,  2026-11-10,      23",
            "2026-11-11T06:00,   06:00,  2026-11-11,       0",
            "2026-11-11T23:00,   06:00,  2026-11-11,      17",
            "2026-11-11T05:00,   04:00,  2026-11-11,       1",   // 同一时刻，切割点不同 → 归属不同
            "2026-11-11T03:59,   04:00,  2026-11-10,      23"
    })
    @DisplayName("营业日归属与横轴旋转：06:00 前算前一天，横轴从切割点起转")
    void businessDayAndSlot(String at, String cut, String expectDay, int expectSlot) {
        LocalDateTime t = LocalDateTime.parse(at);
        LocalTime dayCut = LocalTime.parse(cut);

        assertThat(StatsAggregator.businessDay(t, dayCut.getHour(), dayCut.getMinute()))
                .isEqualTo(LocalDate.parse(expectDay));
        assertThat(StatsAggregator.slotOf(t, dayCut.getHour())).isEqualTo(expectSlot);
    }

    @Test
    @DisplayName("切割点是配置不是写死：04:00 的店会把同一笔 05:00 的单算到今天")
    void cutTimeComesFromConfig() {
        StatRow fiveAm = row(1L, 1L, "101", "IN_USE", "2026-11-11T05:00", "2026-11-11T08:00", null, "0");

        var at0600 = StatsAggregator.aggregate(new StatsAggregator.Input(
                D1, D2, CUT_0600, 10, List.of(fiveAm)));
        var at0400 = StatsAggregator.aggregate(new StatsAggregator.Input(
                D1, D2, LocalTime.of(4, 0), 10, List.of(fiveAm)));

        assertThat(at0600.daily().get(0).soldRooms()).as("06:00 切割 → 算前一天").isEqualTo(1);
        assertThat(at0600.daily().get(1).soldRooms()).isZero();
        assertThat(at0400.daily().get(0).soldRooms()).as("04:00 切割 → 05:00 已是新的一天").isZero();
        assertThat(at0400.daily().get(1).soldRooms()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- 归属

    @Nested
    class Attribution {

        @Test
        @DisplayName("跨零点全日房整段算入住那天的房晚，但热力图要摊到两个营业日")
        void fullDayCountsOnceButSpreadsHeat() {
            // 11-10 14:00 → 11-11 12:00
            StatRow fullDay = row(1L, 1L, "201", "SETTLED", "2026-11-10T14:00", "2026-11-11T12:00", null, "388");

            var r = StatsAggregator.aggregate(new StatsAggregator.Input(
                    D1, D2, CUT_0600, 10, List.of(fullDay)));

            assertThat(r.daily().get(0).soldRooms()).as("房晚只算入住那天一间").isEqualTo(1);
            assertThat(r.daily().get(1).soldRooms()).as("不会被劈成两天").isZero();
            assertThat(r.totalSoldRoomDays()).isEqualTo(1);

            // 热力按小时摊，且营业日是 06:00→次日 06:00：
            //   11-10 这个营业日 = 11-10 的 14:00–23:00（10 格）+ 11-11 的 00:00–05:00（6 格）
            //   11-11 这个营业日 = 11-11 的 06:00–11:00（6 格）
            // 第一版我按"自然日各 10 / 12 格"写期望，测试当场把这条算错的口径打红
            assertThat(r.heat()).filteredOn(h -> h.businessDay().equals(D1) && h.occupiedRooms() > 0)
                    .hasSize(16);
            assertThat(r.heat()).filteredOn(h -> h.businessDay().equals(D2) && h.occupiedRooms() > 0)
                    .hasSize(6);
        }

        @Test
        @DisplayName("连住三晚：每个营业日各记一次房晚，但单数与营收只算入住那天")
        void multiNightSpreadsRoomNightsButNotRevenue() {
            StatRow stay = row(1L, 1L, "201", "SETTLED",
                    "2026-11-10T14:00", "2026-11-13T12:00", null, "1164.00");
            stay.setNights(3);

            var r = StatsAggregator.aggregate(new StatsAggregator.Input(
                    D1, D2, CUT_0600, 10, List.of(stay)));

            assertThat(r.daily().get(0).soldRooms()).as("11-10 那晚").isEqualTo(1);
            assertThat(r.daily().get(1).soldRooms()).as("11-11 那晚这间房同样是卖出去的").isEqualTo(1);
            assertThat(r.totalSoldRoomDays()).as("区间内两晚 = 2 个房晚，不是 1").isEqualTo(2);

            assertThat(r.daily().get(0).bookings()).isEqualTo(1);
            assertThat(r.daily().get(1).bookings()).as("一张单只数一次，不能跟着房晚翻倍").isZero();
            assertThat(r.daily().get(1).revenue()).isEqualByComparingTo("0.00");
            assertThat(r.totalRevenue()).isEqualByComparingTo("1164.00");
        }

        @Test
        @DisplayName("入住日落在区间外的连住单，区间内的每一晚仍要计房晚")
        void roomNightsSurviveTheRangeFilterThatProtectsRevenue() {
            // 11-09 入住连住三晚，晚上分别落在 11-09 / 11-10 / 11-11；查询窗口只有后两天。
            // 房晚的撒点必须排在"入住日不在区间就 continue"之前 —— 否则"只查今天"
            // 会把今天实际被占住的房间整个漏掉，和热力图当初那个 bug 同一个形状
            StatRow stay = row(1L, 1L, "201", "SETTLED",
                    "2026-11-09T14:00", "2026-11-12T12:00", null, "1164.00");
            stay.setNights(3);

            var r = StatsAggregator.aggregate(new StatsAggregator.Input(
                    D1, D2, CUT_0600, 10, List.of(stay)));

            assertThat(r.daily().get(0).soldRooms())
                    .as("11-10 这一晚这间房确实被卖出去了").isEqualTo(1);
            assertThat(r.daily().get(0).bookings())
                    .as("但单数与营收仍归入住那天，不能顺着房晚混进来").isZero();
            assertThat(r.totalRevenue()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("凌晨的单归前一个营业日，热力格落在旋转后的尾部而不是开头")
        void smallHourBookingLandsAtTheTailOfTheAxis() {
            // 11-11 02:00–05:00 → 属于 11-10 这个营业日，横轴第 20、21、22 格
            StatRow night = row(1L, 1L, "103", "SETTLED", "2026-11-11T02:00", "2026-11-11T05:00", null, "88");

            var r = StatsAggregator.aggregate(new StatsAggregator.Input(
                    D1, D2, CUT_0600, 10, List.of(night)));

            assertThat(r.heat()).hasSize(3).allSatisfy(h -> {
                assertThat(h.businessDay()).isEqualTo(D1);
                assertThat(h.occupiedRooms()).isEqualTo(1);
            });
            assertThat(r.heat()).extracting(StatsAggregator.Heat::slot).containsExactly(20, 21, 22);
            assertThat(r.heat()).extracting(StatsAggregator.Heat::hour).containsExactly(2, 3, 4);
        }

        @Test
        @DisplayName("取消与超时释放的单：不占房晚、不进营收、不进热度")
        void voidBookingsAreIgnored() {
            List<StatRow> rows = List.of(
                    row(1L, 1L, "101", "CANCELLED", "2026-11-10T10:00", "2026-11-10T13:00", null, "0"),
                    row(2L, 2L, "102", "RELEASED", "2026-11-10T10:00", "2026-11-10T13:00", null, "0"));

            var r = StatsAggregator.aggregate(new StatsAggregator.Input(D1, D2, CUT_0600, 10, rows));

            assertThat(r.daily()).allSatisfy(d -> {
                assertThat(d.soldRooms()).isZero();
                assertThat(d.bookings()).isZero();
                assertThat(d.revenue()).isEqualByComparingTo("0");
            });
            assertThat(r.heat()).isEmpty();
        }

        @Test
        @DisplayName("营收只认已结算；退了房没结账的进「待结算」，两者不混")
        void revenueOnlyCountsSettled() {
            List<StatRow> rows = List.of(
                    row(1L, 1L, "101", "SETTLED", "2026-11-10T10:00", "2026-11-10T13:00", null, "148"),
                    row(2L, 2L, "102", "CHECKED_OUT", "2026-11-10T14:00", "2026-11-10T17:00", null, "188"),
                    row(3L, 3L, "103", "IN_USE", "2026-11-10T18:00", "2026-11-10T21:00", null, "0"));

            var r = StatsAggregator.aggregate(new StatsAggregator.Input(D1, D2, CUT_0600, 10, rows));
            var day = r.daily().get(0);

            assertThat(day.revenue()).isEqualByComparingTo("148");
            assertThat(day.pendingBookings()).as("待结算按单数统计").isEqualTo(1);
            assertThat(day.soldRooms()).as("三间房都卖出去过").isEqualTo(3);
            assertThat(r.totalRevenue()).isEqualByComparingTo("148");
            assertThat(r.totalPendingBookings()).isEqualTo(1);
        }

        @Test
        @DisplayName("只查后一天时，前一天入住、跨进窗口的住宿仍要出现在热力图里")
        void heatIncludesStayStartedBeforeTheRange() {
            // 审阅场景：11-10 14:00 → 11-11 12:00，只查 11-11 这一天
            StatRow fullDay = row(1L, 1L, "201", "SETTLED",
                    "2026-11-10T14:00", "2026-11-11T12:00", null, "388");

            var onlyD2 = StatsAggregator.aggregate(
                    new StatsAggregator.Input(D2, D2, CUT_0600, 10, List.of(fullDay)));

            // 房晚与营收按"入住营业日"归属 → 不属于 11-11
            assertThat(onlyD2.daily().get(0).soldRooms()).isZero();
            assertThat(onlyD2.totalRevenue()).isEqualByComparingTo("0");
            // 但热度按"住宿区间是否相交" → 11-11 的 06:00–11:00 六格必须在
            // 第一版把整笔订单跳过了，于是换个查询区间图就变形
            assertThat(onlyD2.heat()).hasSize(6)
                    .allSatisfy(h -> assertThat(h.businessDay()).isEqualTo(D2));
            assertThat(onlyD2.heat()).extracting(StatsAggregator.Heat::hour)
                    .containsExactly(6, 7, 8, 9, 10, 11);
        }

        @Test
        @DisplayName("超时与早退分别计数，按入住营业日归属")
        void overstayAndEarlyLeave() {
            List<StatRow> rows = List.of(
                    // 计划 12:00 离店，实际 13:30 → 超时
                    row(1L, 1L, "101", "CHECKED_OUT", "2026-11-10T09:00", "2026-11-10T12:00",
                            "2026-11-10T13:30", "0"),
                    // 计划 18:00，实际 16:30 → 提早走
                    row(2L, 2L, "102", "CHECKED_OUT", "2026-11-10T15:00", "2026-11-10T18:00",
                            "2026-11-10T16:30", "0"),
                    // 准时走：两个计数都不加
                    row(3L, 3L, "103", "SETTLED", "2026-11-10T10:00", "2026-11-10T12:00",
                            "2026-11-10T12:00", "68"));

            var day = StatsAggregator.aggregate(new StatsAggregator.Input(
                    D1, D2, CUT_0600, 10, rows)).daily().get(0);

            assertThat(day.overstay()).isEqualTo(1);
            assertThat(day.earlyLeave()).isEqualTo(1);
        }

        @Test
        @DisplayName("出租率按「不同房间数」算：同一间房一天卖两次仍只算一间")
        void occupancyCountsDistinctRooms() {
            List<StatRow> rows = List.of(
                    row(1L, 1L, "101", "SETTLED", "2026-11-10T09:00", "2026-11-10T12:00", null, "68"),
                    row(2L, 1L, "101", "SETTLED", "2026-11-10T18:00", "2026-11-10T21:00", null, "88"));

            var day = StatsAggregator.aggregate(new StatsAggregator.Input(
                    D1, D2, CUT_0600, 10, rows)).daily().get(0);

            assertThat(day.soldRooms()).isEqualTo(1);
            assertThat(day.occupancyRate()).isEqualByComparingTo("10.00");
            assertThat(day.revenue()).isEqualByComparingTo("156");
            assertThat(day.bookings()).as("单数仍是两笔").isEqualTo(2);
        }

        @Test
        @DisplayName("区间之外的单不进任何一天，但区间内的空日也要占位")
        void outOfRangeIgnoredButEmptyDaysAreKept() {
            List<StatRow> rows = List.of(
                    row(1L, 1L, "101", "SETTLED", "2026-11-09T10:00", "2026-11-09T13:00", null, "68"),
                    row(2L, 1L, "101", "SETTLED", "2026-11-12T10:00", "2026-11-12T13:00", null, "68"));

            var r = StatsAggregator.aggregate(new StatsAggregator.Input(D1, D2, CUT_0600, 10, rows));

            assertThat(r.daily()).hasSize(2).allSatisfy(d -> {
                assertThat(d.soldRooms()).isZero();
                assertThat(d.occupancyRate()).isEqualByComparingTo("0");
            });
            assertThat(r.ranking()).as("排名只统计区间内的单").isEmpty();
        }

        @Test
        @DisplayName("房间排行按营收降序，营收相同按出让次数，再按房号稳定")
        void rankingOrder() {
            List<StatRow> rows = List.of(
                    row(1L, 1L, "101", "SETTLED", "2026-11-10T09:00", "2026-11-10T12:00", null, "100"),
                    row(2L, 2L, "102", "SETTLED", "2026-11-10T09:00", "2026-11-10T12:00", null, "300"),
                    row(3L, 2L, "102", "SETTLED", "2026-11-10T18:00", "2026-11-10T21:00", null, "0"));

            var r = StatsAggregator.aggregate(new StatsAggregator.Input(D1, D2, CUT_0600, 10, rows));

            assertThat(r.ranking()).extracting(StatsAggregator.Rank::roomNo).containsExactly("102", "101");
            assertThat(r.ranking().get(0).soldTimes()).isEqualTo(2);
            assertThat(r.ranking().get(0).revenue()).isEqualByComparingTo("300");
        }

        @Test
        @DisplayName("平均出租率按「总房晚 / (房间数 × 天数)」加权，不是把每天的率再平均")
        void averageOccupancyIsWeighted() {
            // 两天、10 间房：第一天卖 2 间，第二天卖 0 间 → 2 / 20 = 10%
            List<StatRow> rows = List.of(
                    row(1L, 1L, "101", "IN_USE", "2026-11-10T09:00", "2026-11-10T12:00", null, "0"),
                    row(2L, 2L, "102", "IN_USE", "2026-11-10T14:00", "2026-11-10T17:00", null, "0"));

            var r = StatsAggregator.aggregate(new StatsAggregator.Input(D1, D2, CUT_0600, 10, rows));

            assertThat(r.avgOccupancy()).isEqualTo(10.0);
            assertThat(r.daily().get(0).occupancyRate()).isEqualByComparingTo("20.00");
            assertThat(r.daily().get(1).occupancyRate()).isEqualByComparingTo("0.00");
        }
    }

    // ---------------------------------------------------------------- 入参

    @Test
    @DisplayName("非法区间与超长区间直接抛，不静默返回一张空表")
    void rejectsIllegalInput() {
        assertThatThrownBy(() -> StatsAggregator.aggregate(new StatsAggregator.Input(
                D2, D1, CUT_0600, 10, List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能早于");

        assertThatThrownBy(() -> StatsAggregator.aggregate(new StatsAggregator.Input(
                D1, D1.plusDays(400), CUT_0600, 10, List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最长一年");
    }

    // ---------------------------------------------------------------- 夹具

    private static StatRow row(Long bookingId, Long roomId, String roomNo, String status,
                               String start, String end, String actualOut, String amount) {
        StatRow r = new StatRow();
        r.setBookingId(bookingId);
        r.setRoomId(roomId);
        r.setRoomNo(roomNo);
        r.setSaleType("HOURLY");
        r.setStatus(status);
        r.setStartTime(LocalDateTime.parse(start));
        r.setEndTime(LocalDateTime.parse(end));
        r.setActualCheckOut(actualOut == null ? null : LocalDateTime.parse(actualOut));
        r.setAmount(new BigDecimal(amount));
        return r;
    }
}
