package com.example.smartassistant.consumer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.smartassistant.consumer.entity.UserConversationDoc;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户对话文档 Mapper
 */
@Mapper
public interface UserConversationDocMapper extends BaseMapper<UserConversationDoc> {

    /**
     * 向量相似度搜索
     * @param userId 用户ID
     * @param embedding 查询向量
     * @param topK 返回条数
     */
    List<UserConversationDoc> searchSimilar(@Param("userId") Long userId,
                                             @Param("embedding") float[] embedding,
                                             @Param("topK") int topK);

    /**
     * 查询用户的所有对话文档
     */
    List<UserConversationDoc> findByUserId(@Param("userId") Long userId);
}
