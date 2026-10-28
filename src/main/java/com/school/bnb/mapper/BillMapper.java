package com.school.bnb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.school.bnb.entity.Bill;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface BillMapper extends BaseMapper<Bill> {

    /**
     * 按订单取账单快照。已结算的订单前端要看的是<b>当时那张单</b>，
     * 不是拿现在的规则重算 —— 重算出来的数会和库里收的钱不一致。
     */
    @Select("select * from bill where booking_id = #{bookingId}")
    Bill findByBookingId(@Param("bookingId") Long bookingId);
}
