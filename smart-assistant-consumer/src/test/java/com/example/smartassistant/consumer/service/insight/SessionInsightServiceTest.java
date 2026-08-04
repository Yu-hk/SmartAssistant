/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.service.insight;

import com.example.smartassistant.common.rag.KnowledgeBase;
import com.example.smartassistant.common.rag.KnowledgeDocument;
import com.example.smartassistant.common.rag.KnowledgeHit;
import com.example.smartassistant.consumer.entity.UserProfile;
import com.example.smartassistant.common.memory.AgentMemoryService;
import com.example.smartassistant.common.memory.EntityProfileService;
import com.example.smartassistant.consumer.service.recommendation.UserProfileService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 集成式单元测试（H2 内存库 + Mockito），覆盖本次改动的核心链路：
 * <ul>
 *   <li>P1-C 工单生命周期：创建 → 状态流转 → 关闭 → 列表 + 状态机非法校验</li>
 *   <li>P2-B 知识库检索：RAG 向量优先 + SQL LIKE 兜底</li>
 *   <li>P0 客户 360° 聚合：多源合并</li>
 *   <li>P2-A 情绪分析回流：analyzeEmotion → recordEmotion → UserProfileService</li>
 * </ul>
 */
class SessionInsightServiceTest {

    private SessionInsightService svc;
    private JdbcTemplate jdbc;
    private UserProfileService ups;
    private EntityProfileService eps;
    private AgentMemoryService ams;
    private KnowledgeBase kb;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:insight_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        ds.setUser("sa");
        DataSource dataSource = ds;
        jdbc = new JdbcTemplate(dataSource);

        ups = mock(UserProfileService.class);
        eps = mock(EntityProfileService.class);
        ams = mock(AgentMemoryService.class);
        kb = mock(KnowledgeBase.class);
        svc = new SessionInsightService(jdbc, ups, eps, ams, kb);
    }

    // ===================================================
    // P1-C 工单生命周期
    // ===================================================
    @Test
    void ticketLifecycle_createUpdateCloseAndList() {
        SessionInsightService.TicketView created =
                svc.createTicket(42L, "sess-1", "refund", "用户要求退款", "张三");
        assertNotNull(created.id());
        assertEquals("OPEN", created.status());
        assertNull(created.resolution());
        assertNull(created.closedAt());

        // 正常流转
        assertEquals("IN_PROGRESS", svc.updateTicketStatus(created.id(), 42L, false, "IN_PROGRESS").status());
        assertEquals("RESOLVED", svc.updateTicketStatus(created.id(), 42L, false, "RESOLVED").status());

        // 关闭（终态，附结论）
        SessionInsightService.TicketView closed = svc.closeTicket(created.id(), 42L, false, "已原路退款");
        assertEquals("CLOSED", closed.status());
        assertEquals("已原路退款", closed.resolution());
        assertNotNull(closed.closedAt());

        // 状态机守卫：CLOSED 只能经 closeTicket 进入
        assertThrows(IllegalArgumentException.class,
                () -> svc.updateTicketStatus(created.id(), 42L, false, "CLOSED"));
        // 非法状态
        assertThrows(IllegalArgumentException.class,
                () -> svc.updateTicketStatus(created.id(), 42L, false, "FOO"));
        // 空 ticketId
        assertThrows(IllegalArgumentException.class,
                () -> svc.updateTicketStatus("", 42L, false, "OPEN"));
        // 不存在的工单
        assertThrows(RuntimeException.class,
                () -> svc.updateTicketStatus("nope", 42L, false, "IN_PROGRESS"));

        assertThrows(RuntimeException.class,
                () -> svc.updateTicketStatus(created.id(), 99L, false, "PENDING"));

        // 列表查询
        List<SessionInsightService.TicketView> list = svc.listTickets(42L, false, "sess-1", null);
        assertEquals(1, list.size());
        assertEquals(created.id(), list.get(0).id());
        assertTrue(svc.listTickets(99L, false, "sess-1", null).isEmpty());
    }

    // ===================================================
    // P2-B 知识库检索：RAG 优先 + SQL 兜底
    // ===================================================
    @Test
    void searchKb_ragPriorityOverSql() {
        KnowledgeDocument doc = mock(KnowledgeDocument.class);
        when(doc.getTitle()).thenReturn("RAG 退款政策");
        when(doc.getCategory()).thenReturn("refund");
        when(kb.searchWithParentExpansion(anyString(), anyInt()))
                .thenReturn(List.of(new KnowledgeHit(doc, 0.88)));

        List<SessionInsightService.KbHit> hits = svc.searchKb("退款", null);
        assertEquals(1, hits.size());
        assertEquals("RAG 退款政策", hits.get(0).title());
        assertEquals(88, hits.get(0).match()); // round(0.88*100)
    }

    @Test
    void searchKb_sqlFallbackWhenRagEmpty() {
        // RAG 未播种（返回空）→ 降级到 faq 表 SQL
        when(kb.searchWithParentExpansion(anyString(), anyInt())).thenReturn(List.of());
        jdbc.execute("CREATE TABLE IF NOT EXISTS faq (" +
                "question VARCHAR(255), keywords VARCHAR(255), hit_count INT, category VARCHAR(64))");
        jdbc.update("INSERT INTO faq (question, keywords, hit_count, category) VALUES (?,?,?,?)",
                "如何申请退款", "退款 退货", 12, "refund");

        List<SessionInsightService.KbHit> hits = svc.searchKb("退款", null);
        assertEquals(1, hits.size());
        assertEquals("如何申请退款", hits.get(0).title());
        assertEquals("知识库", hits.get(0).source());
    }

    @Test
    void searchKb_ragExceptionFallsBackToSql() {
        // RAG 抛异常也应降级，不崩溃
        when(kb.searchWithParentExpansion(anyString(), anyInt()))
                .thenThrow(new RuntimeException("embedding 服务不可用"));
        jdbc.execute("CREATE TABLE IF NOT EXISTS faq (" +
                "question VARCHAR(255), keywords VARCHAR(255), hit_count INT, category VARCHAR(64))");
        jdbc.update("INSERT INTO faq (question, keywords, hit_count, category) VALUES (?,?,?,?)",
                "发票怎么开", "发票", 5, "order");

        List<SessionInsightService.KbHit> hits = svc.searchKb("发票", null);
        assertEquals(1, hits.size());
        assertEquals("发票怎么开", hits.get(0).title());
    }

    // ===================================================
    // P0 客户 360° 聚合
    // ===================================================
    @Test
    void getCustomerProfile_aggregatesAllSources() {
        UserProfile p = new UserProfile();
        p.setUserId(1L);
        p.setId(1L);
        p.setTotalQueries(7);
        p.setIntentDistribution(Map.of("refund", 2, "order", 3, "tech", 1));
        p.setFoodPreferencesArray(new String[]{"清淡"});
        p.setTravelPreferencesArray(new String[]{"自然风光"});
        p.setBudgetRange("经济实惠");
        p.setDietaryRestrictionsArray(new String[]{"素食"});
        // P2-A 持久化情绪聚合
        p.setLastEmotionLabel("略急");
        p.setLastEmotionScore(40);
        p.setNegativeTouchCount(2);
        p.setPositiveTouchCount(1);
        p.setEmotionAvgScore(45.0);
        // ⭐ P2-C 隐藏关键信息
        p.setKeyInsightsArray(new String[]{"经常出差", "企业采购(B2B)"});

        when(ups.getProfile(1L)).thenReturn(p);
        // entityFacts 必须非空，否则聚合中 containsKey 会 NPE
        when(eps.getAll(1L)).thenReturn(Map.of("name", "李四"));
        when(ams.getAllFormatted("1", "order")).thenReturn("订单备注内容");
        when(ams.getAllFormatted("1", "product")).thenReturn(null);
        when(ams.getAllFormatted("1", "general")).thenReturn(null);

        CustomerProfileVO vo = svc.getCustomerProfile(1L, null);
        assertEquals("李四", vo.userName());              // 来自 EntityProfile
        assertEquals(7, vo.totalQueries());
        assertEquals(2, vo.intentDistribution().get("refund"));
        assertEquals(List.of("清淡"), vo.foodPreferences());
        assertEquals("经济实惠", vo.budgetRange());
        assertEquals(0, vo.escalationCount());            // complaint(0) + tech(1)/3 = 0
        assertEquals(2, vo.complaintCount());             // refund(2) + complaint(0)
        // P2-A 情绪聚合
        assertEquals("略急", vo.lastEmotionLabel());
        assertEquals(40, vo.lastEmotionScore());
        assertEquals(2, vo.negativeTouchCount());
        assertEquals(1, vo.positiveTouchCount());
        assertEquals(45.0, vo.emotionAvgScore(), 0.001);
        // ⭐ P2-C 隐藏关键信息聚合
        assertTrue(vo.keyInsights().contains("经常出差"), "应聚合隐藏关键信息, 实际=" + vo.keyInsights());
        assertTrue(vo.keyInsights().contains("企业采购(B2B)"));
        // AgentMemory 摘要
        assertTrue(vo.agentMemorySummaries().stream().anyMatch(s -> s.contains("订单备注内容")));
    }

    @Test
    void getCustomerProfile_anonymousReturnsVisitor() {
        CustomerProfileVO vo = svc.getCustomerProfile(null, null);
        assertEquals("访客", vo.userName());
        assertEquals(0, vo.totalQueries());
    }

    // ===================================================
    // P2-A 情绪分析回流链路
    // ===================================================
    @Test
    void analyzeEmotion_recordsEmotionToUserProfile() {
        svc.analyzeEmotion("我要退款，非常生气", 5L, "退款");
        verify(ups, atLeastOnce()).recordEmotion(eq(5L), anyString(), anyInt());
    }

    @Test
    void analyzeEmotion_emptyTextReturnsCalm() {
        SessionInsightService.EmotionResult r = svc.analyzeEmotion("", 5L, null);
        assertEquals("平静", r.label());
        assertEquals(50, r.score());
    }

    @Test
    void analyzeEmotion_negativeTextLowersScore() {
        SessionInsightService.EmotionResult r = svc.analyzeEmotion("你们是诈骗，我要投诉，退款太慢了", 5L, "退款");
        assertTrue(r.score() < 50, "负面关键词应拉低分数，实际=" + r.score());
        assertEquals("急切 / 不满", r.label());
    }
}
