package com.school.bnb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.school.bnb.entity.Occupancy;
import com.school.bnb.vo.CalendarBlock;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface OccupancyMapper extends BaseMapper<Occupancy> {

    /**
     * 半开区间重叠判定 —— <b>必须是当前读，不能是普通 SELECT</b>。
     *
     * <p>房间行锁只解决"同一房间的写入互斥"，不解决<b>可见性</b>。RR 下一致性读视图在事务内
     * 第一条普通 SELECT 时就建立了；真实入口 {@code BookingService.create} 在进内核之前
     * 还要先查房间/房型/民宿，所以快照建立点早于拿锁 —— 拿锁后那条普通 SELECT
     * 看不到别人刚提交的占用，直接超卖（OversellAuditIT 实测 12/12 轮超卖）。
     *
     * <p>{@code FOR UPDATE} 是当前读，永远读最新已提交版本，与快照和隔离级别无关。
     * 返回 id 而不是 count，是因为 MySQL 不允许聚合查询带 FOR UPDATE。
     */
    @Select("""
            select id from occupancy
            where room_id = #{roomId}
              and block_start < #{endTime}
              and block_end > #{startTime}
            for update
            """)
    List<Long> lockOverlapping(@Param("roomId") Long roomId,
                               @Param("startTime") LocalDateTime startTime,
                               @Param("endTime") LocalDateTime endTime);

    /** 只读版，给报表与调试用 —— 不要拿它做冲突判定。 */
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
    List<Occupancy> findBlocksIn(@Param("roomId") Long roomId,
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
    List<CalendarBlock> findCalendarBlocks(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("propertyId") Long propertyId);
}
