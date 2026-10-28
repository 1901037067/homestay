package com.school.bnb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.Data;

@Data
@TableName("property")
public class Property {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String address;

    /** 营业日切割点：此刻之前算前一天（实施计划 7.2）。 */
    private LocalTime dayCutTime;

    /** 可售窗口，只约束钟点房；全日房模板豁免（实施计划 2.5）。 */
    private LocalTime openTime;
    private LocalTime closeTime;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
