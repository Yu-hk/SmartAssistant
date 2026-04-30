package com.example.smartassistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.smartassistant.entity.TouristAttraction;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 旅游景点 Mapper (MyBatis Plus)
 */
@Mapper
public interface TouristAttractionMapper extends BaseMapper<TouristAttraction> {

    /**
     * 根据城市查询景点
     */
    List<TouristAttraction> findByCity(@Param("city") String city);

    /**
     * 根据省份查询景点
     */
    List<TouristAttraction> findByProvince(@Param("province") String province);

    /**
     * 根据等级查询景点
     */
    List<TouristAttraction> findByLevel(@Param("level") String level);

    /**
     * 根据名称模糊查询
     */
    List<TouristAttraction> findByNameContaining(@Param("name") String name);

    /**
     * 检查景点是否存在（按名称和城市）
     */
    TouristAttraction findByNameAndCity(@Param("name") String name, @Param("city") String city);

    /**
     * 统计每个城市的景点数量
     */
    List<java.util.Map<String, Object>> countByCity();

    /**
     * 统计每个省份的景点数量
     */
    List<java.util.Map<String, Object>> countByProvince();

    /**
     * 查询所有不同的城市
     */
    List<String> findAllCities();

    /**
     * 查询所有不同的省份
     */
    List<String> findAllProvinces();

    /**
     * 根据标签查询景点
     */
    List<TouristAttraction> findByTagsIn(@Param("tags") List<String> tags);
}
