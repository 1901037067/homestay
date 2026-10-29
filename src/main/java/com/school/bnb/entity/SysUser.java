package com.school.bnb.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
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
    /**
     * 所属民宿：null = 店长（看全部门店），非空 = 该民宿前台。角色由这一列派生，不另设 role 列。
     *
     * <p><b>{@code updateStrategy = ALWAYS} 是必须的</b>：MyBatis-Plus 默认跳过 null 字段，
     * 于是"把前台升为店长"（清空归属）这条 PUT 会把 null 直接丢掉、库里仍是原民宿 ——
     * 接口返回 200、列表里角色也变了（VO 是从入参构造的），只有下次登录才发现没升上去。
     * 与 {@code RateRule} 上那几个"留空即不限"的列同一个坑，也同一个解法。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long propertyId;
    private Boolean enabled;
}
