package com.school.bnb.mapper;

import com.school.bnb.vo.StatRow;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 报表取数。<b>只做范围扫描，不做聚合</b> —— 营业日切割、跨零点摊小时、营收归属都在
 * {@link com.school.bnb.stats.StatsAggregator} 里用纯函数算。
 *
 * <p>为什么不在 SQL 里 group by：切割点来自 property 配置、跨零点要把一单摊成多个小时格、
 * 取消单要剔除、已结算与待结算要分开累加 —— 这些写成一条 SQL 既没人读得懂，
 * 也<b>没法单测</b>（每个分支都得连库造数据）。分离之后 SQL 只有这几行，
 * 边界条件全在纯函数上被测住。
 *
 * <p>时间窗刻意取一个<b>宽松的超集</b>（起止各多放一天），精确归属交给聚合器。
 * 好处是 SQL 里不需要再算"切割点之后"的偏移：那种 offset 写错一次就是"少算/多算一天"，
 * 而它只在凌晨的单上暴露，最难在演示时被发现。一个边界只准有一个权威。
 */
public interface StatsMapper {

    @Select("""
            select count(1) from room
            where property_id = #{propertyId} and status = 'ON_SALE'
            """)
    int countSellableRooms(@Param("propertyId") Long propertyId);

    @Select("""
            <script>
            select b.id as booking_id, b.room_id, r.property_id, r.room_no,
                   b.sale_type, b.status, b.start_time, b.end_time, b.actual_check_out, b.amount
            from booking b
            join room r on r.id = b.room_id
            where b.start_time &gt;= #{from}
              and b.start_time &lt; #{to}
            <if test="propertyId != null">
              and r.property_id = #{propertyId}
            </if>
            order by b.start_time
            </script>
            """)
    List<StatRow> scanBookings(@Param("propertyId") Long propertyId,
                               @Param("from") LocalDateTime from,
                               @Param("to") LocalDateTime to);
}
