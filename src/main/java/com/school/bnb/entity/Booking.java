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

    /** 幂等键：防重复开单。系统内部生成的单号，不参与幂等判定。 */
    private String bookingNo;

    /** 客户端生成的请求标识。同一请求重复提交时返回原单，见 uk_request_id。 */
    private String requestId;

    /** HOURLY / FULL_DAY。售卖方式由下单时声明，改期与加时沿用，不允许中途改。 */
    private String saleType;
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

    /** 下单时快照的计费基准 —— 结算据此算账，不再读"现在"的房型配置。 */
    private Integer coveredMinutes;
    private Integer chargeUnitMinutes;

    /** 下单时快照的连住晚数。历史行为 null，BillingService 遇到 null 按当次模板现推（必然推成 1 晚）。 */
    private Integer nights;
    private String remark;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
