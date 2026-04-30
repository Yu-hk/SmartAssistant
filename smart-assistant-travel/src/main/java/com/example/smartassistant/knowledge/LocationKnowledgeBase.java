package com.example.smartassistant.knowledge;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;

/**
 * 全国省市区坐标知识库
 * 提供全国所有省份、主要城市、区县的坐标查询服务
 */
@Component
public class LocationKnowledgeBase {

    private static final Logger log = LoggerFactory.getLogger(LocationKnowledgeBase.class);

    /**
     * 地点信息
     *
     * @param name      地点名称
     * @param province  所属省份
     * @param type      类型：province/city/district
     * @param latitude  纬度
     * @param longitude 经度
     */
        public record LocationInfo(String name, String province, String type, double latitude, double longitude) {

        @NotNull
        @Override
            public String toString() {
                return String.format("%s (%.4f, %.4f)", name, latitude, longitude);
            }
        }

    // 地名到坐标的映射
    private final Map<String, LocationInfo> locationMap = new HashMap<>();
    
    // 省份列表
    private final Set<String> provinces = new HashSet<>();
    
    // 城市列表（按省份分组）
    private final Map<String, List<String>> citiesByProvince = new HashMap<>();

    @PostConstruct
    public void init() {
        log.info("[LocationKnowledgeBase] 开始初始化全国省市区坐标数据...");
        loadAllLocations();
        log.info("[LocationKnowledgeBase] 初始化完成，共加载 {} 个地点", locationMap.size());
    }

    /**
     * 加载所有地点数据
     */
    private void loadAllLocations() {
        // ========== 直辖市 ==========
        addLocation("北京", "北京", 39.9042, 116.4074);
        addLocation("上海", "上海", 31.2304, 121.4737);
        addLocation("天津", "天津", 39.3434, 117.3616);
        addLocation("重庆", "重庆", 29.4316, 106.9123);

        // ========== 河北省 ==========
        addProvince("河北");
        addLocation("石家庄", "河北", 38.0428, 114.5149);
        addLocation("唐山", "河北", 39.6243, 118.1944);
        addLocation("秦皇岛", "河北", 39.9350, 119.6004);
        addLocation("邯郸", "河北", 36.6256, 114.5391);
        addLocation("邢台", "河北", 37.0682, 114.5047);
        addLocation("保定", "河北", 38.8738, 115.4645);
        addLocation("张家口", "河北", 40.7684, 114.8869);
        addLocation("承德", "河北", 40.9515, 117.9627);
        addLocation("沧州", "河北", 38.3037, 116.8388);
        addLocation("廊坊", "河北", 39.5384, 116.7140);
        addLocation("衡水", "河北", 37.7349, 115.6704);

        // ========== 河南省 ==========
        addProvince("河南");
        addLocation("郑州", "河南", 34.7466, 113.6253);
        addLocation("开封", "河南", 34.7970, 114.3074);
        addLocation("洛阳", "河南", 34.6197, 112.4540);
        addLocation("平顶山", "河南", 33.7350, 113.1923);
        addLocation("安阳", "河南", 36.0985, 114.3923);
        addLocation("鹤壁", "河南", 35.7482, 114.2973);
        addLocation("新乡", "河南", 35.3026, 113.9268);
        addLocation("焦作", "河南", 35.2158, 113.2419);
        addLocation("濮阳", "河南", 35.7609, 115.0290);
        addLocation("许昌", "河南", 34.0357, 113.8263);
        addLocation("漯河", "河南", 33.5809, 114.0264);
        addLocation("三门峡", "河南", 34.7733, 111.2008);
        addLocation("南阳", "河南", 32.9906, 112.5283);
        addLocation("商丘", "河南", 34.4149, 115.6503);
        addLocation("信阳", "河南", 32.1264, 114.0672);
        addLocation("周口", "河南", 33.6204, 114.6969);
        addLocation("驻马店", "河南", 32.9773, 114.0247);

        // ========== 山东省 ==========
        addProvince("山东");
        addLocation("济南", "山东", 36.6512, 117.1209);
        addLocation("青岛", "山东", 36.0671, 120.3826);
        addLocation("淄博", "山东", 36.8131, 118.0549);
        addLocation("枣庄", "山东", 34.8107, 117.3235);
        addLocation("东营", "山东", 37.4345, 118.6747);
        addLocation("烟台", "山东", 37.4638, 121.4478);
        addLocation("潍坊", "山东", 36.7067, 119.1619);
        addLocation("济宁", "山东", 35.4059, 116.5873);
        addLocation("泰安", "山东", 36.1950, 117.0883);
        addLocation("威海", "山东", 37.5138, 122.1201);
        addLocation("日照", "山东", 35.4164, 119.5268);
        addLocation("临沂", "山东", 35.1042, 118.3564);
        addLocation("德州", "山东", 37.4355, 116.3574);
        addLocation("聊城", "山东", 36.4560, 115.9803);
        addLocation("滨州", "山东", 37.3835, 117.9723);
        addLocation("菏泽", "山东", 35.2338, 115.4810);

        // ========== 山西省 ==========
        addProvince("山西");
        addLocation("太原", "山西", 37.8706, 112.5489);
        addLocation("大同", "山西", 40.0761, 113.2947);
        addLocation("阳泉", "山西", 37.8570, 113.5833);
        addLocation("长治", "山西", 36.2021, 113.1139);
        addLocation("晋城", "山西", 35.4910, 112.8513);
        addLocation("朔州", "山西", 39.3313, 112.4330);
        addLocation("晋中", "山西", 37.6872, 112.7530);
        addLocation("运城", "山西", 35.0228, 111.0070);
        addLocation("忻州", "山西", 38.4168, 112.7337);
        addLocation("临汾", "山西", 36.0880, 111.5190);
        addLocation("吕梁", "山西", 37.5243, 111.1343);

        // ========== 陕西省 ==========
        addProvince("陕西");
        addLocation("西安", "陕西", 34.3416, 108.9398);
        addLocation("铜川", "陕西", 34.8965, 108.9432);
        addLocation("宝鸡", "陕西", 34.3693, 107.2375);
        addLocation("咸阳", "陕西", 34.3294, 108.7088);
        addLocation("渭南", "陕西", 34.5000, 109.5000);
        addLocation("延安", "陕西", 36.5965, 109.4941);
        addLocation("汉中", "陕西", 33.0667, 107.0333);
        addLocation("榆林", "陕西", 38.2833, 109.7333);
        addLocation("安康", "陕西", 32.6903, 109.0293);
        addLocation("商洛", "陕西", 33.8700, 109.9400);

        // ========== 湖南省 ==========
        addProvince("湖南");
        addLocation("长沙", "湖南", 28.2282, 112.9388);
        addLocation("株洲", "湖南", 27.8274, 113.1339);
        addLocation("湘潭", "湖南", 27.8297, 112.9440);
        addLocation("衡阳", "湖南", 26.8968, 112.5853);
        addLocation("邵阳", "湖南", 27.2389, 111.4677);
        addLocation("岳阳", "湖南", 29.3571, 113.1287);
        addLocation("常德", "湖南", 29.0317, 111.6987);
        addLocation("张家界", "湖南", 29.1274, 110.4791);
        addLocation("益阳", "湖南", 28.5538, 112.3550);
        addLocation("郴州", "湖南", 25.7705, 113.0160);
        addLocation("永州", "湖南", 26.4203, 111.6130);
        addLocation("怀化", "湖南", 27.5500, 109.9833);
        addLocation("娄底", "湖南", 27.7281, 111.9943);
        addLocation("湘西", "湖南", 28.3139, 109.7397);

        // ========== 湖北省 ==========
        addProvince("湖北");
        addLocation("武汉", "湖北", 30.5928, 114.3055);
        addLocation("黄石", "湖北", 30.2200, 115.0333);
        addLocation("十堰", "湖北", 32.6500, 110.7833);
        addLocation("宜昌", "湖北", 30.6920, 111.2865);
        addLocation("襄阳", "湖北", 32.0090, 112.1220);
        addLocation("鄂州", "湖北", 30.3965, 114.8903);
        addLocation("荆门", "湖北", 31.0354, 112.1994);
        addLocation("孝感", "湖北", 30.9264, 113.9166);
        addLocation("荆州", "湖北", 30.3352, 112.2392);
        addLocation("黄冈", "湖北", 30.4500, 114.8750);
        addLocation("咸宁", "湖北", 29.8414, 114.3226);
        addLocation("随州", "湖北", 31.7170, 113.3737);
        addLocation("恩施", "湖北", 30.2833, 109.4833);

        // ========== 广东省 ==========
        addProvince("广东");
        addLocation("广州", "广东", 23.1291, 113.2644);
        addLocation("深圳", "广东", 22.5431, 114.0579);
        addLocation("珠海", "广东", 22.2719, 113.5767);
        addLocation("汕头", "广东", 23.3540, 116.6824);
        addLocation("佛山", "广东", 23.0218, 113.1219);
        addLocation("韶关", "广东", 24.8103, 113.5977);
        addLocation("湛江", "广东", 21.2707, 110.3594);
        addLocation("肇庆", "广东", 23.0786, 112.4656);
        addLocation("江门", "广东", 22.5790, 113.0815);
        addLocation("茂名", "广东", 21.6630, 110.9255);
        addLocation("惠州", "广东", 23.1115, 114.4152);
        addLocation("梅州", "广东", 24.2886, 116.1225);
        addLocation("汕尾", "广东", 22.7862, 115.3754);
        addLocation("河源", "广东", 23.7463, 114.6978);
        addLocation("阳江", "广东", 21.8577, 111.9827);
        addLocation("清远", "广东", 23.6818, 113.0561);
        addLocation("东莞", "广东", 23.0205, 113.7518);
        addLocation("中山", "广东", 22.5170, 113.3927);
        addLocation("潮州", "广东", 23.6567, 116.6229);
        addLocation("揭阳", "广东", 23.5437, 116.3665);
        addLocation("云浮", "广东", 22.9150, 112.0445);

        // ========== 广西壮族自治区 ==========
        addProvince("广西");
        addLocation("南宁", "广西", 22.8170, 108.3665);
        addLocation("柳州", "广西", 24.3264, 109.4286);
        addLocation("桂林", "广西", 25.2736, 110.2906);
        addLocation("梧州", "广西", 23.4748, 111.2791);
        addLocation("北海", "广西", 21.4733, 109.1195);
        addLocation("防城港", "广西", 21.6147, 108.3547);
        addLocation("钦州", "广西", 21.9797, 108.6541);
        addLocation("贵港", "广西", 23.0936, 109.5997);
        addLocation("玉林", "广西", 22.6314, 110.1524);
        addLocation("百色", "广西", 23.9000, 106.6167);
        addLocation("贺州", "广西", 24.4167, 111.5667);
        addLocation("河池", "广西", 24.6958, 108.0853);
        addLocation("来宾", "广西", 23.7333, 109.2333);
        addLocation("崇左", "广西", 22.4042, 107.3650);

        // ========== 四川省 ==========
        addProvince("四川");
        addLocation("成都", "四川", 30.5728, 104.0668);
        addLocation("自贡", "四川", 29.3528, 104.7784);
        addLocation("攀枝花", "四川", 26.5804, 101.7183);
        addLocation("泸州", "四川", 28.8718, 105.4420);
        addLocation("德阳", "四川", 31.1270, 104.3986);
        addLocation("绵阳", "四川", 31.4677, 104.6796);
        addLocation("广元", "四川", 32.4333, 105.8333);
        addLocation("遂宁", "四川", 30.5333, 105.5667);
        addLocation("内江", "四川", 29.5833, 105.0667);
        addLocation("乐山", "四川", 29.5833, 103.7667);
        addLocation("南充", "四川", 30.7991, 106.0784);
        addLocation("眉山", "四川", 30.0733, 103.8333);
        addLocation("宜宾", "四川", 28.7667, 104.6333);
        addLocation("广安", "四川", 30.4564, 106.6333);
        addLocation("达州", "四川", 31.2100, 107.5000);
        addLocation("雅安", "四川", 29.9833, 103.0000);
        addLocation("巴中", "四川", 31.8667, 106.7667);
        addLocation("资阳", "四川", 30.1333, 104.6333);

        // ========== 贵州省 ==========
        addProvince("贵州");
        addLocation("贵阳", "贵州", 26.6470, 106.6302);
        addLocation("六盘水", "贵州", 26.5833, 104.8333);
        addLocation("遵义", "贵州", 27.7000, 106.9333);
        addLocation("安顺", "贵州", 26.2456, 105.9333);
        addLocation("毕节", "贵州", 27.3000, 105.2833);
        addLocation("铜仁", "贵州", 27.7333, 109.1833);

        // ========== 云南省 ==========
        addProvince("云南");
        addLocation("昆明", "云南", 25.0389, 102.7183);
        addLocation("曲靖", "云南", 25.5000, 103.8000);
        addLocation("玉溪", "云南", 24.3500, 102.5333);
        addLocation("保山", "云南", 25.1167, 99.1667);
        addLocation("昭通", "云南", 27.3333, 103.7167);
        addLocation("丽江", "云南", 26.8500, 100.2333);
        addLocation("普洱", "云南", 22.7833, 100.9667);

        // ========== 浙江省 ==========
        addProvince("浙江");
        addLocation("杭州", "浙江", 30.2741, 120.1551);
        addLocation("宁波", "浙江", 29.8683, 121.5440);
        addLocation("温州", "浙江", 28.0000, 120.6667);
        addLocation("嘉兴", "浙江", 30.7500, 120.7500);
        addLocation("湖州", "浙江", 30.8667, 120.1000);
        addLocation("绍兴", "浙江", 30.0000, 120.5833);
        addLocation("金华", "浙江", 29.1000, 119.6500);
        addLocation("衢州", "浙江", 28.9667, 118.8667);
        addLocation("舟山", "浙江", 29.9833, 122.2000);
        addLocation("台州", "浙江", 28.6667, 121.4167);
        addLocation("丽水", "浙江", 28.4500, 119.9167);

        // ========== 江苏省 ==========
        addProvince("江苏");
        addLocation("南京", "江苏", 32.0603, 118.7969);
        addLocation("无锡", "江苏", 31.4912, 120.3119);
        addLocation("徐州", "江苏", 34.2044, 117.2844);
        addLocation("常州", "江苏", 31.8122, 119.9692);
        addLocation("苏州", "江苏", 31.2989, 120.5853);
        addLocation("南通", "江苏", 31.9812, 120.8946);
        addLocation("连云港", "江苏", 34.5967, 119.2216);
        addLocation("淮安", "江苏", 33.5886, 119.0214);
        addLocation("盐城", "江苏", 33.3483, 120.1633);
        addLocation("扬州", "江苏", 32.3912, 119.4129);
        addLocation("镇江", "江苏", 32.1887, 119.4258);
        addLocation("泰州", "江苏", 32.4555, 119.9229);
        addLocation("宿迁", "江苏", 33.9633, 118.2750);

        // ========== 安徽省 ==========
        addProvince("安徽");
        addLocation("合肥", "安徽", 31.8206, 117.2272);
        addLocation("芜湖", "安徽", 31.3339, 118.3764);
        addLocation("蚌埠", "安徽", 32.9400, 117.3600);
        addLocation("淮南", "安徽", 32.6475, 117.0183);
        addLocation("马鞍山", "安徽", 31.6700, 118.5000);
        addLocation("淮北", "安徽", 33.9717, 116.7947);
        addLocation("铜陵", "安徽", 30.9456, 117.8114);
        addLocation("安庆", "安徽", 30.5083, 117.0500);
        addLocation("黄山", "安徽", 29.7144, 118.3383);
        addLocation("滁州", "安徽", 32.3172, 118.3158);
        addLocation("阜阳", "安徽", 32.8900, 115.8167);
        addLocation("宿州", "安徽", 33.6333, 116.9833);
        addLocation("六安", "安徽", 31.7500, 116.5000);
        addLocation("亳州", "安徽", 33.8667, 115.7833);
        addLocation("池州", "安徽", 30.6667, 117.4833);
        addLocation("宣城", "安徽", 30.9500, 118.7500);

        // ========== 福建省 ==========
        addProvince("福建");
        addLocation("福州", "福建", 26.0745, 119.2965);
        addLocation("厦门", "福建", 24.4798, 118.0894);
        addLocation("莆田", "福建", 25.4333, 119.0000);
        addLocation("三明", "福建", 26.2633, 117.6350);
        addLocation("泉州", "福建", 24.8740, 118.6757);
        addLocation("漳州", "福建", 24.5133, 117.6467);
        addLocation("南平", "福建", 26.6450, 118.1778);
        addLocation("龙岩", "福建", 25.0917, 117.0167);
        addLocation("宁德", "福建", 26.6667, 119.5333);

        // ========== 江西省 ==========
        addProvince("江西");
        addLocation("南昌", "江西", 28.6829, 115.8579);
        addLocation("景德镇", "江西", 29.2944, 117.2147);
        addLocation("萍乡", "江西", 27.6228, 113.8522);
        addLocation("九江", "江西", 29.7044, 116.0019);
        addLocation("新余", "江西", 27.8167, 114.9333);
        addLocation("鹰潭", "江西", 28.2600, 117.0667);
        addLocation("赣州", "江西", 25.8500, 114.9333);
        addLocation("吉安", "江西", 27.1167, 114.9833);
        addLocation("宜春", "江西", 27.8000, 114.3833);
        addLocation("抚州", "江西", 27.9500, 116.3583);
        addLocation("上饶", "江西", 28.4500, 117.9667);

        // ========== 黑龙江省 ==========
        addProvince("黑龙江");
        addLocation("哈尔滨", "黑龙江", 45.8038, 126.5340);
        addLocation("齐齐哈尔", "黑龙江", 47.3422, 123.9722);
        addLocation("鸡西", "黑龙江", 45.3000, 130.9667);
        addLocation("鹤岗", "黑龙江", 47.3333, 130.3000);
        addLocation("双鸭山", "黑龙江", 46.6500, 131.1667);
        addLocation("大庆", "黑龙江", 46.5900, 125.1033);
        addLocation("伊春", "黑龙江", 47.7272, 128.8992);
        addLocation("佳木斯", "黑龙江", 46.8000, 130.3167);
        addLocation("七台河", "黑龙江", 45.7711, 130.8528);
        addLocation("牡丹江", "黑龙江", 44.5833, 129.6000);
        addLocation("黑河", "黑龙江", 50.2500, 127.5000);
        addLocation("绥化", "黑龙江", 46.6500, 126.9833);

        // ========== 吉林省 ==========
        addProvince("吉林");
        addLocation("长春", "吉林", 43.8171, 125.3235);
        addLocation("吉林", "吉林", 43.8333, 126.5500);
        addLocation("四平", "吉林", 43.1700, 124.3500);
        addLocation("辽源", "吉林", 42.9000, 125.1500);
        addLocation("通化", "吉林", 41.7333, 125.9333);
        addLocation("白山", "吉林", 41.9425, 126.4278);
        addLocation("松原", "吉林", 45.1417, 124.8250);
        addLocation("白城", "吉林", 45.6167, 122.8333);

        // ========== 辽宁省 ==========
        addProvince("辽宁");
        addLocation("沈阳", "辽宁", 41.8057, 123.4328);
        addLocation("大连", "辽宁", 38.9140, 121.6147);
        addLocation("鞍山", "辽宁", 41.1086, 122.9944);
        addLocation("抚顺", "辽宁", 41.8650, 123.9211);
        addLocation("本溪", "辽宁", 41.2861, 123.7661);
        addLocation("丹东", "辽宁", 40.1294, 124.3542);
        addLocation("锦州", "辽宁", 41.1194, 121.1353);
        addLocation("营口", "辽宁", 40.6667, 122.2333);
        addLocation("阜新", "辽宁", 42.0167, 121.6667);
        addLocation("辽阳", "辽宁", 41.2722, 123.1722);
        addLocation("盘锦", "辽宁", 41.1244, 122.0694);
        addLocation("铁岭", "辽宁", 42.2861, 123.8444);
        addLocation("朝阳", "辽宁", 41.5767, 120.4511);
        addLocation("葫芦岛", "辽宁", 40.7556, 120.8569);

        // ========== 甘肃省 ==========
        addProvince("甘肃");
        addLocation("兰州", "甘肃", 36.0611, 103.8343);
        addLocation("嘉峪关", "甘肃", 39.7725, 98.2892);
        addLocation("金昌", "甘肃", 38.5142, 102.1878);
        addLocation("白银", "甘肃", 36.5450, 104.1386);
        addLocation("天水", "甘肃", 34.5808, 105.7247);
        addLocation("武威", "甘肃", 37.9283, 102.6383);
        addLocation("张掖", "甘肃", 38.9250, 100.4500);
        addLocation("平凉", "甘肃", 35.5428, 106.6847);
        addLocation("酒泉", "甘肃", 39.7333, 98.5000);
        addLocation("庆阳", "甘肃", 35.7089, 107.6383);
        addLocation("定西", "甘肃", 35.5833, 104.6167);
        addLocation("陇南", "甘肃", 33.4000, 104.9167);

        // ========== 青海省 ==========
        addProvince("青海");
        addLocation("西宁", "青海", 36.6171, 101.7782);
        addLocation("海东", "青海", 36.5000, 102.1000);

        // ========== 海南省 ==========
        addProvince("海南");
        addLocation("海口", "海南", 20.0444, 110.1999);
        addLocation("三亚", "海南", 18.2528, 109.5117);
        addLocation("三沙", "海南", 16.8333, 112.3333);

        // ========== 台湾省 ==========
        addProvince("台湾");
        addLocation("台北", "台湾", 25.0330, 121.5654);
        addLocation("高雄", "台湾", 22.6273, 120.3014);
        addLocation("台中", "台湾", 24.1477, 120.6736);

        // ========== 内蒙古自治区 ==========
        addProvince("内蒙古");
        addLocation("呼和浩特", "内蒙古", 40.8414, 111.7519);
        addLocation("包头", "内蒙古", 40.6522, 109.8403);
        addLocation("乌海", "内蒙古", 39.6733, 106.8256);
        addLocation("赤峰", "内蒙古", 42.2753, 118.9569);
        addLocation("通辽", "内蒙古", 43.6172, 122.2636);
        addLocation("鄂尔多斯", "内蒙古", 39.6083, 109.7817);
        addLocation("呼伦贝尔", "内蒙古", 49.2117, 119.7653);

        // ========== 新疆维吾尔自治区 ==========
        addProvince("新疆");
        addLocation("乌鲁木齐", "新疆", 43.8256, 87.6168);
        addLocation("克拉玛依", "新疆", 45.6000, 84.8833);
        addLocation("吐鲁番", "新疆", 42.9500, 89.1833);
        addLocation("哈密", "新疆", 42.8333, 93.5167);

        // ========== 西藏自治区 ==========
        addProvince("西藏");
        addLocation("拉萨", "西藏", 29.6500, 91.1409);
        addLocation("日喀则", "西藏", 29.2667, 88.8833);

        // ========== 宁夏回族自治区 ==========
        addProvince("宁夏");
        addLocation("银川", "宁夏", 38.4872, 106.2309);
        addLocation("石嘴山", "宁夏", 39.0167, 106.3833);
        addLocation("吴忠", "宁夏", 37.9833, 106.2000);
    }

    /**
     * 添加省份
     */
    private void addProvince(String provinceName) {
        provinces.add(provinceName);
        citiesByProvince.putIfAbsent(provinceName, new ArrayList<>());
    }

    /**
     * 添加地点
     */
    private void addLocation(String name, String province, double latitude, double longitude) {
        LocationInfo info = new LocationInfo(name, province, "city", latitude, longitude);
        locationMap.put(name, info);
        
        // 添加到省份的城市列表
        citiesByProvince.computeIfAbsent(province, k -> new ArrayList<>()).add(name);
    }

    /**
     * 根据地点名称查询坐标
     *
     * @param locationName 地点名称（支持省、市、区县）
     * @return 坐标数组 [纬度, 经度]，未找到返回 null
     */
    public double[] getCoordinates(String locationName) {
        if (locationName == null || locationName.isEmpty()) {
            return null;
        }

        // 精确匹配
        LocationInfo info = locationMap.get(locationName);
        if (info != null) {
            return new double[]{info.latitude(), info.longitude()};
        }

        // 模糊匹配（包含关系）
        for (Map.Entry<String, LocationInfo> entry : locationMap.entrySet()) {
            if (entry.getKey().contains(locationName) || locationName.contains(entry.getKey())) {
                log.debug("[LocationKnowledgeBase] 模糊匹配: '{}' -> '{}'", locationName, entry.getKey());
                return new double[]{entry.getValue().latitude(), entry.getValue().longitude()};
            }
        }

        return null;
    }

    /**
     * 获取地点信息
     *
     * @param locationName 地点名称
     * @return 地点信息对象，未找到返回 null
     */
    public LocationInfo getLocationInfo(String locationName) {
        if (locationName == null || locationName.isEmpty()) {
            return null;
        }

        // 精确匹配
        LocationInfo info = locationMap.get(locationName);
        if (info != null) {
            return info;
        }

        // 模糊匹配
        for (Map.Entry<String, LocationInfo> entry : locationMap.entrySet()) {
            if (entry.getKey().contains(locationName) || locationName.contains(entry.getKey())) {
                return entry.getValue();
            }
        }

        return null;
    }

    /**
     * 获取所有省份列表
     *
     * @return 省份名称集合
     */
    public Set<String> getAllProvinces() {
        return Collections.unmodifiableSet(provinces);
    }

    /**
     * 获取指定省份的所有城市
     *
     * @param province 省份名称
     * @return 城市名称列表
     */
    public List<String> getCitiesByProvince(String province) {
        return citiesByProvince.getOrDefault(province, Collections.emptyList());
    }

    /**
     * 获取所有城市列表
     *
     * @return 城市名称集合
     */
    public Set<String> getAllCities() {
        Set<String> cities = new HashSet<>();
        for (LocationInfo info : locationMap.values()) {
            if ("city".equals(info.type())) {
                cities.add(info.name());
            }
        }
        return cities;
    }

    /**
     * 格式化坐标输出
     *
     * @param locationName 地点名称
     * @return 格式化的坐标信息字符串
     */
    public String formatLocationInfo(String locationName) {
        LocationInfo info = getLocationInfo(locationName);
        if (info == null) {
            return "未找到地点 '" + locationName + "' 的信息";
        }

        return "📍 【" + info.name() + "】\n" +
                "省份：" + info.province() + "\n" +
                "类型：" + ("province".equals(info.type()) ? "省份" : "城市") + "\n" +
                "坐标：" + String.format("%.4f, %.4f", info.latitude(), info.longitude()) + "\n" +
                "坐标格式：" + String.format("%.4f,%.4f", info.latitude(), info.longitude());
    }

    /**
     * 获取统计信息
     *
     * @return 统计信息字符串
     */
    public String getStatistics() {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 【全国省市区坐标知识库统计】\n\n");
        sb.append("省份数量：").append(provinces.size()).append("\n");
        sb.append("城市数量：").append(getAllCities().size()).append("\n");
        sb.append("总地点数：").append(locationMap.size()).append("\n\n");
        
        sb.append("覆盖范围：\n");
        List<String> sortedProvinces = new ArrayList<>(provinces);
        Collections.sort(sortedProvinces);
        for (int i = 0; i < sortedProvinces.size(); i++) {
            String province = sortedProvinces.get(i);
            int cityCount = citiesByProvince.getOrDefault(province, Collections.emptyList()).size();
            sb.append("• ").append(province).append(" (").append(cityCount).append("个城市)");
            if ((i + 1) % 3 == 0 || i == sortedProvinces.size() - 1) {
                sb.append("\n");
            } else {
                sb.append(" | ");
            }
        }

        return sb.toString();
    }
}
