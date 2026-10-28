package com.school.bnb.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.school.bnb.booking.BookingKernel;
import com.school.bnb.booking.BookingStateMachine;
import com.school.bnb.booking.SaleType;
import com.school.bnb.booking.SellPolicy;
import com.school.bnb.billing.PriceCalculator;
import com.school.bnb.billing.PricingFrames;
import com.school.bnb.billing.RateRuleDef;
import com.school.bnb.common.AuthContext;
import com.school.bnb.common.BizException;
import com.school.bnb.common.ErrorCode;
import com.school.bnb.common.LoginInterceptor;
import com.school.bnb.entity.Bill;
import com.school.bnb.entity.Booking;
import com.school.bnb.entity.RoomType;
import com.school.bnb.mapper.BillMapper;
import com.school.bnb.mapper.BookingMapper;
import com.school.bnb.mapper.RoomMapper;
import com.school.bnb.vo.BillVO;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 计费服务 —— <b>算钱这件事全部委托给纯函数 {@link PriceCalculator}</b>，这一层只做四件事：
 * 凑齐输入、守住状态机、把结果落成快照、给出人话提示。
 *
 * <p>为什么这么拆：金额若在这层边查边算，"超时费怎么取整""规则怎么决胜"这些最容易被答辩追问的
 * 口径就只能连库验证；拆成纯函数之后它们有一组不打数据库、毫秒级跑完的表驱动用例
 * （{@code PriceCalculatorTest}）。
 *
 * <h3>三个入口的分工</h3>
 * <ul>
 *   <li>{@link #view} 已结算读快照、否则实时试算 —— 前端显示用它；</li>
 *   <li>{@link #preview} 永远按当前规则重算，<b>不写库</b>；</li>
 *   <li>{@link #settle} 收款：落 {@code bill} 快照行并把订单推到 SETTLED。</li>
 * </ul>
 *
 * <p>试算不落库是有意的：前台点一下"看看多少钱"不该留下痕迹，否则"试算十次、结算一次"
 * 的单据分不清哪张才是收钱的那张。
 */
@Slf4j
@Service
public class BillingService {

    /** 能算出钱来的状态。取消与超时释放的单没收过房，没什么可算。 */
    private static final List<String> BILLABLE = List.of(
            Booking.CONFIRMED, Booking.IN_USE, Booking.CHECKED_OUT, Booking.SETTLED);

    private final BookingKernel kernel;
    private final BookingMapper bookingMapper;
    private final RoomMapper roomMapper;
    private final BillMapper billMapper;
    private final RoomContext roomContext;
    private final RateRuleService rateRuleService;
    private final OperationLogService opLog;
    private final ObjectMapper json;

    public BillingService(BookingKernel kernel, BookingMapper bookingMapper, RoomMapper roomMapper,
                          BillMapper billMapper, RoomContext roomContext, RateRuleService rateRuleService,
                          OperationLogService opLog, ObjectMapper json) {
        this.kernel = kernel;
        this.bookingMapper = bookingMapper;
        this.roomMapper = roomMapper;
        this.billMapper = billMapper;
        this.roomContext = roomContext;
        this.rateRuleService = rateRuleService;
        this.opLog = opLog;
        this.json = json;
    }

    // ------------------------------------------------------------------ 读

    /**
     * 前端显示用。已结算的单<b>必须读快照</b> —— 拿现在的规则重算会得出和实际收款不同的数，
     * 那种"系统自己把自己的账改了口"是账单功能最不能接受的失败。
     */
    public BillVO view(Long bookingId) {
        Booking b = kernel.require(bookingId);
        if (Booking.SETTLED.equals(b.getStatus())) {
            Bill bill = billMapper.findByBookingId(bookingId);
            if (bill != null) {
                return BillVO.ofBill(bill, b, readLines(bill.getDetailJson()),
                        notice(b, bill.getCalcEnd()));
            }
            // 状态 SETTLED 却没有账单行 = 有人手工改过状态。回落到重算并留 warn，
            // 比给前台一个它查不出来的错误有用
            log.warn("订单 {} 状态 SETTLED 但查无账单，回落到实时试算", b.getBookingNo());
        }
        return preview(b);
    }

    /** 按当前规则重算，不写库。 */
    public BillVO preview(Long bookingId) {
        return preview(kernel.require(bookingId));
    }

    private BillVO preview(Booking b) {
        Plan p = plan(b, roomContext.of(b.getRoomId()));
        return p.toVO(b, notice(b, p.calcEnd()));
    }

    // ------------------------------------------------------------------ 写

    /**
     * 结算。<b>先赢条件更新、再插账单行。</b>
     *
     * <p>顺序反过来会怎样：并发双击时两条线程各自算好同一个金额，都去 INSERT，
     * 第二条撞 {@code uk_booking_bill} 抛异常把整个事务带回滚 —— 结果照样正确，
     * 但正确性来自唯一键报错而不是来自状态机，错误信息也会变成一句谁也看不懂的约束冲突。
     * 按内核的第 3 条铁律，条件更新才是裁判，预检查只负责给一句人话。
     */
    @Transactional
    public BillVO settle(Long bookingId) {
        Booking b = kernel.require(bookingId);
        // 结算不改 occupancy，但照样先锁房间行：与开单、退房同序，
        // 免得将来把"结算同时释放占用"合进来时又引出第二个 AB-BA 死锁
        if (roomMapper.selectForUpdate(b.getRoomId()) == null) {
            throw new BizException(ErrorCode.ROOM_NOT_FOUND, "房间不存在: " + b.getRoomId());
        }
        Bill existing = billMapper.findByBookingId(bookingId);
        if (existing != null) {
            throw new BizException(ErrorCode.BOOKING_ILLEGAL_STATE,
                    "这张单已结算（账单 #" + existing.getId() + "），不能重复收款");
        }
        if (!Booking.CHECKED_OUT.equals(b.getStatus())) {
            throw new BizException(ErrorCode.BOOKING_ILLEGAL_STATE,
                    BookingStateMachine.describe(b.getStatus(), Booking.SETTLED));
        }

        Plan p = plan(b, roomContext.of(b.getRoomId()));
        BigDecimal total = p.quote().total();
        if (bookingMapper.settleIfCheckedOut(bookingId, total) == 0) {
            // 状态在刚才那一瞬间被别人改过，用当前库里的那个状态重说一句
            throw new BizException(ErrorCode.BOOKING_ILLEGAL_STATE,
                    BookingStateMachine.describe(kernel.require(bookingId).getStatus(), Booking.SETTLED));
        }

        LoginInterceptor.LoginUser user = AuthContext.current();
        Bill bill = new Bill();
        bill.setBookingId(bookingId);
        bill.setRoomNo(p.ctx().room().getRoomNo());
        bill.setGuestName(b.getGuestName());
        bill.setSaleType(b.getSaleType());
        bill.setCalcStart(p.calcStart());
        bill.setCalcEnd(p.calcEnd());
        bill.setCoveredMinutes(p.coveredMinutes());
        bill.setChargeUnitMinutes(p.chargeUnitMinutes());
        bill.setBaseAmount(p.quote().baseAmount());
        bill.setOverageAmount(p.quote().overageAmount());
        bill.setTotalAmount(total);
        bill.setDetailJson(writeLines(p.lines()));
        bill.setSettledAt(LocalDateTime.now());
        bill.setOperatorId(user == null ? null : user.id());
        bill.setOperatorName(user == null ? null : user.displayName());
        billMapper.insert(bill);

        Booking settled = kernel.require(bookingId);
        opLog.record("BOOKING_SETTLE", "booking", bookingId,
                String.format("%s %s｜收款 %s 元（其中超时费 %s 元）", bill.getRoomNo(),
                        settled.getBookingNo(), total.toPlainString(),
                        p.quote().overageAmount().toPlainString()),
                null, bill);
        log.info("结算 booking={} 合计={} 超时={}", settled.getBookingNo(), total, p.quote().overageAmount());
        return BillVO.ofBill(bill, settled, p.lines(), notice(b, p.calcEnd()));
    }

    // ------------------------------------------------------------ 凑纯函数的输入

    /**
     * 一次算价需要的全部上下文。凑齐之后 PriceCalculator 再不需要别的依赖。
     *
     * @param coveredMinutes 起步价覆盖的分钟数
     * @param calcEnd        计费终点（早退不退、晚走要补之后的那个时刻）
     */
    private record Plan(RoomContext.Data ctx,
                        int coveredMinutes,
                        int chargeUnitMinutes,
                        LocalDateTime calcStart,
                        LocalDateTime calcEnd,
                        PriceCalculator.Quote quote) {

        List<BillVO.Line> lines() {
            return quote.lines().stream().map(BillVO.Line::of).toList();
        }

        BillVO toVO(Booking b, String notice) {
            return BillVO.ofQuote(b, ctx.room().getRoomNo(), coveredMinutes, chargeUnitMinutes,
                    calcEnd, notice, quote);
        }
    }

    private Plan plan(Booking b, RoomContext.Data d) {
        if (!BILLABLE.contains(b.getStatus())) {
            throw new BizException(ErrorCode.NOTHING_TO_BILL,
                    "订单当前 " + b.getStatus() + " —— 没入住过的单不产生房费，无需计费");
        }
        RoomType type = d.type();
        // 优先用下单时冻结的基准；历史订单没有快照（V7 之前）才现算，行为与升级前一致。
        // 这一条是审阅指出的漂移风险：14:00→次日12:00 的全日单，若把模板改成 14:00→次日10:00，
        // 结算时起步覆盖从 22 小时变 20 小时，凭空多出两小时超时费
        PricingFrames.Frames frames = PricingFrames.of(parseSale(b), type, b.getStartTime());
        int covered = b.getCoveredMinutes() == null ? frames.coveredMinutes() : b.getCoveredMinutes();
        int unit = b.getChargeUnitMinutes() == null ? frames.chargeUnitMinutes() : b.getChargeUnitMinutes();
        LocalDateTime calcEnd = calcEnd(b);
        List<RateRuleDef> rules = rateRuleService.enabledRules(d.room().getPropertyId(),
                d.room().getRoomTypeId());

        PriceCalculator.Quote q = PriceCalculator.price(new PriceCalculator.Input(
                b.getStartTime(), calcEnd, covered, type.getBasePrice(),
                type.getExtraHourPrice(), unit, rules));
        return new Plan(d, covered, unit, b.getStartTime(), calcEnd, q);
    }

    private static SaleType parseSale(Booking b) {
        return SaleType.FULL_DAY.name().equals(b.getSaleType()) ? SaleType.FULL_DAY : SaleType.HOURLY;
    }

    /**
     * 计费终点。<b>早退不退、晚走要补</b>（实施计划 3.7 已确认的业务口径）：
     * 实际退房比计划早仍按预订终点收，比计划晚就把多出来的时间并进超时费。
     */
    private static LocalDateTime calcEnd(Booking b) {
        LocalDateTime out = b.getActualCheckOut();
        return out != null && out.isAfter(b.getEndTime()) ? out : b.getEndTime();
    }

    /** 把容易被误解的口径写进返回体，而不是让前台自己猜"为什么提前退房钱没少"。 */
    private static String notice(Booking b, LocalDateTime calcEnd) {
        LocalDateTime out = b.getActualCheckOut();
        if (out == null) {
            return Booking.IN_USE.equals(b.getStatus()) || Booking.CONFIRMED.equals(b.getStatus())
                    ? "尚未退房，按预订区间试算；实际退房后再结算" : null;
        }
        if (out.isAfter(b.getEndTime())) {
            return String.format("实际退房晚于计划 %d 分钟，超时部分已并入超时费",
                    Duration.between(b.getEndTime(), out).toMinutes());
        }
        if (out.isBefore(calcEnd)) {
            return "客人提前退房，起步价与已订时长按原单收，不退差价";
        }
        return null;
    }

    // ---------------------------------------------------------------- JSON 快照

    private String writeLines(List<BillVO.Line> lines) {
        try {
            return json.writeValueAsString(lines);
        } catch (Exception e) {
            // 明细写不出去就等于账单没有快照，这条必须响，不能静默存个空数组
            throw new IllegalStateException("账单明细序列化失败", e);
        }
    }

    private List<BillVO.Line> readLines(String raw) {
        try {
            return json.readValue(raw, new TypeReference<List<BillVO.Line>>() {
            });
        } catch (Exception e) {
            log.warn("账单明细读不出来，按空明细返回", e);
            return List.of();
        }
    }
}
