package com.school.bnb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String passwordHash;
    private String displayName;
    /** 所属民宿：null = 店长（看全部门店），非空 = 该民宿前台。角色由这一列派生，不另设 role 列。 */
    private Long propertyId;
    private Boolean enabled;
}
