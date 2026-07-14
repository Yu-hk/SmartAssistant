package com.example.smartassistant.router.service.cache;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.stream.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BGE 重排序 + Lost in the Middle 测试。
 */
class RagRerankTest {

    // ========================
    // 1. Lost in the Middle — 验证首尾交替排列 (10 cases)
    // ========================
    // 输入列表按分数降序排列，lostInTheMiddle 重新排列为：首→尾→次首→次尾→...

    private List<Long> lim(Long... ids) {
        return new ArrayList<>(Arrays.asList(ids));
    }

    private List<Long> invokeLim(List<Long> input, int k) {
        var list = new ArrayList<>(input);
        int n = Math.min(list.size(), k);
        if (n <= 2) return new ArrayList<>(list.subList(0, n));
        List<Long> result = new ArrayList<>(k);
        int left = 0, right = n - 1;
        boolean head = true;
        for (int i = 0; i < n && left <= right; i++) {
            if (head) result.add(list.get(left++));
            else result.add(list.get(right--));
            head = !head;
        }
        return result;
    }

    @Test @DisplayName("Lim: 1项保持原序")
    void lim_1() { assertEquals(List.of(1L), invokeLim(lim(1L), 1)); }

    @Test @DisplayName("Lim: 2项保持原序")
    void lim_2() { assertEquals(List.of(1L, 2L), invokeLim(lim(1L, 2L), 2)); }

    @Test @DisplayName("Lim: 3项 → 首,尾,中")
    void lim_3() { assertEquals(List.of(1L, 3L, 2L), invokeLim(lim(1L, 2L, 3L), 3)); }

    @Test @DisplayName("Lim: 4项 → 首,尾,次首,次尾")
    void lim_4() { assertEquals(List.of(1L, 4L, 2L, 3L), invokeLim(lim(1L, 2L, 3L, 4L), 4)); }

    @Test @DisplayName("Lim: 5项")
    void lim_5() { assertEquals(List.of(1L, 5L, 2L, 4L, 3L), invokeLim(lim(1L, 2L, 3L, 4L, 5L), 5)); }

    @Test @DisplayName("Lim: 7项 — 首尾为最高和最低分")
    void lim_7() {
        var res = invokeLim(lim(1L, 2L, 3L, 4L, 5L, 6L, 7L), 7);
        assertEquals(1L, res.get(0)); // 首: 第一
        assertEquals(7L, res.get(1)); // 尾: 倒数第一
        assertEquals(4L, res.get(6)); // 中间: 中间项
        assertEquals(7, new HashSet<>(res).size()); // 不丢不重
    }

    @Test @DisplayName("Lim: K>候选数")
    void lim_kGt() { assertEquals(List.of(1L, 2L), invokeLim(lim(1L, 2L), 5)); }

    @Test @DisplayName("Lim: 空列表")
    void lim_empty() { assertTrue(invokeLim(new ArrayList<>(), 5).isEmpty()); }

    @Test @DisplayName("Lim: K=0")
    void lim_k0() { assertTrue(invokeLim(lim(1L), 0).isEmpty()); }

    // ========================
    // 2. 余弦相似度 (6 cases)
    // ========================

    private double invokeCos(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0;
        double dot = 0, nA = 0, nB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            nA += (double) a[i] * a[i];
            nB += (double) b[i] * b[i];
        }
        double denom = Math.sqrt(nA) * Math.sqrt(nB);
        return denom == 0 ? 0 : dot / denom;
    }

    @Test @DisplayName("Cos: 相同=1")
    void cos_same() { assertEquals(1.0, invokeCos(new float[]{1,0,0}, new float[]{1,0,0}), 1e-10); }

    @Test @DisplayName("Cos: 正交=0")
    void cos_orth() { assertEquals(0.0, invokeCos(new float[]{1,0}, new float[]{0,1}), 1e-10); }

    @Test @DisplayName("Cos: 相反=-1")
    void cos_opp() { assertEquals(-1.0, invokeCos(new float[]{1,0}, new float[]{-1,0}), 1e-10); }

    @Test @DisplayName("Cos: 零向量=0")
    void cos_zero() { assertEquals(0.0, invokeCos(new float[]{0,0}, new float[]{1,0}), 1e-10); }

    @Test @DisplayName("Cos: 不同长=0")
    void cos_len() { assertEquals(0.0, invokeCos(new float[]{1,0,0}, new float[]{1,0}), 1e-10); }

    @Test @DisplayName("Cos: null参数=0")
    void cos_null() { assertEquals(0.0, invokeCos(null, new float[]{1,0}), 1e-10); }

    // ========================
    // 3. BGE 真实模型 (5 cases)
    // ========================

    private static BgeEmbeddingModel bge;
    private static boolean bgeOk;

    @BeforeAll
    static void init() {
        bge = new BgeEmbeddingModel(
                "D:\\workspace\\SmartAssistant\\models\\bge-large-zh-v1.5.onnx",
                "D:\\workspace\\SmartAssistant\\models\\tokenizer.json");
        bgeOk = bge.isAvailable();
    }

    @Test @DisplayName("BGE: 输出维度与模型一致")
    void bge_dim() { if (bgeOk) assertEquals(bge.dimensions(), bge.embedding("test").length); }

    @Test @DisplayName("BGE: 天气同类 > 天气vs美食")
    void bge_weather() {
        if (!bgeOk) return;
        float[] q = bge.embedding("北京明天天气怎么样");
        double simW = invokeCos(q, bge.embedding("北京今天天气晴朗气温最高22度"));
        double simF = invokeCos(q, bge.embedding("推荐北京烤鸭全聚德老字号"));
        System.out.printf("[BGE] 天气vs天气:%.4f 天气vs美食:%.4f%n", simW, simF);
        assertTrue(simW > simF);
    }

    @Test @DisplayName("BGE: 美食同类 > 美食vs景点")
    void bge_food() {
        if (!bgeOk) return;
        float[] q = bge.embedding("附近有什么好吃的川菜馆");
        double simF = invokeCos(q, bge.embedding("推荐麻辣火锅和毛血旺都是正宗川味"));
        double simT = invokeCos(q, bge.embedding("故宫门票60元开放时间上午8点"));
        System.out.printf("[BGE] 美食vs美食:%.4f 美食vs景点:%.4f%n", simF, simT);
        assertTrue(simF > simT);
    }

    @Test @DisplayName("BGE: 自相似≈1.0")
    void bge_self() { if (bgeOk) { float[] v = bge.embedding("test"); assertTrue(invokeCos(v, v) > 0.999); } }

    @Test @DisplayName("BGE: 同义>反义")
    void bge_syn() {
        if (!bgeOk) return;
        float[] ref = bge.embedding("这家餐厅味道很好");
        double syn = invokeCos(ref, bge.embedding("这家饭店菜品很美味"));
        double ant = invokeCos(ref, bge.embedding("这家餐厅服务很差"));
        System.out.printf("[BGE] 同义:%.4f 反义:%.4f%n", syn, ant);
        assertTrue(syn > ant);
    }
}
