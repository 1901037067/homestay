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
     * <p>房间行锁只保证同一房间的写入<b>互斥</b>，不保证<b>可见性</b>。RR 下一致性读视图
     * 在事务内第一条普通 SELECT 时就建立了，而真实入口 {@code BookingService.create}
     * 进内核之前还要先查房间/房型/民宿，所以快照建立点早于拿锁 —— 拿锁后的普通 SELECT
     * 看不到别人刚提交的占用，直接超卖（{@code OversellAuditIT} 实测 12/12 轮超卖）。
     * {@code FOR UPDATE} 是当前读，永远读最新已提交版本。返回 id 而不是 count，
     * 因为 MySQL 不允许聚合查询带 FOR UPDATE。
     *
     * <p><b>第二个条件"超时未退房即持续占用"</b>：客人办了入住又已过计划离店时刻，
     * 这间房什么时候空出来只有退房动作知道。若只比固定的 {@code block_end}，就会出现
     * "计划 12:00 离店、客人 13:00 还没走、13:00 之后的新单照样通过检测" —— 把有人住的房卖出去。
     *
     * <p>条件刻意收窄成"<b>现在</b>已过 block_end 且请求触到 block_end 之后"，不是"只要在住就全挡"：
     * 后者会把"客人 14:00 入住、先把今晚 20:00 之后预卖出去"这种正常操作一起拒掉。
     * 计划离店时刻是客人对房间的承诺，未到期就按承诺算；到期还没退房才是未知状态，需要拦。
     */
    @Select("""
            select o.id from occupancy o
            join booking b on b.id = o.booking_id
            where o.room_id = #{roomId}
              and (
                    (o.block_start < #{endTime} and o.block_end > #{startTime})
                 or (b.status = 'IN_USE' and now() >= o.block_end and #{endTime} > o.block_end)
              )
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

    /** 取某单的房时间占用行（kind=ROOM），入住时要改写它的区间。 */
    @Select("select * from occupancy where booking_id = #{bookingId} and kind = 'ROOM' limit 1")
    Occupancy findRoomBlock(@Param("bookingId") Long bookingId);

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
