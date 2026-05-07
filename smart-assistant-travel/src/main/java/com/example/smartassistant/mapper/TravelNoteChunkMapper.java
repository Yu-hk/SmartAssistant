package com.example.smartassistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.smartassistant.entity.TravelNoteChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 攻略分块 Mapper
 */
@Mapper
public interface TravelNoteChunkMapper extends BaseMapper<TravelNoteChunk> {

    /**
     * 根据游记 ID 删除所有分块
     */
    void deleteByNoteId(@Param("noteId") Long noteId);

    /**
     * 批量插入分块
     */
    void batchInsert(@Param("chunks") List<TravelNoteChunk> chunks);

    /**
     * 单条插入分块（内存优化版）
     */
    void insertOne(@Param("chunk") TravelNoteChunk chunk);

    /**
     * 向量相似度检索
     * @param embedding 查询向量
     * @param locationKeyword 地点关键词
     * @param userId 用户 ID
     * @param limit 返回数量
     * @return 分块列表（含相似度）
     */
    List<TravelNoteChunk> searchByEmbedding(
            @Param("embedding") float[] embedding,
            @Param("locationKeyword") String locationKeyword,
            @Param("userId") Long userId,
            @Param("limit") int limit);

    /**
     * 根据地点关键词检索（不使用向量）
     */
    List<TravelNoteChunk> searchByLocation(
            @Param("locationKeyword") String locationKeyword,
            @Param("userId") Long userId,
            @Param("limit") int limit);

    /**
     * ⭐ 按内容类型检索攻略片段（纯文本查询，无需向量）
     * @param locationKeyword 地点关键词
     * @param userId 用户 ID
     * @param contentType 内容类型（scenic/food/accommodation/transport/general）
     * @param limit 返回数量
     */
    List<TravelNoteChunk> searchByLocationAndType(
            @Param("locationKeyword") String locationKeyword,
            @Param("userId") Long userId,
            @Param("contentType") String contentType,
            @Param("limit") int limit);

    /**
     * ⭐ 根据 note_id 列表随机检索 chunk（用于被筛游记的补充建议）
     * @param noteIds 游记 ID 列表
     * @param limit 返回数量
     */
    List<TravelNoteChunk> searchRandomByNoteIds(
            @Param("noteIds") List<Long> noteIds,
            @Param("limit") int limit);
}
