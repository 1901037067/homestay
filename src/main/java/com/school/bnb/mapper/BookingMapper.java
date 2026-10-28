package com.school.bnb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.school.bnb.entity.Booking;
import com.school.bnb.vo.HoldCandidate;
import java.math.BigDecimal;
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

    /**
     * 加时。<b>把原 end_time 写进 where 当 CAS</b> —— 两个加时请求先后执行时，
     * 后一个若基于自己那份过期快照，就会把前一个的结果改回去（甚至改短）。
     * 只认"当前库里就是这个 end"，否则返回 0 让调用方重读重试。
     */
    @Update("""
            update booking set end_time = #{end}
            where id = #{id} and status = 'IN_USE' and end_time = #{expectedEnd}
            """)
    int extendIfInUse(@Param("id") Long id,
                      @Param("end") LocalDateTime end,
                      @Param("expectedEnd") LocalDateTime expectedEnd);

    /**
     * 下单时把计费基准冻结到订单上。单独一条 update 而不是塞进 insert：
     * 这两个值来自房型（service 层才拿得到），而 insert 在内核里。
     */
    @Update("""
            update booking set covered_minutes = #{covered}, charge_unit_minutes = #{unit}
            where id = #{id}
            """)
    int snapshotPricing(@Param("id") Long id, @Param("covered") int covered, @Param("unit") int unit);

    /**
     * 结算。<b>把金额和状态在同一条 update 里写完</b>，不留"状态改了、钱还没落"的中间态；
     * 状态条件写在 where 里，重复点结算的第二次返回 0。
     */
    @Update("""
            update booking set status = 'SETTLED', amount = #{amount}
            where id = #{id} and status = 'CHECKED_OUT'
            """)
    int settleIfCheckedOut(@Param("id") Long id, @Param("amount") BigDecimal amount);

    /**
     * 按 id 当前读。<b>与 {@code selectById} 的区别不是"加不加锁"，而是"读不读缓存"</b>：
     * 同一事务里前面已经 selectById 过一次的话，再查同一个 id 可能直接命中 MyBatis 一级缓存，
     * 拿到加锁之前的旧对象。凡是要基于"锁内最新状态"下判断的地方，都走这一个。
     */
    @Select("select * from booking where id = #{id} for update")
    Booking findByIdForUpdate(@Param("id") Long id);

    /**
     * 幂等回放用。当前读 —— 普通 SELECT 在 RR 下可能看不到另一笔刚提交的同 requestId 订单，
     * 那样幂等就形同虚设。
     */
    @Select("select * from booking where request_id = #{requestId} for update")
    Booking findByRequestIdForUpdate(@Param("requestId") String requestId);
}
