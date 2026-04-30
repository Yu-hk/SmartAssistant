package com.example.smartassistant.consumer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.smartassistant.consumer.entity.UserPreferenceVector;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户偏好向量 Mapper
 */
@Mapper
public interface UserPreferenceVectorMapper extends BaseMapper<UserPreferenceVector> {

    /**
     * 根据用户ID和向量类型查询
     */
    UserPreferenceVector findByUserIdAndType(@Param("userId") Long userId, @Param("vectorType") String vectorType);

    /**
     * 根据用户ID查询所有向量
     */
    List<UserPreferenceVector> findByUserId(@Param("userId") Long userId);

    /**
     * 根据 embeddingId 查询
     */
    UserPreferenceVector findByEmbeddingId(@Param("embeddingId") String embeddingId);

    /**
     * 删除用户的指定类型向量
     */
    int deleteByUserIdAndType(@Param("userId") Long userId, @Param("vectorType") String vectorType);

    /**
     * 删除用户的所有向量
     */
    int deleteByUserId(@Param("userId") Long userId);
}
