package com.school.bnb.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.school.bnb.common.AuthContext;
import com.school.bnb.common.LoginInterceptor;
import com.school.bnb.entity.OperationLog;
import com.school.bnb.mapper.OperationLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 操作日志。
 *
 * <p><b>刻意不用 AOP。</b>切面能少写几行，但"哪个动作留下了什么记录"就得跳到配置里去读，
 * 而这张表的价值恰恰是审计可读性。显式调用一眼看得见，也直接可测。
 *
 * <p>记日志失败绝不能把业务带崩，所以整体吞异常只打 warn。
 */
@Slf4j
@Service
public class OperationLogService {

    private final OperationLogMapper mapper;
    private final ObjectMapper json;

    public OperationLogService(OperationLogMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    public void record(String action, String entity, Long entityId, String summary,
                       Object before, Object after) {
        try {
            OperationLog row = new OperationLog();
            LoginInterceptor.LoginUser user = AuthContext.current();
            if (user != null) {
                row.setUserId(user.id());
                row.setUsername(user.username());
            }
            row.setAction(action);
            row.setEntity(entity);
            row.setEntityId(entityId);
            row.setSummary(summary);
            row.setBeforeJson(write(before));
            row.setAfterJson(write(after));
            mapper.insert(row);
        } catch (Exception e) {
            log.warn("写操作日志失败 action={} entity={}#{}", action, entity, entityId, e);
        }
    }

    private String write(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return "{\"_error\":\"" + e.getClass().getSimpleName() + "\"}";
        }
    }

    /** 日志查询。默认时间倒序 —— 前台关心的是"刚才谁动了"。 */
    public com.baomidou.mybatisplus.extension.plugins.pagination.Page<OperationLog> page(
            long pageNo, long pageSize, String action, String entity, Long entityId, String username) {
        return mapper.selectPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(pageNo, pageSize),
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OperationLog>()
                        .eq(action != null && !action.isBlank(), OperationLog::getAction, action)
                        .eq(entity != null && !entity.isBlank(), OperationLog::getEntity, entity)
                        .eq(entityId != null, OperationLog::getEntityId, entityId)
                        .eq(username != null && !username.isBlank(), OperationLog::getUsername, username)
                        .orderByDesc(OperationLog::getId));
    }
}
