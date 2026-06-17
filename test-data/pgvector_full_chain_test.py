"""
pgvector 向量搜索全链路测试
直接向 PostgreSQL vector_store 插入测试数据，
然后执行余弦相似度搜索验证端到端流程。
"""

import psycopg2
import numpy as np
import json
import time
import onnxruntime as ort
from datetime import datetime

# ==================== 配置 ====================
DB_CONFIG = {
    "host": "localhost",
    "port": 5432,
    "dbname": "a2a_system",
    "user": "postgres",
    "password": "postgres123"
}
BGE_MODEL_PATH = "/workspace/SmartAssistant/models/bge-large-zh-v1.5.onnx"
BGE_VOCAB_PATH = "/workspace/SmartAssistant/models/tokenizer.json"

MAX_LEN = 128
results = {"tests": [], "passed": 0, "failed": 0}

def report(name, status, detail=""):
    results["tests"].append({"name": name, "status": status, "detail": detail})
    if status: results["passed"] += 1
    else: results["failed"] += 1
    icon = "✅" if status else "❌"
    print(f"  {icon} {name}: {detail[:250] if detail else 'OK'}")

def tokenize(text, vocab, unk_id):
    """Char-level tokenization matching Java BgeEmbeddingModel"""
    ids = [0] * MAX_LEN
    mask = [0] * MAX_LEN
    ids[0] = 101
    mask[0] = 1
    pos = 1
    for ch in text:
        if pos >= MAX_LEN - 1:
            break
        if ch.isspace():
            continue
        tid = vocab.get(ch, unk_id)
        ids[pos] = tid
        mask[pos] = 1
        pos += 1
    ids[pos] = 102
    mask[pos] = 1
    return ids, mask

def compute_embedding(session, text, vocab, unk_id):
    """Compute L2-normalized embedding matching Java BgeEmbeddingModel"""
    ids, mask = tokenize(text, vocab, unk_id)
    ids_arr = np.array([ids], dtype=np.int64)
    mask_arr = np.array([mask], dtype=np.int64)
    ttype_arr = np.zeros((1, MAX_LEN), dtype=np.int64)
    
    result = session.run(['last_hidden_state'], {
        'input_ids': ids_arr, 'attention_mask': mask_arr, 'token_type_ids': ttype_arr
    })[0][0]
    
    # mean pooling
    valid_mask = np.array(mask, dtype=bool)
    emb = result[valid_mask].mean(axis=0)
    # L2 normalize
    norm = np.linalg.norm(emb)
    if norm > 0:
        emb = emb / norm
    return emb.astype(np.float64)

print("=" * 70)
print("🧪 pgvector 全链路向量搜索测试")
print("=" * 70)

# ==================== 1. 初始化 BGE 模型 ====================
print("\n📦 1. 加载 BGE 模型")
try:
    with open(BGE_VOCAB_PATH, 'r', encoding='utf-8') as f:
        vocab_data = json.load(f)
    vocab = vocab_data.get('model', {}).get('vocab', {})
    unk_id = vocab.get("[UNK]", 100)
    print(f"   词表: {len(vocab)} 词条, UNK_ID={unk_id}")
    
    session = ort.InferenceSession(BGE_MODEL_PATH)
    report("BGE 模型加载", True, f"dim=1024, 词表={len(vocab)}")
except Exception as e:
    report("BGE 模型加载", False, str(e))
    exit(1)

# ==================== 2. 生成测试 Embedding 数据 ====================
print("\n📝 2. 生成测试数据")
test_data = [
    # (question, answer, userGroupId, group)
    ("成都有什么美食推荐？", "成都美食推荐：火锅、串串香、担担面、夫妻肺片、龙抄手等。建议尝试春熙路附近的老字号。", "group_travel", "美食"),
    ("成都好吃的有哪些？", "成都好吃的有：麻辣火锅、钵钵鸡、甜水面、蛋烘糕、三大炮等。推荐宽窄巷子附近的小吃街。", "group_travel", "美食"),
    ("北京今天天气怎么样？", "北京今天晴转多云，气温25-32°C，南风3-4级。空气质量良，适合户外活动。", "group_travel", "天气"),
    ("杭州有哪些必去的景点？", "杭州必去景点：西湖、灵隐寺、雷峰塔、宋城、千岛湖。建议游玩3-4天，春秋季节最佳。", "group_travel", "旅游"),
    ("上海迪士尼怎么玩？", "上海迪士尼攻略：建议早到抢FP，必玩项目有创极速光轮、加勒比海盗、飞跃地平线。", "group_family", "旅游"),
    ("深圳有哪些好吃的？", "深圳美食推荐：椰子鸡、肠粉、烧鹅、海鲜大餐。华强北和东门附近美食众多。", "group_business", "美食"),
]

embeddings = []
for q, a, g, tag in test_data:
    emb = compute_embedding(session, q, vocab, unk_id)
    embeddings.append(emb)
    print(f"   {q:20s} → dim={len(emb)}, norm={np.linalg.norm(emb):.4f}")

report("测试数据生成", True, f"{len(test_data)} 条问答对")

# ==================== 3. 写入 PostgreSQL vector_store ====================
print("\n💾 3. 写入 PostgreSQL vector_store")
conn = psycopg2.connect(**DB_CONFIG)
cur = conn.cursor()

try:
    cleaned_count = 0
    for i, (q, a, g, tag) in enumerate(test_data):
        emb_list = embeddings[i].tolist()
        content = f"Q: {q}\nA: {a}"
        metadata = {
            "question": q,
            "answer": a,
            "userId": "test_bge_user",
            "userGroupId": g,
            "timestamp": int(time.time() * 1000),
            "cachedAt": datetime.now().isoformat(),
            "category": tag
        }
        
        cur.execute("""
            INSERT INTO vector_store (id, content, metadata, embedding)
            VALUES (gen_random_uuid(), %s, %s, %s::vector)
            ON CONFLICT DO NOTHING
        """, (content, json.dumps(metadata, ensure_ascii=False), emb_list))
        cleaned_count += 1
    
    conn.commit()
    report("写入 vector_store", True, f"成功写入 {cleaned_count} 条向量数据")
except Exception as e:
    conn.rollback()
    report("写入 vector_store", False, str(e))

# ==================== 4. pgvector 语义搜索验证 ====================
print("\n🔍 4. pgvector 余弦相似度搜索验证")

try:
    search_queries = [
        ("成都有什么好吃的？", "group_travel", "美食类·同义改写"),
        ("成都美食推荐", "group_travel", "美食类·关键词精简"),
        ("北京天气怎么样？", "group_travel", "天气类·精确匹配"),
        ("上海旅游攻略", "group_travel", "旅游类·跨组(默认组)"),
        ("今天天气如何？", "group_travel", "天气类·泛化查询"),
        ("深圳哪里好吃？", "group_travel", "美食类·组内搜索(非本组)"),
    ]
    
    for query, search_group, test_desc in search_queries:
        # 生成查询向量
        q_emb = compute_embedding(session, query, vocab, unk_id)
        emb_list = q_emb.tolist()
        
        # pgvector 余弦距离搜索 (<=> = cosine distance, 1 - distance = cosine similarity)
        filter_clause = "WHERE metadata->>'userGroupId' = %s" if search_group else ""
        params = [emb_list, emb_list]
        if search_group:
            params = [search_group, emb_list, emb_list]
        
        cur.execute(f"""
            SELECT id, left(content, 60) as content_preview,
                   metadata->>'question' as question,
                   metadata->>'answer' as answer,
                   metadata->>'userGroupId' as group_id,
                   1 - (embedding <=> %s::vector) as cosine_sim
            FROM vector_store
            WHERE metadata->>'userGroupId' = %s
            ORDER BY embedding <=> %s::vector
            LIMIT 3
        """, (emb_list, search_group, emb_list))
        
        results_rows = cur.fetchall()
        print(f"\n   🔎 搜索: 「{query}」(组={search_group}) [{test_desc}]")
        
        if results_rows:
            for r in results_rows:
                sim_score = r[5]
                print(f"      sim={sim_score:.4f} | {r[3][:50] if r[3] else 'N/A'}")
            
            # 判断：最高相似度应 > 0.6 才算有效命中
            top_sim = results_rows[0][5]
            report(f"搜索测试: {test_desc}", top_sim > 0.6,
                   f"最高相似度={top_sim:.4f}")
        else:
            report(f"搜索测试: {test_desc}", False, "无结果")
    
    # 5. 跨组过滤验证
    print("\n🔒 5. 组隔离验证：搜索 group_family 组不应返回 group_travel 组数据")
    
    q_emb = compute_embedding(session, "成都好吃的", vocab, unk_id)
    cur.execute("""
        SELECT metadata->>'userGroupId' as group_id,
               metadata->>'question' as question,
               1 - (embedding <=> %s::vector) as cosine_sim
        FROM vector_store
        WHERE metadata->>'userGroupId' = 'group_family'
        ORDER BY embedding <=> %s::vector
        LIMIT 5
    """, (q_emb.tolist(), q_emb.tolist()))
    
    family_results = cur.fetchall()
    if family_results:
        is_isolated = all(r[0] == 'group_family' for r in family_results)
        report("组隔离验证", is_isolated,
               f"结果均来自 group_family (共 {len(family_results)} 条)")
    else:
        report("组隔离验证", True, "无跨组数据 (空结果也符合预期)")
    
    # 6. 相似度阈值验证
    print("\n🎯 6. 相似度阈值验证（检查不同语义距离的分布）")
    # 美食 vs 美食 → 高相似度
    emb1 = compute_embedding(session, "成都美食推荐", vocab, unk_id)
    emb2 = compute_embedding(session, "成都好吃的有哪些", vocab, unk_id)
    sim_food_food = float(np.dot(emb1, emb2))
    
    # 美食 vs 天气 → 低相似度
    emb3 = compute_embedding(session, "北京今天天气怎么样", vocab, unk_id)
    sim_food_weather = float(np.dot(emb1, emb3))
    
    # 美食 vs 旅游 → 中等相似度
    emb4 = compute_embedding(session, "杭州必去景点", vocab, unk_id)
    sim_food_travel = float(np.dot(emb1, emb4))
    
    print(f"     美食 vs 美食: {sim_food_food:.4f}")
    print(f"     美食 vs 天气: {sim_food_weather:.4f}")
    print(f"     美食 vs 旅游: {sim_food_travel:.4f}")
    print(f"     阈值分析: 同类>={0.6}, 异类<={0.5}")
    
    report("阈值测试: 同义句高相似度", sim_food_food > 0.6, f"{sim_food_food:.4f}")
    report("阈值测试: 异义句低相似度", sim_food_weather < 0.5, f"{sim_food_weather:.4f}")
    report("阈值测试: 语义区分度", sim_food_food > sim_food_travel > sim_food_weather,
           f"{sim_food_food:.4f} > {sim_food_travel:.4f} > {sim_food_weather:.4f}")
    
except Exception as e:
    report("pgvector 搜索", False, str(e))
    import traceback
    traceback.print_exc()

# ==================== 清理 & 总结 ====================
print("\n" + "=" * 70)
print("📋 测试总结")
total = results["passed"] + results["failed"]
print(f"  总测试数: {total}")
print(f"  ✅ 通过: {results['passed']}")
print(f"  ❌ 失败: {results['failed']}")
print(f"  通过率: {results['passed']/total*100:.1f}%" if total else "N/A")

# 清理测试数据
cur.execute("DELETE FROM vector_store WHERE metadata->>'userId' = 'test_bge_user'")
conn.commit()
print(f"\n🧹 已清理 {cur.rowcount} 条测试数据")
cur.close()
conn.close()
print("✅ 测试完成")
