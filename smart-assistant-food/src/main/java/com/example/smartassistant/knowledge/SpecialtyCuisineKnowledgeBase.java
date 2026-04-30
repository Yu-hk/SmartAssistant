package com.example.smartassistant.knowledge;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 全国特色菜知识库
 * 存储全国各地的特色菜品信息
 */
@Component
public class SpecialtyCuisineKnowledgeBase {

    /**
     * 特色菜数据结构
     *
     * @param name        Getters 菜名
     * @param city        城市
     * @param province    省份
     * @param description 描述
     * @param taste       口味特点
     * @param ingredients 主要食材
     */
        public record SpecialtyDish(String name, String city, String province, String description, String taste,
                                    List<String> ingredients) {
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

    // 知识库数据存储
    private final Map<String, List<SpecialtyDish>> cityDishes = new HashMap<>();
    private final Map<String, List<SpecialtyDish>> provinceDishes = new HashMap<>();
    private final Map<String, List<SpecialtyDish>> tasteDishes = new HashMap<>();

    public SpecialtyCuisineKnowledgeBase() {
        initializeKnowledgeBase();
    }

    /**
     * 初始化知识库 - 全国各地特色菜
     */
    private void initializeKnowledgeBase() {
        List<SpecialtyDish> allDishes = new ArrayList<>();

        // ===== 北京 =====
        allDishes.add(new SpecialtyDish("北京烤鸭", "北京", "北京", 
                "北京著名菜肴，以色泽红润、肉质细嫩、味道醇厚、肥而不腻著称", 
                "香脆可口", Arrays.asList("鸭肉", "面饼", "葱丝", "黄瓜", "甜面酱")));
        allDishes.add(new SpecialtyDish("炸酱面", "北京", "北京", 
                "老北京传统面食，黄酱和甜面酱炒制炸酱，配上各种菜码", 
                "咸香浓郁", Arrays.asList("面条", "猪肉", "黄酱", "黄瓜", "豆芽")));
        allDishes.add(new SpecialtyDish("豆汁儿", "北京", "北京", 
                "北京传统小吃，用绿豆发酵制成，味道独特", 
                "酸涩特殊", List.of("绿豆")));

        // ===== 四川/成都 =====
        allDishes.add(new SpecialtyDish("麻婆豆腐", "成都", "四川", 
                "川菜经典，豆腐嫩滑，麻辣鲜香", 
                "麻辣鲜香", Arrays.asList("豆腐", "牛肉末", "豆瓣酱", "花椒", "辣椒")));
        allDishes.add(new SpecialtyDish("回锅肉", "成都", "四川", 
                "川菜代表菜之一，肉片肥瘦相间，口感鲜嫩", 
                "香辣味美", Arrays.asList("猪肉", "蒜苗", "豆瓣酱", "豆豉")));
        allDishes.add(new SpecialtyDish("宫保鸡丁", "成都", "四川", 
                "川菜名菜，鸡肉鲜嫩，花生酥脆，酸甜微辣", 
                "酸甜微辣", Arrays.asList("鸡肉", "花生", "干辣椒", "花椒")));
        allDishes.add(new SpecialtyDish("火锅", "成都", "四川", 
                "四川特色美食，以麻辣锅底闻名，可涮各种食材", 
                "麻辣鲜香", Arrays.asList("牛油", "辣椒", "花椒", "各种食材")));

        // ===== 广东/广州 =====
        allDishes.add(new SpecialtyDish("白切鸡", "广州", "广东", 
                "粤菜经典，皮爽肉滑，清淡鲜美", 
                "清淡鲜美", Arrays.asList("鸡肉", "姜", "葱")));
        allDishes.add(new SpecialtyDish("烧鹅", "广州", "广东", 
                "广东传统名菜，皮脆肉嫩，色泽金红", 
                "香脆多汁", Arrays.asList("鹅肉", "五香粉", "蜂蜜")));
        allDishes.add(new SpecialtyDish("虾饺", "广州", "广东", 
                "广式早茶四大天王之一，皮薄馅大", 
                "鲜美弹牙", Arrays.asList("虾仁", "猪肉", "竹笋", "澄面")));
        allDishes.add(new SpecialtyDish("叉烧包", "广州", "广东", 
                "广式点心，外皮松软，内馅甜美", 
                "甜咸适中", Arrays.asList("面粉", "叉烧肉", "蚝油")));

        // ===== 上海 =====
        allDishes.add(new SpecialtyDish("小笼包", "上海", "上海", 
                "上海特色点心，皮薄汁多，鲜美可口", 
                "鲜美多汁", Arrays.asList("猪肉", "皮冻", "面粉")));
        allDishes.add(new SpecialtyDish("红烧肉", "上海", "上海", 
                "本帮菜代表，肥而不腻，入口即化", 
                "甜咸适口", Arrays.asList("五花肉", "冰糖", "酱油")));
        allDishes.add(new SpecialtyDish("生煎包", "上海", "上海", 
                "上海传统小吃，底部金黄酥脆，上部松软", 
                "外酥里嫩", Arrays.asList("猪肉", "面粉", "芝麻", "葱花")));

        // ===== 江苏/南京 =====
        allDishes.add(new SpecialtyDish("盐水鸭", "南京", "江苏", 
                "南京特产，皮白肉嫩，肥而不腻", 
                "清香鲜美", Arrays.asList("鸭肉", "盐", "香料")));
        allDishes.add(new SpecialtyDish("狮子头", "扬州", "江苏", 
                "淮扬菜经典，肉质细嫩，汤汁清澈", 
                "鲜嫩清淡", Arrays.asList("猪肉", "荸荠", "青菜")));

        // ===== 浙江/杭州 =====
        allDishes.add(new SpecialtyDish("西湖醋鱼", "杭州", "浙江", 
                "杭州名菜，鱼肉鲜嫩，酸甜可口", 
                "酸甜鲜美", Arrays.asList("草鱼", "醋", "糖", "姜")));
        allDishes.add(new SpecialtyDish("东坡肉", "杭州", "浙江", 
                "杭州传统名菜，色泽红亮，肥而不腻", 
                "软糯香甜", Arrays.asList("五花肉", "黄酒", "酱油", "冰糖")));
        allDishes.add(new SpecialtyDish("龙井虾仁", "杭州", "浙江", 
                "杭州特色菜，虾仁鲜嫩，茶香四溢", 
                "清香鲜嫩", Arrays.asList("虾仁", "龙井茶叶")));

        // ===== 湖南/长沙 =====
        allDishes.add(new SpecialtyDish("剁椒鱼头", "长沙", "湖南", 
                "湘菜代表，鱼头鲜嫩，剁椒香辣", 
                "香辣鲜美", Arrays.asList("鱼头", "剁椒", "姜", "蒜")));
        allDishes.add(new SpecialtyDish("臭豆腐", "长沙", "湖南", 
                "长沙特色小吃，闻着臭吃着香", 
                "外焦里嫩", Arrays.asList("豆腐", "卤水", "辣椒")));
        allDishes.add(new SpecialtyDish("辣椒炒肉", "长沙", "湖南", 
                "湘菜家常菜，香辣下饭", 
                "香辣可口", Arrays.asList("猪肉", "辣椒", "豆豉")));

        // ===== 陕西/西安 =====
        allDishes.add(new SpecialtyDish("肉夹馍", "西安", "陕西", 
                "陕西传统小吃，馍酥肉香", 
                "香酥可口", Arrays.asList("猪肉", "面粉", "香料")));
        allDishes.add(new SpecialtyDish("羊肉泡馍", "西安", "陕西", 
                "西安特色，馍块浸泡在羊肉汤中", 
                "鲜香浓郁", Arrays.asList("羊肉", "馍", "粉丝", "香菜")));
        allDishes.add(new SpecialtyDish("凉皮", "西安", "陕西", 
                "陕西小吃，爽滑筋道，酸辣开胃", 
                "酸辣爽口", Arrays.asList("米皮", "黄瓜", "面筋", "辣椒油")));

        // ===== 云南/昆明 =====
        allDishes.add(new SpecialtyDish("过桥米线", "昆明", "云南", 
                "云南特色，汤鲜味美，配料丰富", 
                "鲜美醇厚", Arrays.asList("米线", "鸡汤", "肉片", "蔬菜")));
        allDishes.add(new SpecialtyDish("汽锅鸡", "昆明", "云南", 
                "云南名菜，原汁原味，营养丰富", 
                "鲜嫩清香", Arrays.asList("鸡肉", "三七", "天麻")));

        // ===== 福建/厦门 =====
        allDishes.add(new SpecialtyDish("沙茶面", "厦门", "福建", 
                "厦门特色，汤头浓郁，配料丰富", 
                "鲜香微辣", Arrays.asList("面条", "沙茶酱", "海鲜", "豆腐")));
        allDishes.add(new SpecialtyDish("土笋冻", "厦门", "福建", 
                "闽南特色小吃，清凉爽口", 
                "Q弹清爽", Arrays.asList("海虫", "胶质")));

        // ===== 湖北/武汉 =====
        allDishes.add(new SpecialtyDish("热干面", "武汉", "湖北", 
                "武汉早餐代表，面条劲道，芝麻酱香浓", 
                "香浓劲道", Arrays.asList("面条", "芝麻酱", "萝卜干", "葱花")));
        allDishes.add(new SpecialtyDish("武昌鱼", "武汉", "湖北", 
                "湖北名菜，肉质细嫩，味道鲜美", 
                "鲜嫩可口", Arrays.asList("武昌鱼", "冬笋", "香菇")));

        // ===== 天津 =====
        allDishes.add(new SpecialtyDish("狗不理包子", "天津", "天津", 
                "天津传统名点，皮薄馅大，滋味鲜美", 
                "鲜香多汁", Arrays.asList("猪肉", "面粉", "酱油", "香油")));
        allDishes.add(new SpecialtyDish("煎饼果子", "天津", "天津", 
                "天津特色早餐，薄脆可口", 
                "香脆可口", Arrays.asList("绿豆面", "鸡蛋", "油条", "面酱")));

        // ===== 重庆 =====
        allDishes.add(new SpecialtyDish("重庆小面", "重庆", "重庆", 
                "重庆特色，麻辣鲜香，面条劲道", 
                "麻辣鲜香", Arrays.asList("面条", "辣椒", "花椒", "花生")));
        allDishes.add(new SpecialtyDish("毛血旺", "重庆", "重庆", 
                "重庆江湖菜，麻辣鲜香，内容丰富", 
                "麻辣过瘾", Arrays.asList("鸭血", "毛肚", "午餐肉", "辣椒")));

        // ===== 甘肃/兰州 =====
        allDishes.add(new SpecialtyDish("兰州拉面", "兰州", "甘肃", 
                "兰州特色，面条细长，汤清味美", 
                "清香鲜美", Arrays.asList("牛肉", "面条", "萝卜", "香菜")));

        // ===== 新疆/乌鲁木齐 =====
        allDishes.add(new SpecialtyDish("烤羊肉串", "乌鲁木齐", "新疆", 
                "新疆特色，肉质鲜嫩，孜然飘香", 
                "香嫩多汁", Arrays.asList("羊肉", "孜然", "辣椒面")));
        allDishes.add(new SpecialtyDish("大盘鸡", "乌鲁木齐", "新疆", 
                "新疆名菜，鸡肉鲜嫩，土豆软糯", 
                "香辣可口", Arrays.asList("鸡肉", "土豆", "青椒", "宽面")));

        // ===== 河南/郑州 =====
        allDishes.add(new SpecialtyDish("烩面", "郑州", "河南", 
                "河南特色，面条宽厚，汤鲜味美", 
                "鲜美醇厚", Arrays.asList("羊肉", "面条", "粉丝", "海带")));

        // ===== 山东/青岛 =====
        allDishes.add(new SpecialtyDish("青岛啤酒配海鲜", "青岛", "山东", 
                "青岛特色，新鲜海鲜配冰镇啤酒", 
                "鲜美清爽", Arrays.asList("海鲜", "啤酒")));
        allDishes.add(new SpecialtyDish("九转大肠", "济南", "山东", 
                "鲁菜经典，色泽红亮，五味俱全", 
                "酸甜苦咸香", Arrays.asList("猪大肠", "砂仁", "桂皮")));

        // ===== 河北/石家庄 =====
        allDishes.add(new SpecialtyDish("驴肉火烧", "保定", "河北", 
                "河北传统名吃，外酥里嫩，驴肉鲜香", 
                "香酥可口", Arrays.asList("驴肉", "面粉", "青椒")));
        allDishes.add(new SpecialtyDish("金凤扒鸡", "石家庄", "河北", 
                "石家庄特色，鸡肉鲜嫩，香气扑鼻", 
                "鲜香浓郁", Arrays.asList("鸡肉", "香料")));
        allDishes.add(new SpecialtyDish("正定崩肝", "正定", "河北", 
                "河北传统小吃，口感独特，风味浓郁", 
                "香辣味美", Arrays.asList("牛肝", "辣椒", "香料")));
        allDishes.add(new SpecialtyDish("缸炉烧饼", "石家庄", "河北", 
                "河北特色面点，层层酥脆，芝麻飘香", 
                "香脆可口", Arrays.asList("面粉", "芝麻", "香油")));

        // 按城市分类
        for (SpecialtyDish dish : allDishes) {
            cityDishes.computeIfAbsent(dish.city(), k -> new ArrayList<>()).add(dish);
            provinceDishes.computeIfAbsent(dish.province(), k -> new ArrayList<>()).add(dish);
            
            // 按口味分类
            String taste = dish.taste();
            if (taste.contains("麻辣")) {
                tasteDishes.computeIfAbsent("麻辣", k -> new ArrayList<>()).add(dish);
            }
            if (taste.contains("香辣")) {
                tasteDishes.computeIfAbsent("香辣", k -> new ArrayList<>()).add(dish);
            }
            if (taste.contains("清淡") || taste.contains("清香")) {
                tasteDishes.computeIfAbsent("清淡", k -> new ArrayList<>()).add(dish);
            }
            if (taste.contains("酸甜")) {
                tasteDishes.computeIfAbsent("酸甜", k -> new ArrayList<>()).add(dish);
            }
            if (taste.contains("鲜香") || taste.contains("鲜美")) {
                tasteDishes.computeIfAbsent("鲜香", k -> new ArrayList<>()).add(dish);
            }
        }
    }

    /**
     * 根据城市查询特色菜
     */
    public List<SpecialtyDish> getDishesByCity(String city) {
        return cityDishes.getOrDefault(city, new ArrayList<>());
    }

    /**
     * 根据省份查询特色菜
     */
    public List<SpecialtyDish> getDishesByProvince(String province) {
        return provinceDishes.getOrDefault(province, new ArrayList<>());
    }

    /**
     * 根据口味查询特色菜
     */
    public List<SpecialtyDish> getDishesByTaste(String taste) {
        return tasteDishes.getOrDefault(taste, new ArrayList<>());
    }

    /**
     * 搜索特色菜（支持菜名、城市、口味模糊搜索）
     */
    public List<SpecialtyDish> searchDishes(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return new ArrayList<>();
        }

        String lowerKeyword = keyword.toLowerCase();
        Set<SpecialtyDish> results = new LinkedHashSet<>();

        // 搜索所有菜品
        for (List<SpecialtyDish> dishes : cityDishes.values()) {
            for (SpecialtyDish dish : dishes) {
                if (dish.name().contains(keyword) ||
                    dish.city().contains(keyword) ||
                    dish.province().contains(keyword) ||
                    dish.taste().contains(keyword) ||
                    dish.description().contains(keyword)) {
                    results.add(dish);
                }
            }
        }

        return new ArrayList<>(results);
    }

    /**
     * 获取所有城市列表
     */
    public Set<String> getAllCities() {
        return cityDishes.keySet();
    }

    /**
     * 获取所有省份列表
     */
    public Set<String> getAllProvinces() {
        return provinceDishes.keySet();
    }

    /**
     * 格式化特色菜信息为文本
     */
    public String formatDishInfo(SpecialtyDish dish) {
        StringBuilder sb = new StringBuilder();
        sb.append("• ").append(dish.name()).append("\n");
        sb.append("  📍 ").append(dish.city()).append(", ").append(dish.province()).append("\n");
        sb.append("  👅 口味：").append(dish.taste()).append("\n");
        sb.append("  📝 简介：").append(dish.description()).append("\n");
        if (!dish.ingredients().isEmpty()) {
            sb.append("  🥘 主要食材：").append(String.join("、", dish.ingredients())).append("\n");
        }
        return sb.toString();
    }

    /**
     * 格式化多个特色菜信息
     */
    public String formatDishesList(List<SpecialtyDish> dishes, String title) {
        if (dishes.isEmpty()) {
            return "未找到相关的特色菜信息";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("🍽️ 【").append(title).append("】\n\n");
        
        for (int i = 0; i < Math.min(10, dishes.size()); i++) {
            sb.append(formatDishInfo(dishes.get(i)));
            if (i < Math.min(10, dishes.size()) - 1) {
                sb.append("\n");
            }
        }

        if (dishes.size() > 10) {
            sb.append("\n... 还有 ").append(dishes.size() - 10).append(" 道菜品\n");
        }

        return sb.toString();
    }
}
