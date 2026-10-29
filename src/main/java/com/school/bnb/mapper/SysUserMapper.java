package com.school.bnb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.school.bnb.entity.SysUser;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SysUserMapper extends BaseMapper<SysUser> {

    @Select("select * from sys_user where username = #{username}")
    SysUser findByUsername(@Param("username") String username);

    /**
     * 锁住"当前所有可用的店长"并返回它们的 id —— <b>必须是当前读</b>。
     *
     * <p>权限变更是 check-then-act：先数店长还剩几个、再决定这次降级能不能做。两个店长同时
     * 互相降级时，两边都数到 2、都通过检查，结果一个店长都不剩（第十轮审阅第 4 条）。
     * 普通 SELECT 在 RR 下拿的是快照、也不加锁，等于没检查；{@code FOR UPDATE} 把这些行锁住，
     * 第二个事务只能等第一个提交后重新数，才会看到"只剩 1 个"从而拒绝。
     */
    @Select("select id from sys_user where property_id is null and enabled = 1 for update")
    java.util.List<Long> lockEnabledManagers();
}
