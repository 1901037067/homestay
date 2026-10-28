package com.school.bnb.vo;

import com.school.bnb.entity.Booking;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record BookingVO(
        Long id,
        String bookingNo,
        Long roomId,
        String roomNo,
        Long propertyId,
        String propertyName,
        String roomTypeName,
        String guestName,
        String guestPhone,
        LocalDateTime startTime,
        LocalDateTime endTime,
        String status,
        LocalDateTime holdExpireAt,
        LocalDateTime actualCheckIn,
        LocalDateTime actualCheckOut,
        BigDecimal amount,
        LocalDateTime createdAt) {

    public static BookingVO of(Booking b, String roomNo, Long propertyId, String propertyName, String roomTypeName) {
        return new BookingVO(b.getId(), b.getBookingNo(), b.getRoomId(), roomNo, propertyId, propertyName,
                roomTypeName, b.getGuestName(), b.getGuestPhone(), b.getStartTime(), b.getEndTime(),
                b.getStatus(), b.getHoldExpireAt(), b.getActualCheckIn(), b.getActualCheckOut(),
                b.getAmount(), b.getCreatedAt());
    }
}
