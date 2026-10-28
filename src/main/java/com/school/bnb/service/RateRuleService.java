package com.school.bnb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.school.bnb.billing.RateRuleDef;
import com.school.bnb.common.BizException;
import com.school.bnb.common.ErrorCode;
import com.school.bnb.dto.RateRuleReq;
import com.school.bnb.entity.RateRule;
import com.school.bnb.entity.RoomType;
import com.school.bnb.mapper.RateRuleMapper;
import com.school.bnb.vo.RateRuleVO;
import com.school.bnb.vo.RoomTypeVO;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 价格规则的配置入口与加载器。
 *
 * <p><b>删规则是物理删除，不用像房间那样软删。</b>理由不是"它不重要"，而是
 * 账单存了当时的单价与命中的规则名（{@code bill.detail_json}），
 * 规则行本身不再是历史的一部分 —— 删掉它，已结算的账单照样读得出当时的价。
 * 房间反过来不能删，是因为占用/订单只存了 room_id，删了就把历史指向了幽灵房间。
 */
@Service
public class RateRuleService {

    private final RateRuleMapper ruleMapper;
    private final PropertyService propertyService;
    private final RoomTypeService roomTypeService;

    public RateRuleService(RateRuleMapper ruleMapper, PropertyService propertyService,
                           RoomTypeService roomTypeService) {
        this.ruleMapper = ruleMapper;
        this.propertyService = propertyService;
        this.roomTypeService = roomTypeService;
    }

    // ------------------------------------------------------------------ CRUD

    public List<RateRuleVO> list(Long propertyId) {
        List<RateRule> rows = ruleMapper.selectList(new LambdaQueryWrapper<RateRule>()
                .eq(propertyId != null, RateRule::getPropertyId, propertyId)
                .orderByAsc(RateRule::getPropertyId)
                .orderByAsc(RateRule::getPriority)
                .orderByAsc(RateRule::getId));
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, String> propertyNames = propertyService.nameMap();
        Map<Long, String> typeNames = roomTypeService.list(null).stream()
                .collect(Collectors.toMap(RoomTypeVO::id, RoomTypeVO::name, (a, b) -> a));
        List<RateRuleVO> out = new ArrayList<>(rows.size());
        for (RateRule r : rows) {
            Long rt = r.getRoomTypeId();
            out.add(RateRuleVO.of(r, propertyNames.get(r.getPropertyId()),
                    rt == null ? null : typeNames.get(rt), parseWeekdays(r.getWeekdays())));
        }
        return out;
    }

    @Transactional
    public RateRule save(RateRuleReq req) {
        propertyService.require(req.propertyId());
        RateRuleDef.Mode mode = parseMode(req.mode());
        List<Integer> days = normalizeWeekdays(req.weekdays());
        validate(req, mode, days);

        RateRule r = new RateRule();
        r.setId(req.id());
        r.setPropertyId(req.propertyId());
        r.setRoomTypeId(req.roomTypeId());
        r.setName(req.name().trim());
        r.setValidFrom(req.validFrom());
        r.setValidTo(req.validTo());
        r.setWeekdays(toCsv(days));
        r.setStartHour(req.startHour());
        r.setEndHour(req.endHour());
        r.setMode(mode.name());
        r.setValue(req.value());
        r.setPriority(req.priority() == null ? 100 : req.priority());
        r.setEnabled(req.enabled() == null || req.enabled());
        if (req.id() == null) {
            ruleMapper.insert(r);
        } else {
            require(req.id());
            ruleMapper.updateById(r);
        }
        return r;
    }

    @Transactional
    public void delete(Long id) {
        require(id);
        ruleMapper.deleteById(id);
    }

    public RateRule require(Long id) {
        RateRule r = ruleMapper.selectById(id);
        if (r == null) {
            throw new BizException(ErrorCode.RULE_NOT_FOUND, "价格规则不存在: " + id);
        }
        return r;
    }

    // ------------------------------------------------------------------ 加载

    /**
     * 这间房此刻可用的规则集：<b>民宿级 + 自己房型级，且只取启用的</b>。
     *
     * <p>停用规则在这里、而不是在 {@link RateRuleDef} 里过滤掉，是为了让计费层根本不知道
     * "启停"这个概念存在 —— 少一个分支，就少一类"忘了判 enabled"的 bug。
     *
     * <p>没有按日期预筛：一次结算最多几十条规则，全量交给纯函数逐时刻判更简单，
     * 而且 SQL 里写"跨零点时段"的谓词很容易写错，不值得为省几次比较去冒这个险。
     */
    public List<RateRuleDef> enabledRules(Long propertyId, Long roomTypeId) {
        return ruleMapper.selectList(new LambdaQueryWrapper<RateRule>()
                        .eq(RateRule::getPropertyId, propertyId)
                        .eq(RateRule::getEnabled, true)
                        .and(w -> w.isNull(RateRule::getRoomTypeId)
                                .or().eq(RateRule::getRoomTypeId, roomTypeId)))
                .stream()
                // 只有"这条是给这个房型写的"才算房型级；null 是民宿通用
                .map(r -> toDef(r, roomTypeId != null && roomTypeId.equals(r.getRoomTypeId())))
                .toList();
    }

    static RateRuleDef toDef(RateRule r, boolean roomTypeScoped) {
        return new RateRuleDef(r.getId(), r.getName(), toDaySet(parseWeekdays(r.getWeekdays())),
                r.getValidFrom(), r.getValidTo(), r.getStartHour(), r.getEndHour(),
                parseMode(r.getMode()), r.getValue(),
                r.getPriority() == null ? 100 : r.getPriority(), roomTypeScoped);
    }

    // ---------------------------------------------------------------- 校验与转换

    private void validate(RateRuleReq req, RateRuleDef.Mode mode, List<Integer> days) {
        if (req.validTo() != null && req.validFrom() == null) {
            throw new BizException(ErrorCode.RULE_INVALID, "填了结束日期就得有开始日期");
        }
        if (req.validFrom() != null && req.validTo() != null && req.validTo().isBefore(req.validFrom())) {
            throw new BizException(ErrorCode.RULE_INVALID, "结束日期不能早于开始日期");
        }
        if ((req.startHour() == null) != (req.endHour() == null)) {
            throw new BizException(ErrorCode.RULE_INVALID, "时段窗口的起止必须一起填或一起留空");
        }
        if (mode == RateRuleDef.Mode.MULTIPLY && req.value().signum() <= 0) {
            // 倍率 0 = 这房白住，几乎肯定是前台想填 OVERRIDE 却选错了模式
            throw new BizException(ErrorCode.RULE_INVALID, "倍率必须大于 0（要打 85 折填 0.85，想一口价请改用 OVERRIDE）");
        }
        if (mode == RateRuleDef.Mode.MULTIPLY && req.value().compareTo(new BigDecimal("10")) > 0) {
            throw new BizException(ErrorCode.RULE_INVALID, "倍率超过 10 一般是填错了单位");
        }
        if (req.roomTypeId() != null) {
            RoomType type = roomTypeService.require(req.roomTypeId());
            if (!type.getPropertyId().equals(req.propertyId())) {
                throw new BizException(ErrorCode.RULE_INVALID,
                        "房型「" + type.getName() + "」不属于该民宿，规则会永远命中不到");
            }
        }
    }

    static RateRuleDef.Mode parseMode(String raw) {
        try {
            return RateRuleDef.Mode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BizException(ErrorCode.RULE_INVALID,
                    "计价方式只能是 OVERRIDE（一口价）或 MULTIPLY（倍率），收到 " + raw);
        }
    }

    /** 前端传 [6,7] 这样的列表；去重、排序、越界直接拒 —— 静默丢掉一个 8 会让规则少覆盖一天。 */
    static List<Integer> normalizeWeekdays(List<Integer> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Set<Integer> uniq = new java.util.TreeSet<>();
        for (Integer d : raw) {
            if (d == null || d < 1 || d > 7) {
                throw new BizException(ErrorCode.RULE_INVALID, "星期只能用 1..7（1=周一），收到 " + d);
            }
            uniq.add(d);
        }
        return List.copyOf(uniq);
    }

    static List<Integer> parseWeekdays(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<Integer> out = new ArrayList<>();
        for (String part : csv.split(",")) {
            if (!part.isBlank()) {
                out.add(Integer.parseInt(part.trim()));
            }
        }
        return out;
    }

    private static String toCsv(List<Integer> days) {
        return days.isEmpty() ? null : days.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static Set<DayOfWeek> toDaySet(List<Integer> days) {
        Set<DayOfWeek> out = EnumSet.noneOf(DayOfWeek.class);
        days.forEach(d -> out.add(DayOfWeek.of(d)));
        return out;
    }
}
