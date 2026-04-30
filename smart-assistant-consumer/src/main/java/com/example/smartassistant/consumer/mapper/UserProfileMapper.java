package com.example.smartassistant.consumer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.smartassistant.consumer.entity.UserProfile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 用户画像 Mapper (MyBatis Plus)
 */
@Mapper
public interface UserProfileMapper extends BaseMapper<UserProfile> {
    
    /**
     * 根据用户 ID 查找画像
     */
    UserProfile findByUserId(@Param("userId") Long userId);
    
    /**
     * 检查用户画像是否存在
     */
    boolean existsByUserId(@Param("userId") Long userId);
    
    /**
     * 自定义插入 - 处理 PostgreSQL text[] 类型
     */
    int insertProfile(UserProfile profile);
    
    /**
     * 自定义更新 - 处理 PostgreSQL text[] 类型
     */
    int updateProfile(UserProfile profile);
}
