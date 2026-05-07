package com.example.smartassistant.router.service;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.Event;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.example.smartassistant.router.config.AgentDiscoveryConfig;
import com.example.smartassistant.router.model.AgentMetadata;
import com.example.smartassistant.router.model.DiscoveredAgent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class AgentDiscoveryService {
    
    private static final Logger log = LoggerFactory.getLogger(AgentDiscoveryService.class);
    private static final String SSE_URLS_REDIS_KEY = "a2a:agent:sse:urls";
    private static final long SSE_URLS_TTL_SECONDS = 300;
    
    private final NamingService namingService;
    private final AgentDiscoveryConfig discoveryConfig;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    
    private final Map<String, DiscoveredAgent> agentCache = new ConcurrentHashMap<>();
    private final Set<String> subscribedServices = ConcurrentHashMap.newKeySet();
    
    // ⭐ 预计算的降级 Agent，每次 agentCache 变化时自动刷新
    private volatile DiscoveredAgent fallbackAgent;
    
    public AgentDiscoveryService(NamingService namingService, AgentDiscoveryConfig discoveryConfig,
                                  @Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.namingService = namingService;
        this.discoveryConfig = discoveryConfig;
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * ⭐ 初始化时订阅 Nacos 服务变化事件
     */
    @PostConstruct
    public void init() {
        if (!discoveryConfig.isEnabled()) {
            log.info("[AgentDiscovery] ⚠️ Agent 自动发现已禁用");
            return;
        }
        
        try {
            List<String> watchedServices = discoveryConfig.getWatchedServices();
            
            if (watchedServices.isEmpty()) {
                log.info("[AgentDiscovery] 🔄 使用动态订阅模式 - 自动订阅所有符合条件的服务");
                subscribeAllMatchingServices();
            } else {
                for (String serviceName : watchedServices) {
                    namingService.subscribe(serviceName, new AgentChangeListener());
                    log.info("[AgentDiscovery] ✅ 已订阅服务: {}", serviceName);
                }
            }
            
            log.info("[AgentDiscovery] ✅ Nacos 服务监听器已启动");
            
            // 初始化时发现所有 Agent 并写入 Redis
            discoverAllAgents();
            saveSseUrlsToRedis();
        } catch (NacosException e) {
            log.error("[AgentDiscovery] ❌ 启动 Nacos 监听器失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * ⭐ 动态订阅所有符合条件的服务
     */
    private void subscribeAllMatchingServices() throws NacosException {
        var servicesResult = namingService.getServicesOfServer(1, 100);
        List<String> allServices = new ArrayList<>(servicesResult.getData());
        
        log.info("[AgentDiscovery] 🔍 扫描到 {} 个服务，开始筛选...", allServices.size());
        
        int subscribedCount = 0;
        for (String serviceName : allServices) {
            if (isAgentService(serviceName) && !isExcludedService(serviceName)) {
                try {
                    subscribedServices.add(serviceName);
                    namingService.subscribe(serviceName, new AgentChangeListener());
                    log.info("[AgentDiscovery] ✅ 自动订阅: {}", serviceName);
                    subscribedCount++;
                } catch (Exception e) {
                    log.warn("[AgentDiscovery] ⚠️ 订阅失败: {}, 错误: {}", serviceName, e.getMessage());
                }
            }
        }
        
        log.info("[AgentDiscovery] 📊 共订阅 {} 个 Agent 服务", subscribedCount);
    }
    
    /**
     * 发现所有已注册的 Agent
     */
    public List<DiscoveredAgent> discoverAllAgents() {
        try {
            // ⭐ 优化: 支持分页获取所有服务
            List<String> allServices = getAllServicesWithPagination();
            
            log.info("[AgentDiscovery] Nacos 返回的服务总数: {}", allServices.size());
            
            List<DiscoveredAgent> discoveredAgents = new ArrayList<>();
            
            for (String serviceName : allServices) {
                if (!isAgentService(serviceName)) {
                    continue;
                }
                
                if (isExcludedService(serviceName)) {
                    continue;
                }
                
                DiscoveredAgent agent = discoverAgent(serviceName);
                if (agent != null) {
                    discoveredAgents.add(agent);
                    agentCache.put(serviceName, agent);
                }
            }
            
            log.info("[AgentDiscovery] 成功发现 {} 个 Agent: {}", 
                    discoveredAgents.size(), 
                    discoveredAgents.stream().map(DiscoveredAgent::getServiceName).toList());
            
            // ⭐ 发现完成后刷新降级 Agent
            refreshFallbackAgent();
            
            return discoveredAgents;
            
        } catch (Exception e) {
            log.error("[AgentDiscovery] 发现 Agent 失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }
    
    /**
     * ⭐ 分页获取所有服务(解决只获取前100个服务的限制)
     */
    private List<String> getAllServicesWithPagination() throws NacosException {
        List<String> allServices = new ArrayList<>();
        int pageNo = 1;
        int pageSize = 100;
        
        while (true) {
            var servicesResult = namingService.getServicesOfServer(pageNo, pageSize);
            List<String> pageServices = new ArrayList<>(servicesResult.getData());
            
            if (pageServices.isEmpty()) {
                break;
            }
            
            allServices.addAll(pageServices);
            
            // 如果返回的服务数小于pageSize,说明已经是最后一页
            if (pageServices.size() < pageSize) {
                break;
            }
            
            pageNo++;
        }
        
        return allServices;
    }
    
    /**
     * 发现单个 Agent
     */
    public DiscoveredAgent discoverAgent(String serviceName) {
        try {
            List<Instance> instances = namingService.selectInstances(serviceName, true);
            
            if (instances.isEmpty()) {
                log.warn("[AgentDiscovery] 服务 {} 没有可用实例", serviceName);
                return null;
            }
            
            Instance instance = instances.get(0);
            
            // 解析元数据
            AgentMetadata metadata = parseMetadata(instance.getMetadata());
            
            // 构建发现结果
            DiscoveredAgent agent = new DiscoveredAgent();
            agent.setServiceName(serviceName);
            agent.setAgentName(metadata.getAgentType() != null ? 
                    metadata.getAgentType() : serviceName);
            agent.setIp(instance.getIp());
            agent.setPort(instance.getPort());
            agent.setUrl("http://" + instance.getIp() + ":" + instance.getPort() + "/a2a");
            agent.setMetadata(metadata);
            agent.setHealthy(instance.isHealthy());
            agent.setWeight(instance.getWeight());
            
            log.info("[AgentDiscovery] 发现 Agent: {}, URL: {}, 类型: {}", 
                    serviceName, agent.getUrl(), metadata.getAgentType());
            
            return agent;
            
        } catch (Exception e) {
            log.error("[AgentDiscovery] 发现 Agent 失败: {}, 错误: {}", serviceName, e.getMessage());
            return null;
        }
    }
    
    /**
     * ⭐ 将 Agent SSE URL 映射存入 Redis（从 agentCache 读取，使用 Nacos 注册的 IP:Port）
     * <p>
     * key: a2a:agent:sse:urls
     * value: {"location_weather": "http://192.168.0.101:8085/travel/stream/chat", ...}
     */
    private void saveSseUrlsToRedis() {
        if (redisTemplate == null) {
            return;
        }
        try {
            Map<String, String> urlMap = new HashMap<>();
            for (DiscoveredAgent agent : agentCache.values()) {
                String agentName = agent.getAgentName();
                if (agentName == null) continue;
                // 使用 Nacos 注册的实际 IP:Port
                String host = "http://" + agent.getIp() + ":" + agent.getPort();
                String serviceName = agent.getServiceName();
                String prefix = serviceName.contains("-") ? serviceName.substring(0, serviceName.indexOf('-')) : serviceName;
                String ssePath = "/" + prefix + "/stream/chat";
                urlMap.put(agentName, host + ssePath);
            }
            if (urlMap.isEmpty()) {
                redisTemplate.delete(SSE_URLS_REDIS_KEY);
                log.info("[AgentDiscovery] 🗑️ Agent SSE URL 映射已清空（无可用 Agent）");
            } else {
                String json = objectMapper.writeValueAsString(urlMap);
                redisTemplate.opsForValue().set(SSE_URLS_REDIS_KEY, json, SSE_URLS_TTL_SECONDS, TimeUnit.SECONDS);
                log.info("[AgentDiscovery] ✅ Agent SSE URL 映射已更新: {}", urlMap);
            }
        } catch (JsonProcessingException e) {
            log.warn("[AgentDiscovery] ⚠️ SSE URL 序列化失败: {}", e.getMessage());
        }
    }
    
    /**
     * ⭐ 查找用作降级的 Agent（优先级最高的 agent，作为最后兜底）
     * <p>
     * 遍历所有已发现 Agent，选择 priority 值最大的作为降级 Agent。
     * 当前 General Agent 的 priority=100，远高于 Food/Travel 的 priority=10。
     */
    public DiscoveredAgent findFallbackAgent() {
        return fallbackAgent;
    }

    /**
     * ⭐ 刷新降级 Agent：遍历缓存，选择 priority 最大的 Agent
     * <p>
     * 每次 agentCache 变化后自动调用，确保降级 Agent 始终正确。
     */
    private void refreshFallbackAgent() {
        DiscoveredAgent candidate = null;
        int maxPriority = Integer.MIN_VALUE;

        for (DiscoveredAgent agent : agentCache.values()) {
            int priority = agent.getMetadata() != null ? agent.getMetadata().getPriority() : 0;
            if (priority > maxPriority) {
                maxPriority = priority;
                candidate = agent;
            }
        }

        this.fallbackAgent = candidate;
        log.info("[AgentDiscovery] 降级 Agent 已刷新: {} (优先级: {})",
                fallbackAgent != null ? fallbackAgent.getServiceName() : "none",
                maxPriority == Integer.MIN_VALUE ? 0 : maxPriority);
    }

    /**
     * 根据用户问题匹配最合适的 Agent
     */
    public DiscoveredAgent matchAgent(String question) {
        if (question == null || question.isEmpty()) {
            return null;
        }
        
        Collection<DiscoveredAgent> agents = getCachedAgents();
        if (agents.isEmpty()) {
            log.warn("[AgentDiscovery] 没有可用的 Agent");
            return null;
        }
        
        DiscoveredAgent bestMatch = null;
        double highestScore = 0.0;
        
        for (DiscoveredAgent agent : agents) {
            double score = calculateMatchScore(agent, question);
            log.debug("[AgentDiscovery] Agent {} 匹配分数: {}", agent.getServiceName(), score);
            
            if (score > highestScore) {
                highestScore = score;
                bestMatch = agent;
            }
        }
        
        if (bestMatch != null) {
            log.info("[AgentDiscovery] 最佳匹配: {}, 分数: {}", 
                    bestMatch.getServiceName(), highestScore);
        }
        
        return bestMatch;
    }
    
    /**
     * 计算 Agent 与问题的匹配分数
     */
    private double calculateMatchScore(DiscoveredAgent agent, String question) {
        AgentMetadata metadata = agent.getMetadata();
        if (metadata == null) {
            return 0.0;
        }
        
        double score = 0.0;
        String lowerQuestion = question.toLowerCase();
        
        // 1. 关键词匹配（权重 0.6）
        String[] keywords = metadata.getKeywordsArray();
        int keywordMatches = 0;
        for (String keyword : keywords) {
            if (lowerQuestion.contains(keyword.trim())) {
                keywordMatches++;
            }
        }
        if (keywords.length > 0) {
            score += 0.6 * ((double) keywordMatches / keywords.length);
        }
        
        // 2. 能力匹配（权重 0.3）
        String[] capabilities = metadata.getCapabilitiesArray();
        int capabilityMatches = 0;
        for (String capability : capabilities) {
            if (lowerQuestion.contains(capability.trim())) {
                capabilityMatches++;
            }
        }
        if (capabilities.length > 0) {
            score += 0.3 * ((double) capabilityMatches / capabilities.length);
        }
        
        // 3. 优先级加成（权重 0.1）
        score += 0.1 * (metadata.getPriority() / 10.0);
        
        return score;
    }
    
    /**
     * 解析 Nacos 元数据
     */
    private AgentMetadata parseMetadata(Map<String, String> nacosMetadata) {
        AgentMetadata metadata = new AgentMetadata();
        
        if (nacosMetadata == null) {
            return metadata;
        }
        
        metadata.setAgentType(nacosMetadata.get("agent-type"));
        metadata.setCapabilities(nacosMetadata.get("capabilities"));
        metadata.setKeywords(nacosMetadata.get("keywords"));
        metadata.setCuisineTypes(nacosMetadata.get("cuisine-types"));
        metadata.setSupportLocation(Boolean.parseBoolean(nacosMetadata.get("support-location")));
        metadata.setSupportWeather(Boolean.parseBoolean(nacosMetadata.get("support-weather")));
        metadata.setSupportPlanning(Boolean.parseBoolean(nacosMetadata.get("support-planning")));
        
        String priorityStr = nacosMetadata.get("priority");
        if (priorityStr != null) {
            try {
                metadata.setPriority(Integer.parseInt(priorityStr));
            } catch (NumberFormatException e) {
                metadata.setPriority(0);
            }
        }
        
        metadata.setVersion(nacosMetadata.getOrDefault("version", "1.0.0"));
        metadata.setProtocolVersion(nacosMetadata.getOrDefault("protocol-version", "v1"));
        metadata.setMinClientVersion(nacosMetadata.getOrDefault("min-client-version", "1.0.0"));
        metadata.setSupportedProtocols(nacosMetadata.getOrDefault("supported-protocols", "a2a-v1"));
        
        return metadata;
    }
    
    /**
     * 获取缓存的 Agent 列表
     */
    public Collection<DiscoveredAgent> getCachedAgents() {
        return agentCache.values();
    }

    /**
     * ⭐ 更新 Agent 心跳时间
     * @param serviceName Agent 服务名
     * @return true 如果找到并更新成功
     */
    public boolean updateHeartbeat(String serviceName) {
        DiscoveredAgent agent = agentCache.get(serviceName);
        if (agent != null) {
            agent.setLastHeartbeatAt(System.currentTimeMillis());
            log.debug("[AgentDiscovery] ✅ 更新心跳: serviceName={}", serviceName);
            return true;
        }
        log.warn("[AgentDiscovery] ❌ 心跳更新失败，未知 Agent: serviceName={}", serviceName);
        return false;
    }

    /**
     * 清除缓存
     */
    public void clearCache() {
        agentCache.clear();
        this.fallbackAgent = null;  // ⭐ 同步清除降级 Agent
        if (redisTemplate != null) {
            redisTemplate.delete(SSE_URLS_REDIS_KEY);
            log.info("[AgentDiscovery] 🗑️ Agent 缓存已清除，降级 Agent 已置空");
        }
    }
    
    /**
     * ⭐ 扫描并订阅新的 Agent 服务
     */
    public int scanAndSubscribeNewServices() {
        if (!discoveryConfig.isEnabled()) {
            return 0;
        }
        
        try {
            List<String> allServices = getAllServicesWithPagination();
            
            int newSubscribedCount = 0;
            for (String serviceName : allServices) {
                if (isAgentService(serviceName) && 
                    !isExcludedService(serviceName) && 
                    !subscribedServices.contains(serviceName)) {
                    
                    try {
                        subscribedServices.add(serviceName);
                        namingService.subscribe(serviceName, new AgentChangeListener());
                        log.info("[AgentDiscovery] ✅ 新订阅服务: {}", serviceName);
                        newSubscribedCount++;
                        
                        DiscoveredAgent agent = discoverAgent(serviceName);
                        if (agent != null) {
                            agentCache.put(serviceName, agent);
                        }
                    } catch (Exception e) {
                        log.warn("[AgentDiscovery] ⚠️ 订阅新服务失败: {}, 错误: {}", 
                            serviceName, e.getMessage());
                    }
                }
            }
            
            if (newSubscribedCount > 0) {
                log.info("[AgentDiscovery] 📊 本次新订阅 {} 个 Agent 服务", newSubscribedCount);
                refreshFallbackAgent();  // ⭐ 刷新降级 Agent
                saveSseUrlsToRedis();
            }
            
            return newSubscribedCount;
            
        } catch (Exception e) {
            log.error("[AgentDiscovery] ❌ 扫描新服务失败: {}", e.getMessage(), e);
            return 0;
        }
    }
    
    /**
     * 获取已订阅的服务数量
     */
    public int getSubscribedServiceCount() {
        return subscribedServices.size();
    }
    
    // ==================== 内部辅助方法 ====================
    
    private boolean isAgentService(String serviceName) {
        List<String> suffixes = discoveryConfig.getServiceNameSuffixes();
        return suffixes.stream().anyMatch(serviceName::endsWith);
    }
    
    private boolean isExcludedService(String serviceName) {
        return discoveryConfig.getExcludedServices().contains(serviceName);
    }
    
    /**
     * Nacos 事件监听器
     */
    private class AgentChangeListener implements EventListener {
        @Override
        public void onEvent(Event event) {
            if (event instanceof NamingEvent namingEvent) {
                String serviceName = namingEvent.getServiceName();
                List<Instance> instances = namingEvent.getInstances();
                
                log.info("[AgentDiscovery] 📡 收到服务变更事件: {}, 实例数: {}", 
                        serviceName, instances != null ? instances.size() : 0);
                
                if (instances == null || instances.isEmpty()) {
                    // 服务下线,从缓存中移除
                    agentCache.remove(serviceName);
                    log.info("[AgentDiscovery] ❌ 服务下线: {}", serviceName);
                    refreshFallbackAgent();  // ⭐ 刷新降级 Agent
                    saveSseUrlsToRedis();
                } else {
                    // 服务上线或更新,重新发现
                    DiscoveredAgent agent = discoverAgent(serviceName);
                    if (agent != null) {
                        agentCache.put(serviceName, agent);
                        log.info("[AgentDiscovery] ✅ 服务更新: {}", serviceName);
                        refreshFallbackAgent();  // ⭐ 刷新降级 Agent
                        saveSseUrlsToRedis();
                    }
                }
            }
        }
    }
}
