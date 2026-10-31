package com.school.bnb.vo;

import com.school.bnb.entity.Booking;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record BookingVO(
        Long id,
        String bookingNo,
        String requestId,
        String saleType,
        Long roomId,
        String roomNo,
        Long propertyId,
        String propertyName,
        String roomTypeName,
        String guestName,
        String guestPhone,
        LocalDateTime startTime,
        LocalDateTime endTime,
        /** 连住晚数。历史行没有这一列（V9 之前），一律按 1 晚报出——列表不该出现"不知道几晚"。 */
        Integer nights,
        String status,
        LocalDateTime holdExpireAt,
        LocalDateTime actualCheckIn,
        LocalDateTime actualCheckOut,
        BigDecimal amount,
        LocalDateTime createdAt) {

    public static BookingVO of(Booking b, String roomNo, Long propertyId, String propertyName, String roomTypeName) {
        return new BookingVO(b.getId(), b.getBookingNo(), b.getRequestId(), b.getSaleType(),
                b.getRoomId(), roomNo, propertyId, propertyName,
                roomTypeName, b.getGuestName(), b.getGuestPhone(), b.getStartTime(), b.getEndTime(),
                b.getNights() == null ? 1 : b.getNights(),
                b.getStatus(), b.getHoldExpireAt(), b.getActualCheckIn(), b.getActualCheckOut(),
                b.getAmount(), b.getCreatedAt());
    }
}
