package com.school.bnb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.school.bnb.common.BizException;
import com.school.bnb.common.ErrorCode;
import com.school.bnb.dto.RoomTypeReq;
import com.school.bnb.entity.Room;
import com.school.bnb.entity.RoomType;
import com.school.bnb.mapper.RoomMapper;
import com.school.bnb.mapper.RoomTypeMapper;
import com.school.bnb.vo.RoomTypeVO;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoomTypeService {

    private final RoomTypeMapper roomTypeMapper;
    private final RoomMapper roomMapper;
    private final PropertyService propertyService;
    private final OperationLogService opLog;

    public RoomTypeService(RoomTypeMapper roomTypeMapper, RoomMapper roomMapper,
                           PropertyService propertyService, OperationLogService opLog) {
        this.roomTypeMapper = roomTypeMapper;
        this.roomMapper = roomMapper;
        this.propertyService = propertyService;
        this.opLog = opLog;
    }

    public List<RoomTypeVO> list(Long propertyId) {
        LambdaQueryWrapper<RoomType> q = new LambdaQueryWrapper<RoomType>()
                .eq(propertyId != null, RoomType::getPropertyId, propertyId)
                .orderByAsc(RoomType::getPropertyId)
                .orderByAsc(RoomType::getId);
        List<RoomType> types = roomTypeMapper.selectList(q);
        if (types.isEmpty()) {
            return List.of();
        }
        // 一次性把民宿名取全，别在 map 里逐行查（N+1）
        Map<Long, String> names = propertyService.nameMap();
        return types.stream()
                .map(t -> RoomTypeVO.of(t, names.get(t.getPropertyId()), countRooms(t.getId())))
                .toList();
    }

    public RoomType require(Long id) {
        RoomType t = roomTypeMapper.selectById(id);
        if (t == null) {
            throw new BizException(ErrorCode.ROOM_TYPE_NOT_FOUND, "房型不存在: " + id);
        }
        return t;
    }

    @Transactional
    public RoomType save(RoomTypeReq req) {
        propertyService.require(req.propertyId());
        RoomType existing = req.id() == null ? null : require(req.id());
        if (existing != null && !existing.getPropertyId().equals(req.propertyId())) {
            // 改挂到别的民宿会留下"房间属于 A 店、房型属于 B 店"的错配。
            // 按这个体量，直接禁止跨店迁移就够，不值得做级联搬家。
            long rooms = countRooms(existing.getId());
            if (rooms > 0) {
                throw new BizException(ErrorCode.HAS_CHILDREN,
                        "该房型下仍有 " + rooms + " 间房，不能改挂到其它民宿");
            }
        }
        RoomType t = new RoomType();
        t.setId(req.id());
        t.setPropertyId(req.propertyId());
        t.setName(req.name().trim());
        t.setBasePrice(req.basePrice());
        t.setMinHours(req.minHours());
        t.setExtraHourPrice(req.extraHourPrice());
        // 粒度是 1 分钟以下的值没有意义，且会放大切片次数，所以下限兜到 1、缺省兜到 60
        t.setChargeUnitMinutes(req.chargeUnitMinutes() == null ? 60 : Math.max(1, req.chargeUnitMinutes()));
        t.setCleanBeforeMinutes(req.cleanBeforeMinutes());
        t.setCleanAfterMinutes(req.cleanAfterMinutes());
        t.setFullDayStart(req.fullDayStart());
        t.setFullDayEnd(req.fullDayEnd());
        if (req.id() == null) {
            roomTypeMapper.insert(t);
        } else {
            require(req.id());
            roomTypeMapper.updateById(t);
        }
        // 改价必须留痕：这一列直接决定账单金额，而账单是钱的凭证。
        // 之前只记了订单与房间状态，"谁把 212 改成了 188"这种追问是答不出来的
        opLog.record(existing == null ? "ROOM_TYPE_CREATE" : "ROOM_TYPE_UPDATE", "room_type", t.getId(),
                priceSummary(existing, t), existing, t);
        return t;
    }

    /** 日志摘要只说变了的钱，不把整行字段列一遍 —— 列表页里要一眼看得出改了什么。 */
    private static String priceSummary(RoomType before, RoomType after) {
        if (before == null) {
            return String.format("%s｜基础价 %s、起步 %dh、超时 %s/h、粒度 %d′",
                    after.getName(), after.getBasePrice(), after.getMinHours(),
                    after.getExtraHourPrice(), after.getChargeUnitMinutes());
        }
        List<String> changes = new ArrayList<>();
        if (before.getBasePrice().compareTo(after.getBasePrice()) != 0) {
            changes.add("基础价 " + before.getBasePrice() + "→" + after.getBasePrice());
        }
        if (before.getExtraHourPrice().compareTo(after.getExtraHourPrice()) != 0) {
            changes.add("超时 " + before.getExtraHourPrice() + "→" + after.getExtraHourPrice());
        }
        if (!before.getMinHours().equals(after.getMinHours())) {
            changes.add("起步 " + before.getMinHours() + "h→" + after.getMinHours() + "h");
        }
        if (!before.getChargeUnitMinutes().equals(after.getChargeUnitMinutes())) {
            changes.add("粒度 " + before.getChargeUnitMinutes() + "′→" + after.getChargeUnitMinutes() + "′");
        }
        return after.getName() + "｜" + (changes.isEmpty() ? "价格未变" : String.join("；", changes));
    }

    @Transactional
    public void delete(Long id) {
        require(id);
        long rooms = countRooms(id);
        if (rooms > 0) {
            throw new BizException(ErrorCode.HAS_CHILDREN, "该房型下仍有 " + rooms + " 间房，请先调整房间的房型");
        }
        roomTypeMapper.deleteById(id);
    }

    private long countRooms(Long roomTypeId) {
        Long n = roomMapper.selectCount(new LambdaQueryWrapper<Room>().eq(Room::getRoomTypeId, roomTypeId));
        return n == null ? 0 : n;
    }
}
