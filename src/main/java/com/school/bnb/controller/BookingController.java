package com.school.bnb.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.school.bnb.common.R;
import com.school.bnb.dto.ChangeTimesReq;
import com.school.bnb.dto.CreateBookingReq;
import com.school.bnb.dto.ExtendReq;
import com.school.bnb.service.BookingService;
import com.school.bnb.vo.BookingVO;
import com.school.bnb.vo.FreeSlotVO;
import com.school.bnb.vo.RoomVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "预订", description = "下单/改期/加时/入住/退房/取消，冲突检测在房间行锁内")
@RestController
@RequestMapping("/api")
public class BookingController {

    private final BookingService service;

    public BookingController(BookingService service) {
        this.service = service;
    }

    @Operation(summary = "下单（核心，带行锁）")
    @PostMapping("/bookings")
    public R<BookingVO> create(@Valid @RequestBody CreateBookingReq req) {
        return R.ok(service.create(req));
    }

    @Operation(summary = "订单列表")
    @GetMapping("/bookings")
    public R<Page<BookingVO>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long roomId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return R.ok(service.list(page, size, status, roomId, keyword, from, to));
    }

    @Operation(summary = "订单详情")
    @GetMapping("/bookings/{id}")
    public R<BookingVO> get(@PathVariable Long id) {
        return R.ok(service.get(id));
    }

    @Operation(summary = "改期：删旧占用→重跑冲突检测→插新占用，同一事务")
    @PutMapping("/bookings/{id}/times")
    public R<BookingVO> changeTimes(@PathVariable Long id, @Valid @RequestBody ChangeTimesReq req) {
        return R.ok(service.changeTimes(id, req));
    }

    @Operation(summary = "确认预占")
    @PostMapping("/bookings/{id}/confirm")
    public R<BookingVO> confirm(@PathVariable Long id) {
        return R.ok(service.confirm(id));
    }

    @Operation(summary = "取消")
    @PostMapping("/bookings/{id}/cancel")
    public R<BookingVO> cancel(@PathVariable Long id) {
        return R.ok(service.cancel(id));
    }

    @Operation(summary = "入住")
    @PostMapping("/bookings/{id}/check-in")
    public R<BookingVO> checkIn(@PathVariable Long id) {
        return R.ok(service.checkIn(id));
    }

    @Operation(summary = "退房：释放占用行")
    @PostMapping("/bookings/{id}/check-out")
    public R<BookingVO> checkOut(@PathVariable Long id) {
        return R.ok(service.checkOut(id));
    }

    @Operation(summary = "加时：需重跑冲突检测")
    @PostMapping("/bookings/{id}/extend")
    public R<BookingVO> extend(@PathVariable Long id, @Valid @RequestBody ExtendReq req) {
        return R.ok(service.extend(id, req));
    }

    @Operation(summary = "可售房间列表")
    @GetMapping("/rooms/availability")
    public R<List<RoomVO>> availableRooms(
            @RequestParam(required = false) Long propertyId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
            @RequestParam(required = false) Integer minHours) {
        return R.ok(service.availableRooms(propertyId, start, end, minHours));
    }

    @Operation(summary = "某房的空闲区间列表")
    @GetMapping("/rooms/{id}/availability")
    public R<List<FreeSlotVO>> freeSlots(
            @PathVariable("id") Long roomId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
            @RequestParam(required = false) Integer minHours) {
        return R.ok(service.freeSlots(roomId, start, end, minHours));
    }
}
