package com.example.smartassistant.knowledge;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;

/**
 * 旅游景点知识库
 * 提供全国主要城市的景点信息查询服务
 */
@Component
public class TouristAttractionKnowledgeBase {

    private static final Logger log = LoggerFactory.getLogger(TouristAttractionKnowledgeBase.class);

    /**
     * 景点信息
     */
    public record AttractionInfo(
            String name,           // 景点名称
            String city,           // 所在城市
            String province,       // 所在省份
            String description,    // 描述
            String level,          // 等级（5A/4A等）
            Double ticketPrice,    // 门票价格
            String openTime,       // 开放时间
            Integer suggestDuration, // 建议游玩时长（小时）
            List<String> tags,     // 标签
            List<String> highlights // 亮点
    ) {
        @NotNull
        @Override
        public String toString() {
            return String.format("📍 %s (%s)\n等级：%s\n门票：%.0f元\n开放：%s\n建议游玩：%d小时\n描述：%s\n亮点：%s",
                    name, city, level, 
                    ticketPrice != null ? ticketPrice : 0,
                    openTime, 
                    suggestDuration != null ? suggestDuration : 0,
                    description,
                    String.join("、", highlights != null ? highlights : Collections.emptyList()));
        }
    }

    // 城市到景点列表的映射
    private final Map<String, List<AttractionInfo>> cityAttractions = new HashMap<>();
    
    // 所有景点列表（用于全局搜索）
    private final List<AttractionInfo> allAttractions = new ArrayList<>();

    @PostConstruct
    public void init() {
        log.info("[TouristAttractionKB] 开始初始化旅游景点数据...");
        loadAllAttractions();
        log.info("[TouristAttractionKB] 初始化完成，共加载 {} 个景点", allAttractions.size());
    }

    /**
     * 加载所有景点数据
     */
    private void loadAllAttractions() {
        // ========== 北京 ==========
        addAttraction(new AttractionInfo(
                "故宫博物院", "北京", "北京",
                "明清两代的皇家宫殿，世界上现存规模最大、保存最完整的木质结构古建筑之一",
                "5A", 60.0, "08:30-17:00", 180,
                Arrays.asList("历史", "文化", "建筑", "博物馆"),
                Arrays.asList("太和殿", "中和殿", "保和殿", "珍宝馆")
        ));

        addAttraction(new AttractionInfo(
                "长城（八达岭）", "北京", "北京",
                "世界文化遗产，中国古代伟大的防御工程万里长城的重要组成部分",
                "5A", 40.0, "06:30-19:00", 240,
                Arrays.asList("历史", "自然", "徒步", "世界遗产"),
                Arrays.asList("好汉坡", "北八楼", "烽火台")
        ));

        addAttraction(new AttractionInfo(
                "天坛公园", "北京", "北京",
                "明清两代皇帝祭天祈谷的场所，中国现存最大的古代祭祀性建筑群",
                "5A", 15.0, "06:00-22:00", 120,
                Arrays.asList("历史", "文化", "公园"),
                Arrays.asList("祈年殿", "回音壁", "圜丘")
        ));

        addAttraction(new AttractionInfo(
                "颐和园", "北京", "北京",
                "中国清朝时期皇家园林，被誉为'皇家园林博物馆'",
                "5A", 30.0, "06:30-18:00", 180,
                Arrays.asList("历史", "园林", "湖泊"),
                Arrays.asList("昆明湖", "万寿山", "长廊", "十七孔桥")
        ));

        // ========== 上海 ==========
        addAttraction(new AttractionInfo(
                "外滩", "上海", "上海",
                "上海标志性景点，全长1.5公里，汇集了52幢风格迥异的古典复兴大楼",
                "5A", 0.0, "全天开放", 120,
                Arrays.asList("建筑", "夜景", "历史", "免费"),
                Arrays.asList("万国建筑博览群", "黄浦江夜景", "外白渡桥")
        ));

        addAttraction(new AttractionInfo(
                "东方明珠塔", "上海", "上海",
                "上海标志性文化景观之一，塔高约468米",
                "5A", 160.0, "08:00-21:30", 120,
                Arrays.asList("现代", "观景", "地标"),
                Arrays.asList("观光层", "旋转餐厅", "太空舱")
        ));

        addAttraction(new AttractionInfo(
                "豫园", "上海", "上海",
                "明代江南古典园林，占地三十余亩，园内布局精致",
                "5A", 40.0, "09:00-16:30", 90,
                Arrays.asList("园林", "历史", "文化"),
                Arrays.asList("九曲桥", "湖心亭", "点春堂")
        ));

        // ========== 成都 ==========
        addAttraction(new AttractionInfo(
                "大熊猫繁育研究基地", "成都", "四川",
                "全球最大的大熊猫人工繁育种群基地，可近距离观赏国宝大熊猫",
                "5A", 55.0, "07:30-18:00", 180,
                Arrays.asList("动物", "自然", "亲子"),
                Arrays.asList("大熊猫", "小熊猫", "月亮产房", "太阳产房")
        ));

        addAttraction(new AttractionInfo(
                "宽窄巷子", "成都", "四川",
                "成都遗留下来的较成规模的清朝古街道，由宽巷子、窄巷子和井巷子组成",
                "4A", 0.0, "全天开放", 120,
                Arrays.asList("历史", "美食", "文化", "免费"),
                Arrays.asList("老成都生活", "特色小吃", "川剧变脸")
        ));

        addAttraction(new AttractionInfo(
                "都江堰", "成都", "四川",
                "世界文化遗产，始建于秦昭王末年，是全世界迄今为止年代最久的大型水利工程",
                "5A", 80.0, "08:00-17:30", 180,
                Arrays.asList("历史", "水利", "世界遗产"),
                Arrays.asList("鱼嘴", "飞沙堰", "宝瓶口", "安澜索桥")
        ));

        // ========== 西安 ==========
        addAttraction(new AttractionInfo(
                "秦始皇兵马俑", "西安", "陕西",
                "世界第八大奇迹，秦始皇陵的陪葬坑，展现了秦军的雄伟气势",
                "5A", 120.0, "08:30-17:00", 180,
                Arrays.asList("历史", "文化", "世界遗产", "博物馆"),
                Arrays.asList("一号坑", "二号坑", "三号坑", "铜车马")
        ));

        addAttraction(new AttractionInfo(
                "大雁塔", "西安", "陕西",
                "唐代著名佛塔，为保存玄奘法师由天竺经丝绸之路带回长安的经卷佛像而建",
                "5A", 50.0, "08:00-18:30", 90,
                Arrays.asList("历史", "佛教", "文化"),
                Arrays.asList("大慈恩寺", "音乐喷泉", "玄奘雕像")
        ));

        addAttraction(new AttractionInfo(
                "古城墙", "西安", "陕西",
                "中国现存规模最大、保存最完整的古代城垣，周长13.74公里",
                "5A", 54.0, "08:00-22:00", 120,
                Arrays.asList("历史", "骑行", "文化"),
                Arrays.asList("永宁门", "环城公园", "城墙骑行")
        ));

        // ========== 杭州 ==========
        addAttraction(new AttractionInfo(
                "西湖", "杭州", "浙江",
                "中国大陆首批国家重点风景名胜区，世界文化遗产，以秀丽的湖光山色闻名",
                "5A", 0.0, "全天开放", 240,
                Arrays.asList("自然", "湖泊", "文化", "免费", "世界遗产"),
                Arrays.asList("断桥残雪", "苏堤春晓", "雷峰夕照", "三潭印月")
        ));

        addAttraction(new AttractionInfo(
                "灵隐寺", "杭州", "浙江",
                "中国最早的佛教寺院之一，距今已有1670多年历史",
                "5A", 75.0, "06:30-18:00", 120,
                Arrays.asList("佛教", "历史", "文化"),
                Arrays.asList("大雄宝殿", "飞来峰", "五百罗汉堂")
        ));

        // ========== 广州 ==========
        addAttraction(new AttractionInfo(
                "广州塔", "广州", "广东",
                "中国第一高塔，世界第二高塔，昵称'小蛮腰'",
                "5A", 150.0, "09:30-22:30", 120,
                Arrays.asList("现代", "观景", "地标"),
                Arrays.asList("摩天轮", "极速云霄", "观景平台")
        ));

        addAttraction(new AttractionInfo(
                "长隆旅游度假区", "广州", "广东",
                "国家级旅游度假区，包含野生动物世界、欢乐世界等多个主题园区",
                "5A", 300.0, "09:30-18:00", 480,
                Arrays.asList("主题乐园", "动物", "亲子"),
                Arrays.asList("野生动物世界", "欢乐世界", "水上乐园")
        ));

        // ========== 深圳 ==========
        addAttraction(new AttractionInfo(
                "世界之窗", "深圳", "广东",
                "荟萃世界几千年人类文明精华的主题公园，按比例复刻世界著名景观",
                "5A", 220.0, "09:00-22:00", 300,
                Arrays.asList("主题乐园", "文化", "建筑"),
                Arrays.asList("埃菲尔铁塔", "金字塔", "凯旋门")
        ));

        addAttraction(new AttractionInfo(
                "东部华侨城", "深圳", "广东",
                "集休闲度假、观光旅游、户外运动于一体的综合性旅游度假区",
                "5A", 200.0, "09:00-18:00", 360,
                Arrays.asList("主题乐园", "自然", "度假"),
                Arrays.asList("大侠谷", "茶溪谷", "云海索道")
        ));

        // ========== 重庆 ==========
        addAttraction(new AttractionInfo(
                "洪崖洞", "重庆", "重庆",
                "重庆标志性景点，巴渝传统建筑特色的吊脚楼为主体，夜景绝美",
                "4A", 0.0, "全天开放", 120,
                Arrays.asList("建筑", "夜景", "美食", "免费"),
                Arrays.asList("吊脚楼", "江景", "美食街", "千厮门大桥")
        ));

        addAttraction(new AttractionInfo(
                "解放碑", "重庆", "重庆",
                "重庆地标性建筑，抗战胜利纪功碑暨人民解放纪念碑",
                "4A", 0.0, "全天开放", 60,
                Arrays.asList("历史", "地标", "购物", "免费"),
                Arrays.asList("步行街", "美食", "购物")
        ));

        // ========== 南京 ==========
        addAttraction(new AttractionInfo(
                "中山陵", "南京", "江苏",
                "中国近代伟大的民主革命先行者孙中山先生的陵寝",
                "5A", 0.0, "08:00-17:00", 120,
                Arrays.asList("历史", "文化", "免费"),
                Arrays.asList("博爱坊", "祭堂", "墓室", "石阶392级")
        ));

        addAttraction(new AttractionInfo(
                "夫子庙", "南京", "江苏",
                "供奉祭祀孔子的地方，中国四大文庙之一",
                "5A", 0.0, "全天开放", 120,
                Arrays.asList("历史", "文化", "美食", "免费"),
                Arrays.asList("大成殿", "秦淮河", "乌衣巷", "美食街")
        ));

        // ========== 武汉 ==========
        addAttraction(new AttractionInfo(
                "黄鹤楼", "武汉", "湖北",
                "江南三大名楼之一，因唐代诗人崔颢登楼所题《黄鹤楼》一诗而名扬四海",
                "5A", 70.0, "08:00-18:00", 90,
                Arrays.asList("历史", "文化", "建筑"),
                Arrays.asList("主楼", "白云阁", "岳飞铜像")
        ));

        addAttraction(new AttractionInfo(
                "东湖", "武汉", "湖北",
                "中国最大的城中湖，水域面积33平方公里",
                "5A", 0.0, "全天开放", 180,
                Arrays.asList("自然", "湖泊", "免费"),
                Arrays.asList("听涛景区", "磨山景区", "落雁景区")
        ));

        // ========== 厦门 ==========
        addAttraction(new AttractionInfo(
                "鼓浪屿", "厦门", "福建",
                "世界文化遗产，素有'海上花园'之美誉",
                "5A", 0.0, "全天开放", 240,
                Arrays.asList("海岛", "历史", "建筑", "免费", "世界遗产"),
                Arrays.asList("日光岩", "菽庄花园", "钢琴博物馆", "各国领事馆")
        ));

        addAttraction(new AttractionInfo(
                "南普陀寺", "厦门", "福建",
                "闽南佛教胜地之一，始建于唐代",
                "4A", 0.0, "08:00-17:00", 90,
                Arrays.asList("佛教", "历史", "免费"),
                Arrays.asList("大雄宝殿", "藏经阁", "五老峰")
        ));

        // ========== 三亚 ==========
        addAttraction(new AttractionInfo(
                "亚龙湾", "三亚", "海南",
                "天下第一湾，拥有7千米长的银白色海滩，沙质细腻",
                "5A", 0.0, "全天开放", 180,
                Arrays.asList("海滩", "度假", "免费"),
                Arrays.asList("沙滩", "海水浴场", "热带天堂森林公园")
        ));

        addAttraction(new AttractionInfo(
                "南山文化旅游区", "三亚", "海南",
                "展示中国佛教传统文化的大型园区，108米海上观音圣像举世瞩目",
                "5A", 122.0, "08:00-18:00", 240,
                Arrays.asList("佛教", "文化", "景观"),
                Arrays.asList("海上观音", "南山寺", "金玉观音阁")
        ));

        // ========== 丽江 ==========
        addAttraction(new AttractionInfo(
                "丽江古城", "丽江", "云南",
                "世界文化遗产，具有浓郁地方民族特色的历史文化名城",
                "5A", 50.0, "全天开放", 240,
                Arrays.asList("历史", "文化", "古镇", "世界遗产"),
                Arrays.asList("四方街", "木府", "大水车", "酒吧街")
        ));

        addAttraction(new AttractionInfo(
                "玉龙雪山", "丽江", "云南",
                "北半球最近赤道的终年积雪山脉，主峰扇子陡海拔5596米",
                "5A", 100.0, "07:00-18:00", 300,
                Arrays.asList("自然", "雪山", "徒步"),
                Arrays.asList("冰川公园", "蓝月谷", "云杉坪", "牦牛坪")
        ));

        // ========== 桂林 ==========
        addAttraction(new AttractionInfo(
                "漓江", "桂林", "广西",
                "世界自然遗产，桂林山水甲天下，漓江山水甲桂林",
                "5A", 215.0, "08:00-17:00", 240,
                Arrays.asList("自然", "河流", "世界遗产"),
                Arrays.asList("九马画山", "黄布倒影", "兴坪古镇")
        ));

        addAttraction(new AttractionInfo(
                "象鼻山", "桂林", "广西",
                "桂林城徽，因整座山酷似一头驻足漓江边饮水的大象而得名",
                "5A", 0.0, "06:30-18:00", 60,
                Arrays.asList("自然", "地标", "免费"),
                Arrays.asList("水月洞", "爱情岛", "普贤塔")
        ));
    }

    /**
     * 添加景点
     */
    private void addAttraction(AttractionInfo attraction) {
        allAttractions.add(attraction);
        cityAttractions.computeIfAbsent(attraction.city(), k -> new ArrayList<>()).add(attraction);
    }

    /**
     * 根据城市查询景点列表
     *
     * @param city 城市名称
     * @return 景点列表
     */
    public List<AttractionInfo> getAttractionsByCity(String city) {
        if (city == null || city.isEmpty()) {
            return Collections.emptyList();
        }
        
        List<AttractionInfo> attractions = cityAttractions.get(city);
        return attractions != null ? attractions : Collections.emptyList();
    }

    /**
     * 根据关键词搜索景点
     *
     * @param keyword 关键词
     * @return 匹配的景点列表
     */
    public List<AttractionInfo> searchAttractions(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return Collections.emptyList();
        }

        String lowerKeyword = keyword.toLowerCase();
        List<AttractionInfo> results = new ArrayList<>();

        for (AttractionInfo attraction : allAttractions) {
            // 匹配景点名称、城市、描述、标签
            if (attraction.name().contains(keyword) ||
                attraction.city().contains(keyword) ||
                attraction.description().contains(keyword) ||
                attraction.tags().stream().anyMatch(tag -> tag.contains(keyword))) {
                results.add(attraction);
            }
        }

        return results;
    }

    /**
     * 获取热门景点推荐（按城市）
     *
     * @param city 城市名称
     * @param limit 返回数量限制
     * @return 热门景点列表
     */
    public List<AttractionInfo> getTopAttractions(String city, int limit) {
        List<AttractionInfo> attractions = getAttractionsByCity(city);
        return attractions.stream().limit(limit).toList();
    }

    /**
     * 获取所有支持的城市列表
     *
     * @return 城市名称集合
     */
    public Set<String> getAllCities() {
        return Collections.unmodifiableSet(cityAttractions.keySet());
    }

    /**
     * 格式化景点信息输出
     *
     * @param attraction 景点信息
     * @return 格式化的字符串
     */
    public String formatAttractionInfo(AttractionInfo attraction) {
        StringBuilder sb = new StringBuilder();
        sb.append("🏛️ 【").append(attraction.name()).append("】\n");
        sb.append("📍 位置：").append(attraction.province()).append(" ").append(attraction.city()).append("\n");
        sb.append("⭐ 等级：").append(attraction.level()).append("级景区\n");
        
        if (attraction.ticketPrice() != null && attraction.ticketPrice() > 0) {
            sb.append("💰 门票：").append(String.format("%.0f", attraction.ticketPrice())).append("元\n");
        } else {
            sb.append("💰 门票：免费\n");
        }
        
        sb.append("⏰ 开放：").append(attraction.openTime()).append("\n");
        
        if (attraction.suggestDuration() != null) {
            sb.append("⏱️ 建议游玩：").append(attraction.suggestDuration()).append("分钟\n");
        }
        
        sb.append("🏷️ 标签：").append(String.join("、", attraction.tags())).append("\n");
        sb.append("📝 简介：").append(attraction.description()).append("\n");
        
        if (attraction.highlights() != null && !attraction.highlights().isEmpty()) {
            sb.append("✨ 亮点：").append(String.join("、", attraction.highlights())).append("\n");
        }
        
        return sb.toString();
    }

    /**
     * 生成城市旅游攻略
     *
     * @param city 城市名称
     * @return 旅游攻略文本
     */
    public String generateTravelGuide(String city) {
        List<AttractionInfo> attractions = getAttractionsByCity(city);
        
        if (attractions.isEmpty()) {
            return "抱歉，暂未收录 " + city + " 的景点信息。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("🗺️ 【").append(city).append("旅游攻略】\n\n");
        sb.append("📊 共有 ").append(attractions.size()).append(" 个推荐景点\n\n");
        
        // 推荐 TOP 景点
        sb.append("🌟 必游景点推荐：\n");
        int count = Math.min(3, attractions.size());
        for (int i = 0; i < count; i++) {
            AttractionInfo attraction = attractions.get(i);
            sb.append((i + 1)).append(". ").append(attraction.name());
            if (attraction.ticketPrice() != null && attraction.ticketPrice() > 0) {
                sb.append(" (¥").append(String.format("%.0f", attraction.ticketPrice())).append(")");
            } else {
                sb.append(" (免费)");
            }
            sb.append("\n");
        }
        
        sb.append("\n💡 旅行贴士：\n");
        sb.append("• 建议游玩时间：").append(getSuggestedDays(city)).append("天\n");
        sb.append("• 最佳季节：根据具体景点而定\n");
        sb.append("• 交通方式：建议使用地铁/公交/打车\n");
        
        return sb.toString();
    }

    /**
     * 获取建议游玩天数
     */
    private int getSuggestedDays(String city) {
        List<AttractionInfo> attractions = getAttractionsByCity(city);
        if (attractions.size() <= 3) {
            return 1;
        } else if (attractions.size() <= 6) {
            return 2;
        } else {
            return 3;
        }
    }

    /**
     * 获取统计信息
     */
    public String getStatistics() {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 【旅游景点知识库统计】\n\n");
        sb.append("覆盖城市：").append(cityAttractions.size()).append(" 个\n");
        sb.append("景点总数：").append(allAttractions.size()).append(" 个\n\n");
        
        sb.append("城市列表：\n");
        List<String> cities = new ArrayList<>(cityAttractions.keySet());
        Collections.sort(cities);
        for (String city : cities) {
            sb.append("• ").append(city).append(" (")
              .append(cityAttractions.get(city).size()).append("个景点)\n");
        }
        
        return sb.toString();
    }
}
