package com.school.bnb.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 房态日历上的一块占用。
 *
 * <p>用可变 POJO 而不是 record：MyBatis 的结果集自动映射走 setter，record 的构造器映射
 * 依赖编译参数与版本行为，不值得为省几行代码赌它。
 */
@Data
public class CalendarBlock {
    private Long roomId;
    private Long bookingId;
    private String bookingNo;
    private String guestName;
    private String bookingStatus;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime blockStart;
    private LocalDateTime blockEnd;
    private String kind;
}
