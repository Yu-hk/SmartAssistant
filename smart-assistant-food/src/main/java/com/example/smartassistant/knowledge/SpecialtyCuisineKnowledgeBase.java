package com.example.smartassistant.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 全国特色菜知识库
 * <p>
 * 数据源：specialty-cuisine.json（classpath 资源文件），
 * 无需修改代码即可增删菜品。
 */
@Component
public class SpecialtyCuisineKnowledgeBase {

    private static final Logger log = LoggerFactory.getLogger(SpecialtyCuisineKnowledgeBase.class);

    private static final String DATA_FILE = "specialty-cuisine.json";

    public record SpecialtyDish(String name, String city, String province,
                                String description, String taste, List<String> ingredients) {
        public SpecialtyDish(String name, String city, String province,
                             String description, String taste, List<String> ingredients) {
            this.name = name;
            this.city = city;
            this.province = province;
            this.description = description;
            this.taste = taste;
            this.ingredients = ingredients != null ? ingredients : new ArrayList<>();
        }
    }

    private final Map<String, List<SpecialtyDish>> cityDishes = new HashMap<>();
    private final Map<String, List<SpecialtyDish>> provinceDishes = new HashMap<>();
    private final Map<String, List<SpecialtyDish>> tasteDishes = new HashMap<>();

    public SpecialtyCuisineKnowledgeBase() {
        loadFromJson();
    }

    private void loadFromJson() {
        List<SpecialtyDish> allDishes = new ArrayList<>();
        try {
            ObjectMapper mapper = new ObjectMapper();
            ClassPathResource resource = new ClassPathResource(DATA_FILE);
            if (!resource.exists()) {
                log.warn("[SpecialtyCuisine] 数据文件不存在: {}", DATA_FILE);
                return;
            }
            try (InputStream is = resource.getInputStream()) {
                List<Map<String, Object>> raw = mapper.readValue(is, new TypeReference<>() {});
                for (Map<String, Object> item : raw) {
                    String name = (String) item.get("name");
                    String city = (String) item.get("city");
                    String province = (String) item.get("province");
                    String description = (String) item.get("description");
                    String taste = (String) item.get("taste");
                    @SuppressWarnings("unchecked")
                    List<String> ingredients = (List<String>) item.get("ingredients");
                    allDishes.add(new SpecialtyDish(name, city, province, description, taste, ingredients));
                }
            }
            log.info("[SpecialtyCuisine] 从 {} 加载了 {} 道菜品", DATA_FILE, allDishes.size());
        } catch (Exception e) {
            log.error("[SpecialtyCuisine] 加载菜品数据失败: {}", e.getMessage(), e);
        }

        // 构建索引
        for (SpecialtyDish dish : allDishes) {
            cityDishes.computeIfAbsent(dish.city(), k -> new ArrayList<>()).add(dish);
            provinceDishes.computeIfAbsent(dish.province(), k -> new ArrayList<>()).add(dish);
            tasteDishes.computeIfAbsent(dish.taste(), k -> new ArrayList<>()).add(dish);
        }
    }

    public List<SpecialtyDish> getDishesByCity(String city) {
        return cityDishes.getOrDefault(city, Collections.emptyList());
    }

    public List<SpecialtyDish> getDishesByProvince(String province) {
        return provinceDishes.getOrDefault(province, Collections.emptyList());
    }

    public List<SpecialtyDish> getDishesByTaste(String taste) {
        return tasteDishes.entrySet().stream()
                .filter(e -> e.getKey().contains(taste))
                .flatMap(e -> e.getValue().stream())
                .collect(Collectors.toList());
    }

    public List<SpecialtyDish> searchDishes(String query) {
        if (query == null || query.isBlank()) return Collections.emptyList();
        String lower = query.toLowerCase();
        return cityDishes.values().stream()
                .flatMap(Collection::stream)
                .filter(d -> d.name().contains(lower) || d.description().contains(lower)
                        || d.city().contains(lower) || d.province().contains(lower)
                        || d.taste().contains(lower))
                .collect(Collectors.toList());
    }

    public Set<String> getAvailableCities() {
        return cityDishes.keySet();
    }

    public Set<String> getAllCities() {
        return cityDishes.keySet();
    }

    public Set<String> getAllProvinces() {
        return provinceDishes.keySet();
    }

    public Set<String> getAvailableTastes() {
        return tasteDishes.keySet();
    }

    /**
     * 格式化菜品列表为可读文本
     */
    public String formatDishesList(List<SpecialtyDish> dishes, String title) {
        if (dishes == null || dishes.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(title).append("】\n");
        for (SpecialtyDish dish : dishes) {
            sb.append("• ").append(dish.name()).append("：").append(dish.description());
            sb.append("（").append(dish.taste()).append("）\n");
        }
        return sb.toString();
    }
}
