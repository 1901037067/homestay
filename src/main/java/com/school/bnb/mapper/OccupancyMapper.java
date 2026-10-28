package com.school.bnb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.school.bnb.entity.Occupancy;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface OccupancyMapper extends BaseMapper<Occupancy> {

    /**
     * 半开区间重叠判定：新单 [start, end) 与既有占用冲突的条数。
     *
     * <p>按总纲 A「有行即占位」，这里<b>不需要任何状态过滤条件</b> —— 已取消/已退房的单子
     * 它的行早就被删掉了，留下的行全都是真占位。这是本设计最省事也最不容易漏的地方。
     *
     * <p>边界相接（block_end == start）不算冲突，会被清扫缓冲自然挡住。
     */
    @Select("""
            select count(1) from occupancy
            where room_id = #{roomId}
              and block_start < #{endTime}
              and block_end > #{startTime}
            """)
    int countOverlap(@Param("roomId") Long roomId,
                     @Param("startTime") LocalDateTime startTime,
                     @Param("endTime") LocalDateTime endTime);

    /** 总纲 A 的释放动作。调用方必须已持有该房间行锁，且与状态变更在同一事务内。 */
    @Delete("delete from occupancy where booking_id = #{bookingId}")
    int deleteByBookingId(@Param("bookingId") Long bookingId);

    @Select("select id from occupancy where booking_id = #{bookingId} limit 1")
    Long findRoomIdByBookingId(@Param("bookingId") Long bookingId);
}
