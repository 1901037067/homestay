package com.school.bnb.controller;

import com.school.bnb.common.R;
import com.school.bnb.dto.RoomTypeReq;
import com.school.bnb.entity.RoomType;
import com.school.bnb.service.RoomTypeService;
import com.school.bnb.vo.RoomTypeVO;
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

@Tag(name = "房源-房型")
@RestController
@RequestMapping("/api/room-types")
public class RoomTypeController {

    private final RoomTypeService service;

    public RoomTypeController(RoomTypeService service) {
        this.service = service;
    }

    @GetMapping
    public R<List<RoomTypeVO>> list(@RequestParam(required = false) Long propertyId) {
        return R.ok(service.list(propertyId));
    }

    @PostMapping
    public R<RoomType> create(@Valid @RequestBody RoomTypeReq req) {
        return R.ok(service.save(req));
    }

    @PutMapping("/{id}")
    public R<RoomType> update(@PathVariable Long id, @Valid @RequestBody RoomTypeReq req) {
        return R.ok(service.save(withId(req, id)));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.ok();
    }

    private static RoomTypeReq withId(RoomTypeReq req, Long id) {
        return new RoomTypeReq(id, req.propertyId(), req.name(), req.basePrice(), req.minHours(),
                req.extraHourPrice(), req.cleanBeforeMinutes(), req.cleanAfterMinutes(),
                req.fullDayStart(), req.fullDayEnd());
    }
}
