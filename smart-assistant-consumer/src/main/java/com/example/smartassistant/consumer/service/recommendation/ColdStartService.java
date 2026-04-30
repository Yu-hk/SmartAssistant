package com.example.smartassistant.consumer.service.recommendation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 冷启动推荐服务
 * 针对新用户或无历史行为的用户,提供默认建议
 */
@Service
public class ColdStartService {
    
    private static final Logger log = LoggerFactory.getLogger(ColdStartService.class);
    
    // 热门问题模板(按场景分类)
    private static final Map<String, List<String>> HOT_TEMPLATES = new HashMap<>();
    
    static {
        // 美食类
        HOT_TEMPLATES.put("FOOD", Arrays.asList(
                "推荐{location}的特色美食",
                "{location}有什么必吃的餐厅?",
                "我想吃{location}的当地特色菜"
        ));
        
        // 旅游类
        HOT_TEMPLATES.put("TRAVEL", Arrays.asList(
                "{location}有哪些值得去的景点?",
                "帮我规划{location}的一日游行程",
                "{location}周末去哪里玩比较好?"
        ));
        
        // 天气类
        HOT_TEMPLATES.put("WEATHER", Arrays.asList(
                "{location}今天天气怎么样?",
                "{location}未来三天的天气预报",
                "{location}适合出门吗?"
        ));
        
        // 交通类
        HOT_TEMPLATES.put("TRANSPORT", Arrays.asList(
                "怎么去{location}最方便?",
                "{location}附近的地铁站在哪里?",
                "从当前位置到{location}怎么走?"
        ));
    }
    
    /**
     * 为新用户生成冷启动建议
     * 
     * @param location 用户所在地点(可为null)
     * @return 建议列表
     */
    public List<String> generateColdStartSuggestions(String location) {
        List<String> suggestions = new ArrayList<>();
        
        String loc = location != null ? location : "附近";
        
        // 从每个类别中随机选择一个模板
        Random random = new Random();
        
        for (Map.Entry<String, List<String>> entry : HOT_TEMPLATES.entrySet()) {
            List<String> templates = entry.getValue();
            if (!templates.isEmpty()) {
                String template = templates.get(random.nextInt(templates.size()));
                String suggestion = template.replace("{location}", loc);
                suggestions.add(suggestion);
            }
        }
        
        // 打乱顺序,避免固定模式
        Collections.shuffle(suggestions);
        
        // 最多返回4条
        return suggestions.subList(0, Math.min(4, suggestions.size()));
    }
    
    /**
     * 判断是否为冷启动场景
     * 
     * @param userId 用户ID
     * @param interactionCount 历史交互次数
     * @return 是否冷启动
     */
    public boolean isColdStart(String userId, int interactionCount) {
        // 匿名用户或交互次数少于3次视为冷启动
        return "anonymous".equals(userId) || interactionCount < 3;
    }
}
