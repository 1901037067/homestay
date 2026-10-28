package com.school.bnb.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.school.bnb.common.Paging;
import com.school.bnb.common.R;
import com.school.bnb.entity.OperationLog;
import com.school.bnb.service.OperationLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "审计", description = "操作日志：谁在什么时候改了什么")
@RestController
@RequestMapping("/api/operation-logs")
public class OperationLogController {

    private final OperationLogService service;

    public OperationLogController(OperationLogService service) {
        this.service = service;
    }

    @Operation(summary = "操作日志列表")
    @GetMapping
    public R<Page<OperationLog>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entity,
            @RequestParam(required = false) Long entityId,
            @RequestParam(required = false) String username) {
        return R.ok(service.page(Paging.page(page), Paging.size(size), action, entity, entityId, username));
    }
}
