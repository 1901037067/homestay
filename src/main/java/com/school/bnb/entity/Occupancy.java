package com.school.bnb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 占位表。总纲 A：<b>有行即占位，释放即删除</b> —— 冲突检测不看任何状态字段，
 * 只看这块时间有没有行。因此取消/超时/退房都必须删掉对应行。
 */
@Data
@TableName("occupancy")
public class Occupancy {

    public static final String KIND_ROOM = "ROOM";
    /** 仅加分项（总纲 B2）使用；启用时 clean_*_minutes 必须置 0，否则缓冲算两遍。 */
    public static final String KIND_CLEANING = "CLEANING";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long roomId;
    private Long bookingId;

    /** 业务时间：客人在房态图上看到的区间。 */
    private LocalDateTime startTime;
    private LocalDateTime endTime;

    /** 冲突检测打在这对字段上：含清扫缓冲。 */
    private LocalDateTime blockStart;
    private LocalDateTime blockEnd;

    private String kind;
    private LocalDateTime createdAt;
}
