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
    /** 售卖方式。加时的提示要按它分话术，而"从时长推断是不是全日房"正是本项目明令禁止的那种猜法。 */
    private String saleType;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime blockStart;
    private LocalDateTime blockEnd;
    private String kind;
}
