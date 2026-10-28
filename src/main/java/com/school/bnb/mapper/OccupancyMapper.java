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

    /** 可售区间计算用：取某房在窗口内压着的所有块，按 block_start 升序好做区间相减。 */
    @Select("""
            select id, room_id, booking_id, start_time, end_time, block_start, block_end, kind, created_at
            from occupancy
            where room_id = #{roomId}
              and block_start < #{endTime}
              and block_end > #{startTime}
            order by block_start
            """)
    java.util.List<Occupancy> findBlocksIn(@Param("roomId") Long roomId,
                                           @Param("startTime") LocalDateTime startTime,
                                           @Param("endTime") LocalDateTime endTime);

    /**
     * 房态日历用：一次取出营业日窗口内所有占用块（含客人信息），避免按房间逐个查。
     *
     * <p>判定用 block 而不是业务时间 —— 日历要画的就是"这段时间被挡住了"，
     * 缓冲期虽然没人住，但确实不可售，不画出来前台就会在缓冲时段上重复开单。
     */
    @Select("""
            <script>
            select o.room_id, o.booking_id, b.booking_no, b.guest_name, b.status as booking_status,
                   o.start_time, o.end_time, o.block_start, o.block_end, o.kind
            from occupancy o
            join booking b on b.id = o.booking_id
            where o.block_start &lt; #{to}
              and o.block_end &gt; #{from}
            <if test="propertyId != null">
              and o.room_id in (select id from room where property_id = #{propertyId})
            </if>
            order by o.block_start
            </script>
            """)
    java.util.List<com.school.bnb.vo.CalendarBlock> findCalendarBlocks(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("propertyId") Long propertyId);
}
