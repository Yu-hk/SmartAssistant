package com.example.smartassistant.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.smartassistant.user.model.UserExternalIdentity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserExternalIdentityMapper extends BaseMapper<UserExternalIdentity> {
    @Select("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext(#{identityKey}))) AS identity_lock")
    Long lockIdentity(@Param("identityKey") String identityKey);

    @Select("SELECT * FROM user_external_identities WHERE provider = #{provider} AND subject = #{subject} LIMIT 1")
    UserExternalIdentity find(@Param("provider") String provider, @Param("subject") String subject);
}
