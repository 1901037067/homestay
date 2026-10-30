package com.school.bnb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.school.bnb.common.AccessScope;
import com.school.bnb.common.BizException;
import com.school.bnb.common.ErrorCode;
import com.school.bnb.dto.PropertyReq;
import com.school.bnb.entity.Property;
import com.school.bnb.entity.Room;
import com.school.bnb.entity.RoomType;
import com.school.bnb.entity.SysUser;
import com.school.bnb.mapper.PropertyMapper;
import com.school.bnb.mapper.RoomMapper;
import com.school.bnb.mapper.RoomTypeMapper;
import com.school.bnb.mapper.SysUserMapper;
import com.school.bnb.vo.PropertyCount;
import com.school.bnb.vo.PropertyVO;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PropertyService {

    private final PropertyMapper propertyMapper;
    private final RoomTypeMapper roomTypeMapper;
    private final RoomMapper roomMapper;
    private final SysUserMapper sysUserMapper;
    private final AccessScope scope;

    public PropertyService(PropertyMapper propertyMapper, RoomTypeMapper roomTypeMapper, RoomMapper roomMapper,
                           SysUserMapper sysUserMapper, AccessScope scope) {
        this.propertyMapper = propertyMapper;
        this.roomTypeMapper = roomTypeMapper;
        this.roomMapper = roomMapper;
        this.sysUserMapper = sysUserMapper;
        this.scope = scope;
    }

    public List<PropertyVO> list() {
        Long bound = scope.boundPropertyId();
        List<Property> properties = propertyMapper.selectList(new LambdaQueryWrapper<Property>()
                .eq(bound != null, Property::getId, bound)
                .orderByAsc(Property::getId));
        if (properties.isEmpty()) {
            return List.of();
        }
        // 原来是"每家店各查两次"（2N 条 SQL）。改成两条 group by 一次取全表，再按 id 取 ——
        // 数据量小的时候无感，但那是论文里不好写的形状。delete() 里按 id 的计数保持不动：
        // 它只对一家店判一次，本来就不是 N+1
        Map<Long, Long> roomTypes = counts(roomTypeMapper.countByProperty());
        Map<Long, Long> rooms = counts(roomMapper.countByProperty());
        return properties.stream()
                .map(p -> PropertyVO.of(p,
                        roomTypes.getOrDefault(p.getId(), 0L),
                        rooms.getOrDefault(p.getId(), 0L)))
                .toList();
    }

    private static Map<Long, Long> counts(List<PropertyCount> rows) {
        return rows.stream()
                .collect(Collectors.toMap(PropertyCount::getPropertyId, PropertyCount::getTotal));
    }

    /**
     * 按 id 取一家民宿，<b>并校验它在当前账号的可见范围内</b>。
     *
     * <p>校验放在 require() 而不是各调用点：这一句同时堵掉"详情接口按 id 读别店"和
     * "PUT 路径给别店 id、请求体给本店 propertyId 的覆盖式改写"两条路 —— 第十轮审阅的
     * 第 1、2 条本质是同一个洞：只认新归属、不认原归属。收在这里，新增的用法自动带上。
     */
    public Property require(Long id) {
        Property p = propertyMapper.selectById(id);
        if (p == null) {
            throw new BizException(ErrorCode.PROPERTY_NOT_FOUND, "民宿不存在: " + id);
        }
        scope.requireVisible(p.getId());
        return p;
    }

    /** 给列表页批量取名字用，避免逐行查库。 */
    public Map<Long, String> nameMap() {
        return propertyMapper.selectList(null).stream()
                .collect(Collectors.toMap(Property::getId, Property::getName));
    }

    @Transactional
    public Property save(PropertyReq req) {
        // 民宿是"租户根"：建店、改店名与营业日切割点都只归店长。
        // 切割点决定整家店的报表归属，让某家店的前台去改它，等于让被约束的人改约束
        scope.requireManager();
        // 更新走"以库里现值为底，只覆盖来的字段"。两个原因：
        // 一是没有存在性检查时 updateById 对不存在的 id 影响 0 行、接口照样返回 200，
        //        调用方以为改成功了（审阅抓出）；
        // 二是前端表单可能只带名称与地址，若把缺省的营业参数一律回落到硬编码默认值，
        //        一次"只想改名字"的保存会顺手把这家店调过的切割点重置成 06:00。
        Property existing = req.id() == null ? null : require(req.id());
        Property p = new Property();
        p.setId(req.id());
        p.setName(req.name().trim());
        p.setAddress(req.address() == null && existing != null ? existing.getAddress() : req.address());
        p.setDayCutTime(orDefault(req.dayCutTime(), existing, Property::getDayCutTime, LocalTime.of(6, 0)));
        // 切割点必须是整点。报表与日历把"一小时"当横轴一格，营业日归属却按时刻精确推算；
        // 06:30 这种切割点会让"归哪一天"用分钟、"画在第几格"只能按整点算，两处对不上 ——
        // 实测 06:15 的单会被归到前一天、却画在属于新一天的格子里（审阅抓出的口径问题）。
        // 对民宿业务来说 06:00 与 06:30 没有实际差别，限制成整点比让每个公式都带着分钟更安全
        if (p.getDayCutTime().getMinute() != 0 || p.getDayCutTime().getSecond() != 0) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "营业日切割点必须是整点（如 06:00），收到 " + p.getDayCutTime()
                            + "；分钟级切割会让报表分桶与日历格子对不齐");
        }
        p.setOpenTime(orDefault(req.openTime(), existing, Property::getOpenTime, LocalTime.of(8, 0)));
        p.setCloseTime(orDefault(req.closeTime(), existing, Property::getCloseTime, LocalTime.of(22, 0)));
        if (req.id() == null) {
            propertyMapper.insert(p);
        } else {
            propertyMapper.updateById(p);
        }
        return p;
    }

    /** 实施计划 2.6：有下级数据就不许物理删除。 */
    @Transactional
    public void delete(Long id) {
        scope.requireManager();
        require(id);
        long users = countUsers(id);
        if (countRoomTypes(id) > 0 || countRooms(id) > 0 || users > 0) {
            throw new BizException(ErrorCode.HAS_CHILDREN,
                    "该民宿下仍有房型、房间或账号（当前账号数 " + users + "），请先处理它们；"
                            + "或只清空地址后保留记录");
        }
        propertyMapper.deleteById(id);
    }

    /** 绑在这家店上的账号数 —— 不加这道守卫，删店会被外键以 1451 顶回来，前台看不到人话提示。 */
    private long countUsers(Long propertyId) {
        Long n = sysUserMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getPropertyId, propertyId));
        return n == null ? 0 : n;
    }

    private long countRoomTypes(Long propertyId) {
        Long n = roomTypeMapper.selectCount(
                new LambdaQueryWrapper<RoomType>().eq(RoomType::getPropertyId, propertyId));
        return n == null ? 0 : n;
    }

    private long countRooms(Long propertyId) {
        Long n = roomMapper.selectCount(
                new LambdaQueryWrapper<Room>().eq(Room::getPropertyId, propertyId));
        return n == null ? 0 : n;
    }

    private static LocalTime orDefault(LocalTime v, Property existing,
                                       Function<Property, LocalTime> stored,
                                       LocalTime hardDefault) {
        if (v != null) {
            return v;
        }
        if (existing == null) {
            return hardDefault;
        }
        LocalTime storedValue = stored.apply(existing);
        return storedValue == null ? hardDefault : storedValue;
    }
}
