package com.school.bnb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.school.bnb.common.BizException;
import com.school.bnb.common.ErrorCode;
import com.school.bnb.entity.Property;
import com.school.bnb.entity.Room;
import com.school.bnb.entity.RoomType;
import com.school.bnb.mapper.OccupancyMapper;
import com.school.bnb.mapper.PropertyMapper;
import com.school.bnb.mapper.RoomMapper;
import com.school.bnb.mapper.RoomTypeMapper;
import com.school.bnb.vo.CalendarBlock;
import com.school.bnb.vo.CalendarRoomVO;
import com.school.bnb.vo.CalendarVO;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 房态日历数据源（实施计划 W4 + 7.2）。
 *
 * <p>核心决定：<b>窗口按营业日而不是自然日</b>。切割点 06:00 的店，日历窗口是
 * [10-28 06:00, 10-29 06:00)，横轴从 06 开始转到次日 05。这样凌晨 2 点的钟点单
 * 会画在"它所属的那个营业日"里，和入住率报表口径一致；用自然日切就会出现
 * "日历上看得见、报表算到明天"的对不上。
 *
 * <p>一次查全部占用块再分组，不按房间逐个查 —— 18 间房就是 18 次查询。
 */
@Service
public class CalendarService {

    private final PropertyMapper propertyMapper;
    private final RoomMapper roomMapper;
    private final RoomTypeMapper roomTypeMapper;
    private final OccupancyMapper occupancyMapper;

    public CalendarService(PropertyMapper propertyMapper, RoomMapper roomMapper,
                           RoomTypeMapper roomTypeMapper, OccupancyMapper occupancyMapper) {
        this.propertyMapper = propertyMapper;
        this.roomMapper = roomMapper;
        this.roomTypeMapper = roomTypeMapper;
        this.occupancyMapper = occupancyMapper;
    }

    public CalendarVO calendar(Long propertyId, LocalDate date) {
        Property property = propertyMapper.selectById(propertyId);
        if (property == null) {
            throw new BizException(ErrorCode.PROPERTY_NOT_FOUND, "民宿不存在: " + propertyId);
        }
        LocalDate day = date == null ? LocalDate.now() : date;
        LocalDateTime windowStart = day.atTime(property.getDayCutTime());
        LocalDateTime windowEnd = windowStart.plusDays(1);

        List<Room> rooms = roomMapper.selectList(new LambdaQueryWrapper<Room>()
                .eq(Room::getPropertyId, propertyId)
                .orderByAsc(Room::getRoomNo));

        Map<Long, RoomType> types = rooms.isEmpty() ? Map.of()
                : roomTypeMapper.selectBatchIds(rooms.stream().map(Room::getRoomTypeId).collect(Collectors.toSet()))
                        .stream().collect(Collectors.toMap(RoomType::getId, t -> t));

        Map<Long, List<CalendarBlock>> byRoom = occupancyMapper
                .findCalendarBlocks(windowStart, windowEnd, propertyId).stream()
                .collect(Collectors.groupingBy(CalendarBlock::getRoomId));

        List<CalendarRoomVO> rows = rooms.stream()
                .map(r -> {
                    RoomType t = types.get(r.getRoomTypeId());
                    return new CalendarRoomVO(r.getId(), r.getRoomNo(), r.getRoomTypeId(),
                            t == null ? null : t.getName(), r.getStatus(),
                            t == null ? null : t.getFullDayStart(),
                            t == null ? null : t.getFullDayEnd(),
                            t == null ? null : t.getMinHours(),
                            t == null ? null : t.getCleanBeforeMinutes(),
                            t == null ? null : t.getCleanAfterMinutes(),
                            byRoom.getOrDefault(r.getId(), List.of()));
                })
                .toList();

        return new CalendarVO(day, property.getDayCutTime(), windowStart, windowEnd,
                property.getId(), property.getName(), property.getOpenTime(), property.getCloseTime(), rows);
    }
}
