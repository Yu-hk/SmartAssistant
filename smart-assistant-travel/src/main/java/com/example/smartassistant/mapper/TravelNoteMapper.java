package com.example.smartassistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.smartassistant.entity.TravelNote;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户游记 Mapper
 */
@Mapper
public interface TravelNoteMapper extends BaseMapper<TravelNote> {

    /**
     * 根据用户 ID 查询游记列表
     */
    List<TravelNote> selectByUserId(@Param("userId") Long userId);

    /**
     * 根据地点关键词搜索游记（与用户无关，所有用户共享）
     */
    List<TravelNote> selectByLocationKeywords(@Param("location") String location, @Param("userId") Long userId);

    /**
     * 根据地点搜索游记（无用户限制，所有游记共享）
     */
    List<TravelNote> selectByLocation(@Param("location") String location);

    /**
     * 查询所有活跃游记（Admin 用）
     */
    List<TravelNote> selectAllActive();
}
