package com.school.bnb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalTime;
import lombok.Data;

@Data
@TableName("room_type")
public class RoomType {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long propertyId;
    private String name;
    private BigDecimal basePrice;

    /** 起步时长，不约束全日房。 */
    private Integer minHours;
    private BigDecimal extraHourPrice;

    /** 总纲 B1：折进 occupancy.block_start/block_end 的清扫缓冲。 */
    private Integer cleanBeforeMinutes;
    private Integer cleanAfterMinutes;

    /** 全日房时段模板，end 落在次日。 */
    private LocalTime fullDayStart;
    private LocalTime fullDayEnd;
}
