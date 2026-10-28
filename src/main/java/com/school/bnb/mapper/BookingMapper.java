package com.school.bnb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.school.bnb.entity.Booking;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface BookingMapper extends BaseMapper<Booking> {

    /** 超时候选的预占单。注意：查出来不代表能删，真正释放要走下面那个条件更新。 */
    @Select("select id from booking where status = 'HELD' and hold_expire_at < #{now}")
    List<Long> findExpiredHeldIds(@Param("now") LocalDateTime now);

    /**
     * 超时释放（实施计划 3.5）：条件更新 + 影响行数判定。
     * 只有返回 1 的那一笔，调用方才允许删它的 occupancy 行 ——
     * 否则会把"刚好在同一瞬间被前台确认"的订单释放掉。
     */
    @Update("""
            update booking set status = 'RELEASED'
            where id = #{id} and status = 'HELD' and hold_expire_at < #{now}
            """)
    int releaseIfStillHeld(@Param("id") Long id, @Param("now") LocalDateTime now);
}
