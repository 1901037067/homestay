package com.school.bnb.controller;

import com.school.bnb.common.R;
import com.school.bnb.service.StatsService;
import com.school.bnb.vo.StatsVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "报表", description = "按营业日切割；营收只计已结算")
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final StatsService service;

    public StatsController(StatsService service) {
        this.service = service;
    }

    @Operation(summary = "经营报表", description =
            "指标卡 + 逐营业日序列 + 分时段热度 + 房间排行，一次扫描出一份自洽快照")
    @GetMapping("/report")
    public R<StatsVO> report(
            @RequestParam Long propertyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return R.ok(service.report(propertyId, from, to));
    }

    @Operation(summary = "导出 CSV", description = "带 UTF-8 BOM，Excel 双击直接正确显示中文")
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam Long propertyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        StatsVO vo = service.report(propertyId, from, to);
        // 文件名只用 ASCII：中文文件名要靠 filename*=UTF-8'' 编码，各家浏览器/下载器行为不一致，
        // 而日期本身就是最好的标识
        String name = "stats-%s_%s.csv".formatted(vo.from(), vo.to());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .body(service.exportCsv(vo));
    }
}
