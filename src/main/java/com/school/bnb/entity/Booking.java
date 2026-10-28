package com.school.bnb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("booking")
public class Booking {

    public static final String HELD = "HELD";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String IN_USE = "IN_USE";
    public static final String CHECKED_OUT = "CHECKED_OUT";
    public static final String SETTLED = "SETTLED";
    public static final String CANCELLED = "CANCELLED";
    public static final String RELEASED = "RELEASED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 幂等键：双击下单靠它兜住。 */
    private String bookingNo;
    private Long roomId;
    private String guestName;
    private String guestPhone;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime actualCheckIn;
    private LocalDateTime actualCheckOut;
    private String status;
    private LocalDateTime holdExpireAt;
    private BigDecimal amount;
    private String remark;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
