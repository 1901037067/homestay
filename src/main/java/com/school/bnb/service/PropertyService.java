package com.school.bnb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.school.bnb.common.BizException;
import com.school.bnb.common.ErrorCode;
import com.school.bnb.dto.PropertyReq;
import com.school.bnb.entity.Property;
import com.school.bnb.entity.Room;
import com.school.bnb.entity.RoomType;
import com.school.bnb.mapper.PropertyMapper;
import com.school.bnb.mapper.RoomMapper;
import com.school.bnb.mapper.RoomTypeMapper;
import com.school.bnb.vo.PropertyVO;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PropertyService {

    private final PropertyMapper propertyMapper;
    private final RoomTypeMapper roomTypeMapper;
    private final RoomMapper roomMapper;

    public PropertyService(PropertyMapper propertyMapper, RoomTypeMapper roomTypeMapper, RoomMapper roomMapper) {
        this.propertyMapper = propertyMapper;
        this.roomTypeMapper = roomTypeMapper;
        this.roomMapper = roomMapper;
    }

    public List<PropertyVO> list() {
        return propertyMapper.selectList(new LambdaQueryWrapper<Property>().orderByAsc(Property::getId))
                .stream()
                .map(p -> new PropertyVO(
                        p.getId(), p.getName(), p.getAddress(), p.getDayCutTime(),
                        p.getOpenTime(), p.getCloseTime(),
                        countRoomTypes(p.getId()), countRooms(p.getId())))
                .toList();
    }

    public Property require(Long id) {
        Property p = propertyMapper.selectById(id);
        if (p == null) {
            throw new BizException(ErrorCode.PROPERTY_NOT_FOUND, "民宿不存在: " + id);
        }
        return p;
    }

    /** 给列表页批量取名字用，避免逐行查库。 */
    public Map<Long, String> nameMap() {
        return propertyMapper.selectList(null).stream()
                .collect(Collectors.toMap(Property::getId, Property::getName));
    }

    @Transactional
    public Property save(PropertyReq req) {
        Property p = new Property();
        p.setId(req.id());
        p.setName(req.name().trim());
        p.setAddress(req.address());
        p.setDayCutTime(orDefault(req.dayCutTime(), LocalTime.of(6, 0)));
        p.setOpenTime(orDefault(req.openTime(), LocalTime.of(8, 0)));
        p.setCloseTime(orDefault(req.closeTime(), LocalTime.of(22, 0)));
        if (req.id() == null) {
            propertyMapper.insert(p);
        } else {
            require(req.id());
            propertyMapper.updateById(p);
        }
        return p;
    }

    /** 实施计划 2.6：有下级数据就不许物理删除。 */
    @Transactional
    public void delete(Long id) {
        require(id);
        if (countRoomTypes(id) > 0 || countRooms(id) > 0) {
            throw new BizException(ErrorCode.HAS_CHILDREN,
                    "该民宿下仍有房型或房间，请先删除它们；或只清空地址后保留记录");
        }
        propertyMapper.deleteById(id);
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

    private static LocalTime orDefault(LocalTime v, LocalTime fallback) {
        return v == null ? fallback : v;
    }
}
