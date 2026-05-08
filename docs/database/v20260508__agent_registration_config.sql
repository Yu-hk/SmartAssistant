-- ============================================================
-- Agent 注册配置表
-- 用于动态管理各服务的 Nacos 注册元数据（keywords/priority等）
-- 替代 hardcoded 在 application.yml 中的 metadata
-- ============================================================

CREATE TABLE IF NOT EXISTS agent_registration_config (
    id BIGSERIAL PRIMARY KEY,
    service_name VARCHAR(64) NOT NULL UNIQUE,   -- 服务名，如 food-service
    agent_type VARCHAR(32),                     -- agent 类型，如 food_recommendation
    keywords TEXT,                              -- 路由关键词（逗号分隔）
    priority INTEGER DEFAULT 5,                 -- 路由优先级
    capabilities TEXT,                          -- 能力描述
    metadata JSONB,                             -- 扩展元数据（JSON）
    enabled BOOLEAN DEFAULT TRUE,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 初始数据
INSERT INTO agent_registration_config (service_name, agent_type, keywords, priority, capabilities) VALUES
('food-service', 'food_recommendation', '美食,餐厅,菜系,特色菜,吃什么,附近美食,推荐餐厅,川菜,粤菜,火锅', 10, 'cuisine_query,restaurant_recommendation,nearby_search'),
('travel-service', 'travel_chat', '旅游,景点,攻略,路线,出行,旅行,天气,景区,门票,交通,住宿,酒店,目的地,行程', 10, 'travel_planning,attraction_query,weather_check,route_planning'),
('general-service', 'general_chat', '', 100, 'chat,qa,entertainment,companion');
