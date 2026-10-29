package com.school.bnb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("operation_log")
public class OperationLog {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String username;
    private String action;
    private String entity;
    private Long entityId;
    /** 这条动作归属的民宿；null = 跨店或系统级（登录、账号管理）。审计页按它隔离。 */
    private Long propertyId;
    private String summary;
    private String beforeJson;
    private String afterJson;
    private LocalDateTime createdAt;
}
