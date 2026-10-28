package com.school.bnb.controller;

import com.school.bnb.common.R;
import com.school.bnb.service.BillingService;
import com.school.bnb.vo.BillVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "计费", description = "试算走纯函数，结算落快照")
@RestController
@RequestMapping("/api")
public class BillController {

    private final BillingService service;

    public BillController(BillingService service) {
        this.service = service;
    }

    @Operation(summary = "账单视图",
            description = "已结算返账单快照，未结算返实时试算；两者用 snapshot 字段区分")
    @GetMapping("/bookings/{id}/bill")
    public R<BillVO> view(@PathVariable Long id) {
        return R.ok(service.view(id));
    }

    @Operation(summary = "试算", description = "按当前规则重算，不写库")
    @PostMapping("/bookings/{id}/bill/preview")
    public R<BillVO> preview(@PathVariable Long id) {
        return R.ok(service.preview(id));
    }

    @Operation(summary = "结算", description = "只有 CHECKED_OUT 能结算；金额与状态同一条条件更新写完")
    @PostMapping("/bookings/{id}/settle")
    public R<BillVO> settle(@PathVariable Long id) {
        return R.ok(service.settle(id));
    }
}
