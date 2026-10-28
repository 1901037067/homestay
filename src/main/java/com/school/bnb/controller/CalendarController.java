package com.school.bnb.controller;

import com.school.bnb.common.R;
import com.school.bnb.service.CalendarService;
import com.school.bnb.vo.CalendarVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "房态", description = "日历数据源，窗口按营业日切割")
@RestController
@RequestMapping("/api")
public class CalendarController {

    private final CalendarService service;

    public CalendarController(CalendarService service) {
        this.service = service;
    }

    @Operation(summary = "房态日历", description = "propertyId 必填：前台一次只看一家店，"
            + "而营业日切割点是按店配置的，两家店混在一张图上窗口对不齐")
    @GetMapping("/calendar")
    public R<CalendarVO> calendar(
            @RequestParam Long propertyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return R.ok(service.calendar(propertyId, date));
    }
}
