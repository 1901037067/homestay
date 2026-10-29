package com.school.bnb.common;

import com.school.bnb.common.LoginInterceptor.LoginUser;
import org.springframework.stereotype.Component;

/**
 * "当前登录人能看哪些民宿"的唯一权威。
 *
 * <p>为什么单独一个类、而不是在各 service 里各写一句 {@code if (user.propertyId != null)}：
 * 隔离这件事一旦散到十几个查询里，就会有人漏掉一处 —— 而漏掉的那处不会报错，
 * 只会让别的民宿的数据安静地出现在列表里。判定收在这里，调用点只负责"问一句"。
 * 这与 {@link com.school.bnb.booking.SellPolicy}、{@code StatsAggregator} 是同一个套路：
 * 一个边界只准有一个权威。
 *
 * <p><b>没有登录上下文时视为不受限</b>（返回 null）。这不是漏洞：HTTP 侧所有
 * {@code /api/**}（除登录与健康检查）都要过 {@link LoginInterceptor}，走到服务层时
 * 一定有登录人；{@code AuthContext} 为空只可能是测试或定时任务这类内部调用 ——
 * 超时回收任务每 60 秒要扫全部房间的过期预占，它本来就不属于任何一家店。
 */
@Component
public class AccessScope {

    /** 当前账号绑定的民宿；null = 店长或内部调用，不受限。 */
    public Long boundPropertyId() {
        LoginUser u = AuthContext.current();
        return u == null ? null : u.propertyId();
    }

    public boolean global() {
        LoginUser u = AuthContext.current();
        return u == null || u.global();
    }

    /**
     * 读列表用：把"请求要看的民宿"和"账号能看的民宿"求交。
     *
     * <p>越界时抛错而不是悄悄换成空结果 —— 前端本来不该发出这个请求，
     * 悄悄返回空列表会让"我明明有数据"和"权限不对"两种情况长得一模一样，最难查。
     */
    public Long narrow(Long requested) {
        Long bound = boundPropertyId();
        if (bound == null) {
            return requested;
        }
        if (requested != null && !requested.equals(bound)) {
            throw outOfScope(requested, bound);
        }
        return bound;
    }

    /** 校验某个具体民宿可见（写路径、详情、报表这类必须带 propertyId 的入口）。 */
    public Long requireVisible(Long propertyId) {
        Long bound = boundPropertyId();
        if (bound != null && !bound.equals(propertyId)) {
            throw outOfScope(propertyId, bound);
        }
        return propertyId;
    }

    /** 店长专属：账号管理、以及建/删民宿。 */
    public void requireManager() {
        if (!global()) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "这个操作只开放给店长账号（不绑定单一民宿的那种）");
        }
    }

    private static BizException outOfScope(Long requested, Long bound) {
        return new BizException(ErrorCode.FORBIDDEN,
                "该民宿不在你的账号可见范围内：当前账号只看 " + bound + " 号民宿，请求的是 "
                        + requested + " 号");
    }
}
