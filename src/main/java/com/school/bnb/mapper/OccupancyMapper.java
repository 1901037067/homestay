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
     *
     * <p><b>第三个条件"待清扫持续占用"</b>：需求方确认的流程是"保洁收拾好点一下，前台才知道这间房能卖"，
     * 所以 {@code CLEANING} 行代表的是<b>一件还没做完的事</b>，不是一个固定的时段 ——
     * 它必须一直挡到有人点"清扫完成"为止，而不是"预计 45 分钟过去就自动放行"。
     * 若只靠第一段的有限区间比较，就会出现"12:00 退房、缓冲 45 分钟、13:00 的新单照样通过"，
     * 而实际房间可能还没打扫，"可售"这个信号又变得不可信 —— 正是 W5 要消灭的那个信任缺口。
     *
     * <p>代价要写清楚：保洁忘了点，这间房<b>连未来的时段都卖不出去</b>。这是确认式闸门的固有代价，
     * 不是 bug —— 缓解办法是让那一行始终看得见、一键就能释放（见 {@link #findCalendarBlocks} 的
     * 第三条 or 分支），而不是把闸门改回"到点自动放行"。
     */
    @Select("""
            select o.id from occupancy o
            join booking b on b.id = o.booking_id
            where o.room_id = #{roomId}
              and (
                    (o.block_start < #{endTime} and o.block_end > #{startTime})
                 or (b.status = 'IN_USE' and now() >= o.block_end and #{endTime} > o.block_end)
                 or (o.kind = 'CLEANING' and #{endTime} > o.block_start)
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

    /**
     * 退房生成清扫窗口时用：这间房在 {@code after} 之后最早的一段占用从哪开始。
     *
     * <p>晚退房会把清扫时间挤进下一单的时段（12:00 的单拖到 12:15 退、下一单 12:30 开），
     * 若仍按 {@code [12:15, 12:45]} 插清扫行，就和下一单的占用行叠在一起 ——
     * 破坏"同房间行互不重叠"这条全项目的前提。所以要截断到下一段开始之前。
     *
     * <p>判定用 {@code block_end > after} 而不是 {@code block_start > after}：<b>已经开始了、
     * 还没结束</b>的那一段同样要算进来 —— 它是最危险的情形（客人刚进房，前一位才退），
     * 按 {@code block_start} 筛会把它跳过，于是清扫行照样压上去。
     */
    @Select("""
            select min(block_start) from occupancy
            where room_id = #{roomId} and block_end > #{after}
            """)
    LocalDateTime findNextBlockStart(@Param("roomId") Long roomId, @Param("after") LocalDateTime after);

    /** 清扫完成：按房间收尾，前台点的是"这间房打扫好了"，不关心是哪张单。 */
    @Delete("delete from occupancy where room_id = #{roomId} and kind = 'CLEANING'")
    int deleteCleaningByRoom(@Param("roomId") Long roomId);

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
     *
     * <p><b>后两个 or 分支是给"异常任务"开的洞。</b>只按窗口相交筛，会让两类行从今天消失：
     * 昨天本该退房却还没退的在住单（block 整段落在今天窗口之左）、以及还没确认的清扫行。
     * 于是前台看到的是"这间房空着"，而开单时后端又提示仍被占用 —— 界面与判定互相打脸。
     * 更要紧的是<b>处理入口就没了</b>：退房按钮、清扫完成按钮都挂在块上，块看不见就等于办不了。
     * 所以这两类行无论落不落在窗口里都必须返回，前端把它们夹到窗口左缘画出一小段
     * （见 {@code RoomCalendarView.blkStyle} 的 Math.max/min）。
     */
    @Select("""
            <script>
            select o.room_id, o.booking_id, b.booking_no, b.guest_name, b.status as booking_status,
                   o.start_time, o.end_time, o.block_start, o.block_end, o.kind
            from occupancy o
            join booking b on b.id = o.booking_id
            where ((o.block_start &lt; #{to}
                    and o.block_end &gt; #{from})
                or (b.status = 'IN_USE' and o.block_end &lt;= #{from})
                or (o.kind = 'CLEANING'))
            <if test="propertyId != null">
              <!-- 三个 or 分支必须整体加括号再 and 房间过滤：and 的优先级高于 or，
                   少了那对括号，民宿隔离只会作用在最后一个分支上，别的店的房间会漏进来 -->
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
