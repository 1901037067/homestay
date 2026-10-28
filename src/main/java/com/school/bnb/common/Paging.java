package com.school.bnb.common;

/**
 * 分页参数兜底。
 *
 * <p>为什么必须有：{@code size=100000} 不会报错，只会把整表拉进内存再序列化 ——
 * 单机毕设里没人恶意攻击，但一个手滑的 URL、一个写错的循环，照样能让 JVM 喘不过气。
 * 这三处列表接口共用同一组上下限，免得"某个接口能查 200、另一个能查 20 万"。
 */
public final class Paging {

    /** 一页最多 200 条：房态日历之外没有哪个页面需要一次看更多。 */
    public static final int MAX_SIZE = 200;

    private Paging() {
    }

    public static long page(long page) {
        return Math.max(page, 1);
    }

    public static long size(long size) {
        return Math.min(Math.max(size, 1), MAX_SIZE);
    }
}
