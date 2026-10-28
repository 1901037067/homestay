package com.school.bnb.controller;

import com.school.bnb.common.R;
import com.school.bnb.mapper.PropertyMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 免鉴权探针：确认「应用 + Flyway 迁移 + 数据库」三件事同时成立。 */
@RestController
public class HealthController {

    private final PropertyMapper propertyMapper;

    public HealthController(PropertyMapper propertyMapper) {
        this.propertyMapper = propertyMapper;
    }

    @GetMapping("/api/ping")
    public R<Map<String, Object>> ping() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("time", LocalDateTime.now().toString());
        body.put("properties", propertyMapper.selectCount(null));
        return R.ok(body);
    }
}
