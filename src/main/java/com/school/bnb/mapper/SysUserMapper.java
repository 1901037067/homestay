package com.school.bnb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.school.bnb.entity.SysUser;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SysUserMapper extends BaseMapper<SysUser> {

    @Select("select * from sys_user where username = #{username}")
    SysUser findByUsername(@Param("username") String username);
}
