package com.school.bnb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("room")
public class Room {

    public static final String ON_SALE = "ON_SALE";
    public static final String MAINTENANCE = "MAINTENANCE";
    public static final String OFFLINE = "OFFLINE";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long propertyId;
    private Long roomTypeId;
    private String roomNo;
    private Integer floorNo;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
