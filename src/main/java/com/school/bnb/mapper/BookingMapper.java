package com.school.bnb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.school.bnb.entity.Booking;
import com.school.bnb.vo.HoldCandidate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface BookingMapper extends BaseMapper<Booking> {

    /**
     * 超时候选的预占单，<b>带上 room_id</b> —— 调用方要按 room_id 升序加锁，
     * 否则和其它写路径的加锁顺序不一致会死锁（OversellAuditIT 那轮审阅抓出来的）。
     */
    @Select("""
            select id as booking_id, room_id from booking
            where status = 'HELD' and hold_expire_at < #{now}
            order by room_id, id
            """)
    List<HoldCandidate> findExpiredHolds(@Param("now") LocalDateTime now);

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

    // -------------------------------------------------------------- 状态流转
    // 全部走"带原状态的条件更新"：预检查可能基于过期快照，只有影响行数才是裁判。

    @Update("update booking set status = 'CONFIRMED' where id = #{id} and status = 'HELD'")
    int confirmIfHeld(@Param("id") Long id);

    @Update("update booking set status = 'CANCELLED' where id = #{id} and status in ('HELD', 'CONFIRMED')")
    int cancelIfNotStarted(@Param("id") Long id);

    @Update("""
            update booking set status = 'IN_USE', actual_check_in = #{at}
            where id = #{id} and status = 'CONFIRMED'
            """)
    int checkInIfConfirmed(@Param("id") Long id, @Param("at") LocalDateTime at);

    @Update("""
            update booking set status = 'CHECKED_OUT', actual_check_out = #{at}
            where id = #{id} and status = 'IN_USE'
            """)
    int checkOutIfInUse(@Param("id") Long id, @Param("at") LocalDateTime at);

    /** 改期：只有还没入住的单能改，状态条件写在 where 里而不是靠预检查。 */
    @Update("""
            update booking set start_time = #{start}, end_time = #{end}
            where id = #{id} and status in ('HELD', 'CONFIRMED')
            """)
    int rescheduleIfOpen(@Param("id") Long id,
                         @Param("start") LocalDateTime start,
                         @Param("end") LocalDateTime end);

    /** 加时：只有在住单能加。 */
    @Update("update booking set end_time = #{end} where id = #{id} and status = 'IN_USE'")
    int extendIfInUse(@Param("id") Long id, @Param("end") LocalDateTime end);

    /**
     * 幂等回放用。当前读 —— 普通 SELECT 在 RR 下可能看不到另一笔刚提交的同 requestId 订单，
     * 那样幂等就形同虚设。
     */
    @Select("select * from booking where request_id = #{requestId} for update")
    Booking findByRequestIdForUpdate(@Param("requestId") String requestId);
}
