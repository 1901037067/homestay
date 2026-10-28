package com.school.bnb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.school.bnb.entity.Room;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface RoomMapper extends BaseMapper<Room> {

    /**
     * 房间行悲观锁 —— 防超卖的地基（实施计划 3.2 / 1.1）。
     *
     * <p>MyBatis-Plus 的 {@code BaseMapper} 不提供 FOR UPDATE，{@code selectById} 也绝不加锁，
     * 所以这个方法必须手写。返回 id 而不是整行，是为了让锁的语义在 SQL 上一眼可见。
     *
     * <p>拿到返回值 == 当前事务独占该房间的写入权；返回 null 表示房间不存在
     * （此时 InnoDB 锁的是间隙，事务照常提交，锁随即释放）。
     */
    @Select("select id from room where id = #{roomId} for update")
    Long selectForUpdate(@Param("roomId") Long roomId);
}
