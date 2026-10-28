package com.school.bnb.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.school.bnb.common.R;
import com.school.bnb.dto.RoomReq;
import com.school.bnb.entity.Room;
import com.school.bnb.service.RoomService;
import com.school.bnb.vo.RoomVO;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "房源-房间")
@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    private final RoomService service;

    public RoomController(RoomService service) {
        this.service = service;
    }

    @GetMapping
    public R<Page<RoomVO>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) Long propertyId,
            @RequestParam(required = false) Long roomTypeId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return R.ok(service.page(page, size, propertyId, roomTypeId, status, keyword));
    }

    @GetMapping("/{id}")
    public R<Room> get(@PathVariable Long id) {
        return R.ok(service.require(id));
    }

    @PostMapping
    public R<Room> create(@Valid @RequestBody RoomReq req) {
        return R.ok(service.save(req));
    }

    @PutMapping("/{id}")
    public R<Room> update(@PathVariable Long id, @Valid @RequestBody RoomReq req) {
        return R.ok(service.save(new RoomReq(id, req.propertyId(), req.roomTypeId(),
                req.roomNo(), req.floorNo(), req.status())));
    }

    /** 上架/维修/下架 —— 删除的替代动作。 */
    @PatchMapping("/{id}/status")
    public R<Room> changeStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        service.changeStatus(id, body.get("status"));
        return R.ok(service.require(id));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.ok();
    }
}
