package com.school.bnb.controller;

import com.school.bnb.common.R;
import com.school.bnb.dto.RateRuleReq;
import com.school.bnb.entity.RateRule;
import com.school.bnb.service.RateRuleService;
import com.school.bnb.vo.RateRuleVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "计费-价格规则", description = "按星期/日期/时段覆盖平时价")
@RestController
@RequestMapping("/api/rate-rules")
public class RateRuleController {

    private final RateRuleService service;

    public RateRuleController(RateRuleService service) {
        this.service = service;
    }

    @GetMapping
    public R<List<RateRuleVO>> list(@RequestParam(required = false) Long propertyId) {
        return R.ok(service.list(propertyId));
    }

    @PostMapping
    public R<RateRule> create(@Valid @RequestBody RateRuleReq req) {
        return R.ok(service.save(req));
    }

    @PutMapping("/{id}")
    public R<RateRule> update(@PathVariable Long id, @Valid @RequestBody RateRuleReq req) {
        return R.ok(service.save(withId(req, id)));
    }

    /** 物理删除：账单存了当时的单价与规则名，删规则不会动摇历史账单（见 RateRuleService 类注释）。 */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.ok();
    }

    private static RateRuleReq withId(RateRuleReq req, Long id) {
        return new RateRuleReq(id, req.propertyId(), req.roomTypeId(), req.name(), req.validFrom(),
                req.validTo(), req.weekdays(), req.startHour(), req.endHour(), req.mode(),
                req.value(), req.appliesTo(), req.priority(), req.enabled());
    }
}
