package com.school.bnb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.school.bnb.entity.RoomType;
import com.school.bnb.vo.PropertyCount;
import java.util.List;
import org.apache.ibatis.annotations.Select;

public interface RoomTypeMapper extends BaseMapper<RoomType> {

    /** 一次取全表按民宿聚合的房型数，给 PropertyService.list 收 N+1（原先每家店各 count 一次）。 */
    @Select("select property_id, count(1) as total from room_type group by property_id")
    List<PropertyCount> countByProperty();
}
