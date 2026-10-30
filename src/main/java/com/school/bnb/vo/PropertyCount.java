package com.school.bnb.vo;

import lombok.Data;

/**
 * "某家民宿下有多少条下级数据"的计数行，给 {@code PropertyService.list} 收 N+1 用。
 *
 * <p>与 {@link HoldCandidate} 同理用 {@code @Data} 而不是 record：MyBatis 的结果集自动映射走 setter。
 */
@Data
public class PropertyCount {
    private Long propertyId;
    private Long total;
}
