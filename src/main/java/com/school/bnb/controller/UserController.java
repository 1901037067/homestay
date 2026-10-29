package com.school.bnb.controller;

import com.school.bnb.common.AccessScope;
import com.school.bnb.common.R;
import com.school.bnb.dto.UserReq;
import com.school.bnb.service.UserService;
import com.school.bnb.vo.UserVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "账号", description = "店长专属：账号的开通、调整与停用（没有自助注册，也没有物理删除）")
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService service;
    private final AccessScope scope;

    public UserController(UserService service, AccessScope scope) {
        this.service = service;
        this.scope = scope;
    }

    @Operation(summary = "账号列表", description = "不含密码哈希")
    @GetMapping
    public R<List<UserVO>> list() {
        scope.requireManager();
        return R.ok(service.list());
    }

    @Operation(summary = "新建账号")
    @PostMapping
    public R<UserVO> create(@Valid @RequestBody UserReq req) {
        return R.ok(service.save(withoutId(req)));
    }

    @Operation(summary = "修改账号", description = "密码留空表示不改；停用是删除的替代动作")
    @PutMapping("/{id}")
    public R<UserVO> update(@PathVariable Long id, @Valid @RequestBody UserReq req) {
        return R.ok(service.save(withId(req, id)));
    }

    private static UserReq withoutId(UserReq req) {
        return new UserReq(null, req.username(), req.displayName(), req.password(),
                req.propertyId(), req.enabled());
    }

    private static UserReq withId(UserReq req, Long id) {
        return new UserReq(id, req.username(), req.displayName(), req.password(),
                req.propertyId(), req.enabled());
    }
}
