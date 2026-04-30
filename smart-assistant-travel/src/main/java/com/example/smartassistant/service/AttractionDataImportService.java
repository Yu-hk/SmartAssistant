package com.example.smartassistant.service;

import com.example.smartassistant.entity.TouristAttraction;
import com.example.smartassistant.mapper.TouristAttractionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

/**
 * 景点数据导入服务
 * 提供批量导入景点数据的功能
 */
@Service
public class AttractionDataImportService {

    private static final Logger log = LoggerFactory.getLogger(AttractionDataImportService.class);

    private final TouristAttractionMapper mapper;

    public AttractionDataImportService(TouristAttractionMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 导入扩展的景点数据集（100+景点）
     */
    @Transactional
    public int importExtendedDataset() {
        log.info("[DataImport] 开始导入扩展景点数据集...");
        
        List<TouristAttraction> attractions = createExtendedDataset();
        int imported = 0;

        for (TouristAttraction attraction : attractions) {
            // 检查是否已存在
            var existing = mapper.findByNameAndCity(attraction.getName(), attraction.getCity());
            if (existing == null) {
                mapper.insert(attraction);
                imported++;
            }
        }

        log.info("[DataImport] 导入完成，新增 {} 个景点", imported);
        return imported;
    }

    /**
     * 创建扩展数据集（100+景点）
     */
    private List<TouristAttraction> createExtendedDataset() {
        return Arrays.asList(
            // ========== 北京 (补充) ==========
            createAttraction("圆明园", "北京", "北京", "清代大型皇家园林，被誉为'万园之园'", 
                "5A", 25.0, "07:00-19:00", 180,
                Arrays.asList("历史", "园林", "文化"),
                Arrays.asList("大水法遗址", "西洋楼", "福海"),
                40.0037, 116.3050),

            createAttraction("雍和宫", "北京", "北京", "北京市内最大的藏传佛教寺院",
                "4A", 25.0, "09:00-16:30", 90,
                Arrays.asList("佛教", "历史", "文化"),
                Arrays.asList("大雄宝殿", "万福阁", "五百罗汉山"),
                39.9490, 116.4170),

            // ========== 上海 (补充) ==========
            createAttraction("上海迪士尼乐园", "上海", "上海", "中国大陆首座迪士尼主题乐园",
                "5A", 399.0, "08:00-21:30", 480,
                Arrays.asList("主题乐园", "亲子", "娱乐"),
                Arrays.asList("奇幻童话城堡", "创极速光轮", "加勒比海盗"),
                31.1441, 121.6601),

            createAttraction("上海野生动物园", "上海", "上海", "国家级野生动物园",
                "5A", 130.0, "09:00-17:00", 300,
                Arrays.asList("动物", "自然", "亲子"),
                Arrays.asList("车入区", "步行区", "动物表演"),
                31.0560, 121.7180),

            // ========== 成都 (补充) ==========
            createAttraction("青城山", "成都", "四川", "中国道教名山之一，素有'青城天下幽'之美誉",
                "5A", 80.0, "08:00-17:00", 300,
                Arrays.asList("自然", "道教", "徒步"),
                Arrays.asList("上清宫", "建福宫", "月城湖"),
                30.9020, 103.5730),

            createAttraction("西岭雪山", "成都", "四川", "成都第一高峰，终年积雪",
                "5A", 120.0, "08:00-17:30", 360,
                Arrays.asList("自然", "雪山", "滑雪"),
                Arrays.asList("日月坪", "阴阳界", "滑雪场"),
                30.6700, 103.1700),

            // ========== 西安 (补充) ==========
            createAttraction("华清宫", "西安", "陕西", "唐代皇家园林，因唐玄宗和杨贵妃的爱情故事而闻名",
                "5A", 120.0, "07:00-18:00", 180,
                Arrays.asList("历史", "文化", "温泉"),
                Arrays.asList("贵妃池", "五间厅", "兵谏亭"),
                34.3630, 109.2140),

            createAttraction("法门寺", "西安", "陕西", "安置释迦牟尼佛指骨舍利的著名寺院",
                "5A", 100.0, "08:00-18:00", 180,
                Arrays.asList("佛教", "历史", "文化"),
                Arrays.asList("真身宝塔", "合十舍利塔", "地宫"),
                34.4340, 107.9080),

            // ========== 杭州 (补充) ==========
            createAttraction("千岛湖", "杭州", "浙江", "人工湖泊，拥有1078个岛屿",
                "5A", 150.0, "08:00-17:00", 360,
                Arrays.asList("自然", "湖泊", "度假"),
                Arrays.asList("中心湖区", "东南湖区", "梅峰岛"),
                29.6070, 119.0380),

            createAttraction("乌镇", "杭州", "浙江", "江南水乡古镇，世界互联网大会永久会址",
                "5A", 150.0, "全天开放", 240,
                Arrays.asList("古镇", "历史", "文化"),
                Arrays.asList("东栅", "西栅", "木心美术馆"),
                30.7500, 120.4800),

            // ========== 广州 (补充) ==========
            createAttraction("白云山", "广州", "广东", "南粤名山之一，羊城第一秀",
                "5A", 5.0, "06:00-22:00", 180,
                Arrays.asList("自然", "徒步", "免费"),
                Arrays.asList("摩星岭", "鸣春谷", "云台花园"),
                23.1780, 113.3000),

            createAttraction("沙面岛", "广州", "广东", "曾经的租界区，欧式建筑群",
                "4A", 0.0, "全天开放", 120,
                Arrays.asList("历史", "建筑", "免费"),
                Arrays.asList("欧式建筑", "教堂", "咖啡馆"),
                23.1070, 113.2440),

            // ========== 深圳 (补充) ==========
            createAttraction("欢乐谷", "深圳", "广东", "大型现代化主题公园",
                "5A", 230.0, "09:30-22:00", 360,
                Arrays.asList("主题乐园", "娱乐", "亲子"),
                Arrays.asList("过山车", "水上公园", "魔幻城堡"),
                22.5470, 113.9720),

            createAttraction("大梅沙", "深圳", "广东", "深圳最长的海滩",
                "4A", 0.0, "全天开放", 180,
                Arrays.asList("海滩", "免费", "度假"),
                Arrays.asList("沙滩", "海滨栈道", "日出观景"),
                22.5940, 114.3080),

            // ========== 重庆 (补充) ==========
            createAttraction("武隆天生三桥", "重庆", "重庆", "世界自然遗产，喀斯特地貌奇观",
                "5A", 125.0, "08:00-17:00", 240,
                Arrays.asList("自然", "世界遗产", "地质"),
                Arrays.asList("天龙桥", "青龙桥", "黑龙桥"),
                29.4250, 107.7950),

            createAttraction("大足石刻", "重庆", "重庆", "世界文化遗产，唐宋时期石刻艺术",
                "5A", 115.0, "08:30-16:30", 180,
                Arrays.asList("历史", "佛教", "世界遗产"),
                Arrays.asList("宝顶山", "北山", "南山"),
                29.7000, 105.7200),

            // ========== 南京 (补充) ==========
            createAttraction("明孝陵", "南京", "江苏", "明朝开国皇帝朱元璋的陵墓",
                "5A", 70.0, "06:30-18:00", 120,
                Arrays.asList("历史", "文化", "世界遗产"),
                Arrays.asList("神道", "方城明楼", "宝顶"),
                32.0520, 118.8570),

            createAttraction("总统府", "南京", "江苏", "中国近代史重要遗址",
                "5A", 40.0, "08:00-18:00", 120,
                Arrays.asList("历史", "文化", "博物馆"),
                Arrays.asList("子超楼", "煦园", "史料陈列馆"),
                32.0440, 118.7950),

            // ========== 武汉 (补充) ==========
            createAttraction("武汉大学", "武汉", "湖北", "中国最美大学之一，樱花胜地",
                "4A", 0.0, "全天开放", 120,
                Arrays.asList("校园", "樱花", "免费"),
                Arrays.asList("樱花大道", "老斋舍", "珞珈山"),
                30.5430, 114.3650),

            createAttraction("湖北省博物馆", "武汉", "湖北", "国家一级博物馆，馆藏丰富",
                "5A", 0.0, "09:00-17:00", 120,
                Arrays.asList("博物馆", "历史", "免费"),
                Arrays.asList("曾侯乙编钟", "越王勾践剑", "郧县人头骨"),
                30.5640, 114.3650),

            // ========== 厦门 (补充) ==========
            createAttraction("厦门大学", "厦门", "福建", "中国最美大学之一",
                "4A", 0.0, "全天开放", 90,
                Arrays.asList("校园", "建筑", "免费"),
                Arrays.asList("芙蓉隧道", "上弦场", "嘉庚建筑"),
                24.4390, 118.0920),

            createAttraction("集美学村", "厦门", "福建", "陈嘉庚先生创办的学村",
                "4A", 0.0, "全天开放", 120,
                Arrays.asList("历史", "建筑", "免费"),
                Arrays.asList("龙舟池", "鳌园", "嘉庚纪念馆"),
                24.5730, 118.0960),

            // ========== 三亚 (补充) ==========
            createAttraction("天涯海角", "三亚", "海南", "海南标志性景点",
                "5A", 81.0, "08:00-18:00", 120,
                Arrays.asList("海滩", "地标", "文化"),
                Arrays.asList("天涯石", "海角石", "南天一柱"),
                18.2920, 109.3670),

            createAttraction("蜈支洲岛", "三亚", "海南", "中国的马尔代夫",
                "5A", 144.0, "08:00-17:30", 360,
                Arrays.asList("海岛", "潜水", "度假"),
                Arrays.asList("情人谷", "观日岩", "海底世界"),
                18.3200, 109.7200),

            // ========== 丽江 (补充) ==========
            createAttraction("束河古镇", "丽江", "云南", "茶马古道上的重要集镇",
                "4A", 40.0, "全天开放", 180,
                Arrays.asList("古镇", "历史", "文化"),
                Arrays.asList("四方街", "九鼎龙潭", "茶马古道博物馆"),
                26.9200, 100.2100),

            createAttraction("泸沽湖", "丽江", "云南", "高原明珠，摩梭人聚居地",
                "5A", 70.0, "全天开放", 480,
                Arrays.asList("湖泊", "自然", "文化"),
                Arrays.asList("里格半岛", "走婚桥", "猪槽船"),
                27.7200, 100.7500),

            // ========== 桂林 (补充) ==========
            createAttraction("阳朔西街", "桂林", "广西", "中国最具异国情调的街道",
                "4A", 0.0, "全天开放", 120,
                Arrays.asList("古镇", "美食", "免费"),
                Arrays.asList("酒吧街", "特色小店", "夜景"),
                24.7750, 110.4900),

            createAttraction("龙脊梯田", "桂林", "广西", "壮族人民创造的农业奇迹",
                "4A", 80.0, "全天开放", 240,
                Arrays.asList("自然", "梯田", "摄影"),
                Arrays.asList("金坑大寨", "平安寨", "七星伴月"),
                25.7500, 110.1200),

            // ========== 长沙 (补充) ==========
            createAttraction("岳麓山", "长沙", "湖南", "南岳衡山七十二峰之一",
                "5A", 0.0, "全天开放", 180,
                Arrays.asList("自然", "文化", "免费"),
                Arrays.asList("爱晚亭", "岳麓书院", "橘子洲"),
                28.1860, 112.9380),

            createAttraction("橘子洲", "长沙", "湖南", "湘江中的小岛，毛泽东青年艺术雕塑所在地",
                "5A", 0.0, "07:00-22:00", 120,
                Arrays.asList("文化", "历史", "免费"),
                Arrays.asList("青年毛泽东雕像", "问天台", "橘洲公园"),
                28.1860, 112.9620),

            // ========== 青岛 (补充) ==========
            createAttraction("崂山", "青岛", "山东", "海上第一名山，道教圣地",
                "5A", 90.0, "06:00-18:00", 300,
                Arrays.asList("自然", "道教", "徒步"),
                Arrays.asList("太清宫", "巨峰", "仰口"),
                36.1600, 120.6200),

            createAttraction("栈桥", "青岛", "山东", "青岛标志性建筑",
                "4A", 0.0, "全天开放", 60,
                Arrays.asList("地标", "海滩", "免费"),
                Arrays.asList("回澜阁", "海滨风光", "海鸥"),
                36.0600, 120.3200),

            // ========== 大连 (补充) ==========
            createAttraction("老虎滩海洋公园", "大连", "辽宁", "现代化海洋主题公园",
                "5A", 220.0, "08:00-17:00", 300,
                Arrays.asList("海洋", "亲子", "娱乐"),
                Arrays.asList("极地馆", "珊瑚馆", "鸟语林"),
                38.8800, 121.6800),

            createAttraction("星海广场", "大连", "辽宁", "亚洲最大的城市广场",
                "4A", 0.0, "全天开放", 90,
                Arrays.asList("地标", "广场", "免费"),
                Arrays.asList("百年城雕", "音乐喷泉", "海景"),
                38.8800, 121.5800),

            // ========== 哈尔滨 (补充) ==========
            createAttraction("太阳岛", "哈尔滨", "黑龙江", "松花江中的沙丘岛",
                "5A", 30.0, "08:00-17:00", 240,
                Arrays.asList("自然", "冰雪", "度假"),
                Arrays.asList("雪博会", "俄罗斯风情小镇", "松鼠岛"),
                45.7900, 126.5900),

            createAttraction("中央大街", "哈尔滨", "黑龙江", "百年老街，欧式建筑风格",
                "4A", 0.0, "全天开放", 120,
                Arrays.asList("历史", "建筑", "免费"),
                Arrays.asList("面包石路", "欧式建筑", "马迭尔冰棍"),
                45.7700, 126.6200),

            // ========== 昆明 (补充) ==========
            createAttraction("石林", "昆明", "云南", "世界自然遗产，喀斯特地貌",
                "5A", 130.0, "07:30-18:00", 240,
                Arrays.asList("自然", "世界遗产", "地质"),
                Arrays.asList("大石林", "小石林", "乃古石林"),
                24.8200, 103.3200),

            createAttraction("滇池", "昆明", "云南", "云南最大的淡水湖",
                "4A", 0.0, "全天开放", 120,
                Arrays.asList("湖泊", "自然", "免费"),
                Arrays.asList("海埂大坝", "西山", "红嘴鸥"),
                24.9800, 102.6600),

            // ========== 贵阳 (补充) ==========
            createAttraction("黄果树瀑布", "贵阳", "贵州", "中国第一大瀑布",
                "5A", 160.0, "07:00-18:00", 240,
                Arrays.asList("自然", "瀑布", "地质"),
                Arrays.asList("大瀑布", "天星桥", "陡坡塘"),
                25.9900, 105.6700),

            createAttraction("青岩古镇", "贵阳", "贵州", "明清军事古镇",
                "5A", 10.0, "08:00-18:00", 180,
                Arrays.asList("古镇", "历史", "文化"),
                Arrays.asList("古城墙", "状元府", "背街"),
                26.3400, 106.6900),

            // ========== 兰州 (补充) ==========
            createAttraction("黄河铁桥", "兰州", "甘肃", "黄河第一桥",
                "4A", 0.0, "全天开放", 60,
                Arrays.asList("历史", "地标", "免费"),
                Arrays.asList("中山桥", "黄河风光", "白塔山"),
                36.0600, 103.8200),

            createAttraction("敦煌莫高窟", "兰州", "甘肃", "世界文化遗产，佛教艺术宝库",
                "5A", 200.0, "08:00-18:00", 240,
                Arrays.asList("历史", "佛教", "世界遗产"),
                Arrays.asList("壁画", "彩塑", "藏经洞"),
                40.0400, 94.8000)
        );
    }

    /**
     * 创建景点对象辅助方法
     */
    private TouristAttraction createAttraction(String name, String city, String province,
                                               String description, String level, Double ticketPrice,
                                               String openTime, Integer suggestDuration,
                                               List<String> tags, List<String> highlights,
                                               Double latitude, Double longitude) {
        TouristAttraction attraction = new TouristAttraction();
        attraction.setName(name);
        attraction.setCity(city);
        attraction.setProvince(province);
        attraction.setDescription(description);
        attraction.setLevel(level);
        attraction.setTicketPrice(ticketPrice);
        attraction.setOpenTime(openTime);
        attraction.setSuggestDuration(suggestDuration);
        attraction.setTags(tags);
        attraction.setHighlights(highlights);
        attraction.setLatitude(latitude);
        attraction.setLongitude(longitude);
        return attraction;
    }

    /**
     * 获取数据统计信息
     */
    public String getStatistics() {
        long total = mapper.selectCount(null);
        var cities = mapper.findAllCities();
        var provinces = mapper.findAllProvinces();

        StringBuilder sb = new StringBuilder();
        sb.append("📊 【数据库景点统计】\n\n");
        sb.append("景点总数：").append(total).append("\n");
        sb.append("覆盖城市：").append(cities.size()).append(" 个\n");
        sb.append("覆盖省份：").append(provinces.size()).append(" 个\n\n");

        sb.append("城市分布（TOP 10）：\n");
        var cityStats = mapper.countByCity();
        int count = Math.min(10, cityStats.size());
        for (int i = 0; i < count; i++) {
            var stat = cityStats.get(i);
            sb.append("• ").append(stat.get("city")).append(": ").append(stat.get("count")).append(" 个景点\n");
        }

        return sb.toString();
    }

    /**
     * 清空所有景点数据
     */
    @Transactional
    public void clearAllData() {
        log.warn("[DataImport] 清空所有景点数据...");
        mapper.delete(null);
        log.info("[DataImport] 数据已清空");
    }
}
