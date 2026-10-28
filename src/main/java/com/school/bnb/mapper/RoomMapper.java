package com.school.bnb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.school.bnb.entity.Room;
import java.time.LocalDateTime;
import java.util.List;
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

    /**
     * 某时间窗内仍可整段卖出的房间。用 NOT EXISTS 一条 SQL 搞定，
     * 别在 Java 里对每个房间各发一次冲突查询 —— 那会变成 N+1。
     *
     * <p><b>必须把新单自己的清扫缓冲一起算</b>。只比较请求的 start/end 会漏判：
     * 已有块到 12:45，新单 13:00 入住、该房型入住前缓冲 30 分钟 → 新单实际 block 从 12:30 起，
     * 真冲突；但按 13:00 比就返回"可售"，前端点了再被内核拒。缓冲挂在 room_type 上，
     * 所以这里要 join 房型表，不能只在 Java 侧传一对固定区间进来。
     */
    @Select("""
            <script>
            select r.id, r.property_id, r.room_type_id, r.room_no, r.floor_no, r.status, r.created_at, r.updated_at
            from room r
            join room_type rt on rt.id = r.room_type_id
            where r.status = 'ON_SALE'
            <if test="propertyId != null"> and r.property_id = #{propertyId} </if>
              and not exists (
                select 1 from occupancy o
                join booking b on b.id = o.booking_id
                where o.room_id = r.id
                  and (
                        (o.block_start &lt; date_add(#{endTime}, interval rt.clean_after_minutes minute)
                         and o.block_end &gt; date_sub(#{startTime}, interval rt.clean_before_minutes minute))
                     or (b.status = 'IN_USE' and now() &gt;= o.block_end
                         and date_add(#{endTime}, interval rt.clean_after_minutes minute) &gt; o.block_end)
                  )
              )
            order by r.property_id, r.room_no
            </script>
            """)
    List<Room> findAvailable(@Param("propertyId") Long propertyId,
                             @Param("startTime") LocalDateTime startTime,
                             @Param("endTime") LocalDateTime endTime);
}
