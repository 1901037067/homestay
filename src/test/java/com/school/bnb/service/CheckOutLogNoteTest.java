package com.school.bnb.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.school.bnb.booking.BookingKernel;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 退房日志里"转清扫几分钟"那句的措辞规则（纯函数，不碰库）。
 *
 * <p>第九轮审阅抓的是：日志照抄房型配置，而实际写下的清扫窗口可能被下一单截短甚至挤成零。
 * 审计凭证写了一句没发生过的话，比漏记更难查 —— 追查的人会按 30 分钟去找那条窗口，
 * 而日历上那一格根本不存在。
 */
class CheckOutLogNoteTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 12, 1, 12, 0);

    private static BookingKernel.CheckOutOutcome window(LocalDateTime end) {
        return new BookingKernel.CheckOutOutcome(null, AT, end);
    }

    @Test
    @DisplayName("没留清扫行：直接说可售，不要写分钟数")
    void noCleaningRow() {
        BookingKernel.CheckOutOutcome out =
                new BookingKernel.CheckOutOutcome(null, null, null);
        assertThat(out.needsCleaning()).isFalse();
        assertThat(BookingService.cleaningNote(0, out)).isEqualTo("无需清扫，直接可售");
    }

    @Test
    @DisplayName("窗口没被截断：分钟数与配置一致")
    void fullWindow() {
        assertThat(BookingService.cleaningNote(30, window(AT.plusMinutes(30))))
                .isEqualTo("转清扫 30 分钟");
    }

    @Test
    @DisplayName("被下一单截短：报实际分钟数，并把配置值一起写出来")
    void clippedByNextOccupancy() {
        String note = BookingService.cleaningNote(30, window(AT.plusMinutes(10)));
        assertThat(note).isEqualTo("转清扫 10 分钟（配置 30，被下一单截断）");
        assertThat(note).as("句子主语是实际值，不能让读者以为挡了半小时")
                .startsWith("转清扫 10 分钟");
    }

    @Test
    @DisplayName("挤成零：仍然要说清有一件待办，而不是假装直接可售")
    void squeezedToZeroStillNeedsConfirmation() {
        BookingKernel.CheckOutOutcome out = window(AT);
        assertThat(out.needsCleaning())
                .as("零长窗口在几何上挡不住任何时段，但它代表还没做完的清扫，必须仍能被确认")
                .isTrue();
        assertThat(out.actualMinutes()).isZero();
        assertThat(BookingService.cleaningNote(30, out))
                .isEqualTo("清扫窗口被下一单挤成 0 分钟，仍需点「清扫完成」才放行");
    }
}
