package com.school.bnb.controller;

import com.school.bnb.common.R;
import com.school.bnb.dto.PropertyReq;
import com.school.bnb.entity.Property;
import com.school.bnb.service.PropertyService;
import com.school.bnb.vo.PropertyVO;
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
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "房源-民宿")
@RestController
@RequestMapping("/api/properties")
public class PropertyController {

    private final PropertyService service;

    public PropertyController(PropertyService service) {
        this.service = service;
    }

    @GetMapping
    public R<List<PropertyVO>> list() {
        return R.ok(service.list());
    }

    @PostMapping
    public R<Property> create(@Valid @RequestBody PropertyReq req) {
        return R.ok(service.save(req));
    }

    @PutMapping("/{id}")
    public R<Property> update(@PathVariable Long id, @Valid @RequestBody PropertyReq req) {
        return R.ok(service.save(withId(req, id)));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.ok();
    }

    private static PropertyReq withId(PropertyReq req, Long id) {
        return new PropertyReq(id, req.name(), req.address(), req.dayCutTime(), req.openTime(), req.closeTime());
    }
}
