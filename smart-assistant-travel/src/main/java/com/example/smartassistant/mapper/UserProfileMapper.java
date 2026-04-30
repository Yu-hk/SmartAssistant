package com.example.smartassistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.smartassistant.entity.UserProfile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 用户画像 Mapper (MyBatis Plus)
 */
@Mapper
public interface UserProfileMapper extends BaseMapper<UserProfile> {

    /**
     * 根据用户ID查询
     */
    UserProfile findByUserId(@Param("userId") String userId);

    /**
     * 检查用户是否存在
     */
    boolean existsByUserId(@Param("userId") String userId);
}
