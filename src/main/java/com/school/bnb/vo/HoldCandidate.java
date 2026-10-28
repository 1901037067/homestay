package com.school.bnb.vo;

import lombok.Data;

/** 超时回收的候选行。用 @Data 而不是 record，MyBatis 走 setter 映射最稳。 */
@Data
public class HoldCandidate {
    private Long bookingId;
    private Long roomId;
}
