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
    private final com.school.bnb.common.AccessScope scope;

    public OperationLogService(OperationLogMapper mapper, ObjectMapper json,
                               com.school.bnb.common.AccessScope scope) {
        this.mapper = mapper;
        this.json = json;
        this.scope = scope;
    }

    /**
     * 登录审计。和 {@link #record} 分开，是因为<b>操作人不能从 AuthContext 取</b> ——
     * 登录这条路径上它还空着（拦截器是在登录成功之后才写）。
     *
     * <p>失败的尝试也记，并且记<b>尝试登录的那个用户名</b>：审计要回答的是"谁在试"。
     * 摘要里区分"账号不存在 / 密码错误 / 账号已停用"，这三者对店长的处置完全不同
     * （被撞库、记错密码、有人拿已停用的号在试）；<b>对外仍统一回"用户名或密码错误"</b>，
     * 不在接口上泄露账号是否存在。
     *
     * <p>登录是跨店动作，{@code property_id} 留 null，只有店长在审计页看得到。
     */
    public void recordAuth(String action, Long userId, String username, String summary) {
        try {
            OperationLog row = new OperationLog();
            row.setUserId(userId);
            row.setUsername(username);
            row.setAction(action);
            row.setEntity("user");
            row.setEntityId(userId);
            row.setPropertyId(null);
            row.setSummary(summary);
            mapper.insert(row);
        } catch (Exception e) {
            log.warn("写登录审计失败 action={} user={}", action, username, e);
        }
    }

    /**
     * @param propertyId 这条动作归属的民宿。摘要里带房号与客人姓名，所以它本身就是民宿数据；
     *                   跨店/系统级动作（登录、账号管理）传 null，只有店长能在审计页看到那些。
     */
    public void record(String action, String entity, Long entityId, Long propertyId, String summary,
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
            row.setPropertyId(propertyId);
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
        // 绑定单一民宿的账号只看自己店的那些行：摘要里有房号和客人姓名，
        // 不收窄等于把别家店的客人名单交给了前台。店长（bound=null）看全部，
        // 含 property_id 为 null 的跨店/系统级动作
        Long bound = scope.boundPropertyId();
        return mapper.selectPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(pageNo, pageSize),
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OperationLog>()
                        .eq(bound != null, OperationLog::getPropertyId, bound)
                        .eq(action != null && !action.isBlank(), OperationLog::getAction, action)
                        .eq(entity != null && !entity.isBlank(), OperationLog::getEntity, entity)
                        .eq(entityId != null, OperationLog::getEntityId, entityId)
                        .eq(username != null && !username.isBlank(), OperationLog::getUsername, username)
                        .orderByDesc(OperationLog::getId));
    }
}
