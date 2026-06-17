"""
BGE 向量搜索综合测试
====================
测试 BgeEmbeddingModel 的向量生成、pgvector 语义搜索、三层缓存联动

测试内容：
1. PostgreSQL pgvector 环境验证
2. Python ONNX Runtime 直接调用 BGE 模型
3. Consumer 服务 BGE 模型加载状态
4. 语义缓存存储与检索（通过 chat API 触发）
5. 向量相似度计算验证
6. Redis 缓存命中状态
"""

import requests
import json
import time
import psycopg2
import numpy as np
import hashlib
import base64
import subprocess
import sys
from datetime import datetime

# ==================== 配置 ====================
CONSUMER_URL = "http://localhost:8082"
GATEWAY_URL = "http://localhost:8081"  # 未启动，仅做参考
DB_CONFIG = {
    "host": "localhost",
    "port": 5432,
    "dbname": "a2a_system",
    "user": "postgres",
    "password": "postgres123"
}
REDIS_CONFIG = {
    "host": "localhost",
    "port": 6379,
    "password": "redis123",
    "db": 0
}

BGE_MODEL_PATH = "/workspace/SmartAssistant/models/bge-large-zh-v1.5.onnx"
BGE_VOCAB_PATH = "/workspace/SmartAssistant/models/tokenizer.json"

results = {
    "timestamp": datetime.now().isoformat(),
    "tests": [],
    "summary": {"passed": 0, "failed": 0, "total": 0}
}

def report(name, status, detail=""):
    """记录测试结果"""
    results["tests"].append({
        "name": name,
        "status": "✅ PASS" if status else "❌ FAIL",
        "detail": detail
    })
    results["summary"]["total"] += 1
    if status:
        results["summary"]["passed"] += 1
    else:
        results["summary"]["failed"] += 1
    icon = "✅" if status else "❌"
    print(f"  {icon} {name}: {detail[:200] if detail else 'OK'}")


# ==================== 测试开始 ====================
print("=" * 70)
print("🧪 BGE 向量搜索综合测试")
print("=" * 70)

# ==================== 1. PostgreSQL pgvector 环境验证 ====================
print("\n" + "=" * 70)
print("📦 1. PostgreSQL pgvector 环境验证")
print("=" * 70)

try:
    conn = psycopg2.connect(**DB_CONFIG)
    cur = conn.cursor()

    # 1.1 pgvector 扩展
    cur.execute("SELECT * FROM pg_extension WHERE extname = 'vector'")
    report("pgvector 扩展已安装", cur.rowcount > 0,
           f"extensions: {cur.rowcount}")

    # 1.2 vector_store 表结构
    cur.execute("""
        SELECT column_name, data_type, udt_name
        FROM information_schema.columns
        WHERE table_name = 'vector_store'
        ORDER BY ordinal_position
    """)
    columns = cur.fetchall()
    if columns:
        cols_detail = "; ".join([f"{c[0]}({c[2]})" for c in columns])
        report("vector_store 表结构", True, cols_detail)

        # 检查 embedding 列类型
        for c in columns:
            if c[0] == 'embedding':
                report(f"embedding 列类型: {c[2]}", True)
                break
    else:
        report("vector_store 表结构", False, "表不存在")

    # 1.3 当前数据量
    cur.execute("SELECT COUNT(*) FROM vector_store")
    count = cur.fetchone()[0]
    report("vector_store 数据量", True, f"{count} 条记录")

    # 1.4 查看所有表
    cur.execute("""
        SELECT table_name, table_schema
        FROM information_schema.tables
        WHERE table_schema = 'public'
        ORDER BY table_name
    """)
    tables = cur.fetchall()
    report("数据库表", True,
           f"共 {len(tables)} 张表: {', '.join([t[0] for t in tables])}")

    cur.close()
    conn.close()
except Exception as e:
    report("PostgreSQL 连接/查询", False, str(e))


# ==================== 2. Python 直接调用 BGE 模型 ====================
print("\n" + "=" * 70)
print("🧠 2. Python ONNX Runtime 直接调用 BGE 模型")
print("=" * 70)

try:
    import onnxruntime as ort
    import json as json_mod

    # 2.1 加载词表
    start = time.time()
    with open(BGE_VOCAB_PATH, 'r', encoding='utf-8') as f:
        vocab_data = json_mod.load(f)
    vocab = vocab_data.get('model', {}).get('vocab', {})
    report("BGE 词表加载", True,
           f"{len(vocab)} 个词条, 耗时 {time.time()-start:.2f}s")
    print(f"     示例词条: {dict(list(vocab.items())[:5])}")

    # 2.2 加载 ONNX 模型
    start = time.time()
    session = ort.InferenceSession(BGE_MODEL_PATH)
    input_names = [i.name for i in session.get_inputs()]
    output_names = [o.name for o in session.get_outputs()]
    input_shape = session.get_inputs()[0].shape
    report("BGE ONNX 模型加载", True,
           f"inputs={input_names}, outputs={output_names}, shape={input_shape}, "
           f"耗时 {time.time()-start:.2f}s")

    # 2.3 生成测试文本的 Embedding
    MAX_LEN = 128
    unk_id = vocab.get("[UNK]", 100)
    # 使用 char-level 分词（匹配 Java BgeEmbeddingModel 逻辑）
    def tokenize(text):
        ids = [0] * MAX_LEN
        mask = [0] * MAX_LEN
        ids[0] = 101  # [CLS]
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
        ids[pos] = 102  # [SEP]
        mask[pos] = 1
        return np.array([ids], dtype=np.int64), np.array([mask], dtype=np.int64)

    test_questions = [
        "成都有什么美食推荐？",
        "成都好吃的有哪些？",
        "北京今天天气怎么样？",
        "帮我查一下杭州的旅游景点",
        "今天适合出门走走吗？",
    ]

    embeddings = {}
    print("\n     生成 Embedding 测试:")
    for q in test_questions:
        start = time.time()
        ids, mask = tokenize(q)
        token_type_ids = np.zeros((1, MAX_LEN), dtype=np.int64)
        result = session.run(output_names, {
            'input_ids': ids,
            'attention_mask': mask,
            'token_type_ids': token_type_ids
        })
        duration = time.time() - start
        # mean pooling + L2 normalize
        emb = result[0][0]
        dim = emb.shape[-1]
        # 应用 attention mask
        valid_mask = mask[0].astype(bool)
        emb_pooled = emb[valid_mask].mean(axis=0)
        # L2 normalize
        norm = np.linalg.norm(emb_pooled)
        if norm > 0:
            emb_pooled = emb_pooled / norm
        embeddings[q] = emb_pooled
        print(f"     [{q[:20]:20s}] dim={dim}, 耗时 {duration*1000:.0f}ms")

    report("BGE Embedding 生成", True,
           f"成功生成 {len(embeddings)} 个文本的向量, 维度={dim}")

    # 2.4 余弦相似度计算验证
    print("\n     相似度矩阵:")
    questions_list = test_questions
    for i in range(len(questions_list)):
        for j in range(i + 1, len(questions_list)):
            q1 = questions_list[i]
            q2 = questions_list[j]
            e1 = embeddings[q1]
            e2 = embeddings[q2]
            sim = float(np.dot(e1, e2))
            print(f"     sim({q1[:15]:15s}, {q2[:15]:15s}) = {sim:.4f}")

    # 验证语义相似：同义句应比无关句相似度高
    sim_food_travel = float(np.dot(embeddings[test_questions[0]], embeddings[test_questions[2]]))
    sim_food_food = float(np.dot(embeddings[test_questions[0]], embeddings[test_questions[1]]))
    
    # 语义相关性测试："成都美食" vs "北京天气" 应该比 "成都美食" vs "成都美食（同义）" 低
    report("语义相似度验证", sim_food_food > sim_food_travel,
           f"同义(成都美食,成都美食)={sim_food_food:.4f} > 不同义(成都美食,北京天气)={sim_food_travel:.4f}")

except ImportError as e:
    report("onnxruntime Python 包", False, f"未安装: {e}")
except Exception as e:
    report("BGE 模型 Python 调用", False, str(e))


# ==================== 3. Consumer BGE 状态检查 ====================
print("\n" + "=" * 70)
print("🔄 3. Consumer 服务 BGE 状态检查")
print("=" * 70)

try:
    # 3.1 Health check
    r = requests.get(f"{CONSUMER_URL}/actuator/health", timeout=5)
    health = r.json()
    report("Consumer Health", r.status_code == 200,
           f"status={health.get('status')}, DB={health.get('components',{}).get('db',{}).get('status')}")

    # 3.2 Check logs for BGE status
    log_candidates = [
        '/workspace/SmartAssistant/logs/consumer-spring.log',
        '/workspace/SmartAssistant/logs/spring.log',
        *[f'/var/log/{f}' for f in ['consumer.log', 'smart-assistant-consumer.log']]
    ]
    bge_found = False
    bge_lines = []
    
    # Check nohup output in the session working directory
    try:
        result = subprocess.run(
            "find /root -name 'nohup.out' 2>/dev/null | head -3",
            shell=True, capture_output=True, text=True, timeout=5
        )
        for p in result.stdout.strip().split('\n'):
            p = p.strip()
            if p:
                log_candidates.append(p)
    except:
        pass
    
    for fpath in log_candidates:
        try:
            with open(fpath, 'r', encoding='utf-8', errors='replace') as f:
                log_text = f.read(50000)  # Read last 50KB
                bge_lines = [l for l in log_text.split('\n') if '[BGE]' in l]
                if bge_lines:
                    bge_found = True
                    break
        except:
            continue
    
    if bge_found:
        bge_summary = '; '.join(bge_lines[-5:])
        report("BGE 模型加载日志", True, bge_summary[:300])
    else:
        # Check process stderr/stdout by inspecting /proc
        try:
            pid = subprocess.run(
                "ps aux | grep 'smart-assistant-consumer' | grep -v grep | awk '{print $2}'",
                shell=True, capture_output=True, text=True, timeout=5
            ).stdout.strip()
            if pid:
                fd_result = subprocess.run(
                    f"ls -la /proc/{pid}/fd/ 2>/dev/null | grep -E '1$|2$|out' | head -5",
                    shell=True, capture_output=True, text=True, timeout=5
                )
                report("BGE 模型加载日志", False,
                       f"进程 {pid} 运行时, 日志文件未找到. FD: {fd_result.stdout[:200]}")
            else:
                report("BGE 模型加载日志", False, "Consumer 进程未运行")
        except:
            report("BGE 模型加载日志", False, "无法定位日志")

    # 3.3 检查 Consumer 进程
    ps_result = subprocess.run(
        "ps aux | grep 'smart-assistant-consumer' | grep -v grep",
        shell=True, capture_output=True, text=True, timeout=5
    )
    if ps_result.stdout.strip():
        report("Consumer 进程", True, ps_result.stdout.strip()[:150])
    else:
        report("Consumer 进程", False, "未运行")

except Exception as e:
    report("Consumer 服务状态", False, str(e))


# ==================== 4. API 接口测试（触发缓存流程） ====================
print("\n" + "=" * 70)
print("🔗 4. 聊天 API 调用测试（触发语义缓存）")
print("=" * 70)

try:
    # 4.1 先注册测试用户
    register_data = {
        "username": f"bge_test_{int(time.time())}",
        "password": "test123456",
        "email": f"bge_test_{int(time.time())}@test.com"
    }
    r = requests.post(f"{CONSUMER_URL}/api/auth/register",
                       json=register_data, timeout=5)
    report("注册测试用户", r.status_code in [200, 201, 400],
           f"status={r.status_code}, body={r.text[:100]}")
    
    if r.status_code in [200, 201]:
        user = r.json()
        test_user_id = str(user.get('id', user.get('userId', '1')))
    else:
        # 尝试登录
        login_data = {"username": "bge_test", "password": "test123456"}
        r2 = requests.post(f"{CONSUMER_URL}/api/auth/login",
                           json=login_data, timeout=5)
        if r2.status_code == 200:
            test_user_id = "1"
        else:
            test_user_id = "1"
    
    # 4.2 发送聊天请求（无session，应触发 BGE embedding）
    print("\n     发送语义相似问题...")
    chat_requests = [
        {"question": "成都有什么美食推荐？", "userId": test_user_id},
        {"question": "说一下成都的美食", "userId": test_user_id},
        {"question": "北京有哪些好玩的地方？", "userId": test_user_id},
    ]
    
    for i, chat_req in enumerate(chat_requests):
        start = time.time()
        try:
            r = requests.post(
                f"{CONSUMER_URL}/api/math/chat",
                json=chat_req,
                headers={"Content-Type": "application/json"},
                timeout=30
            )
            duration = time.time() - start
            if r.status_code == 200:
                resp = r.json()
                reply = resp.get('reply', '')
                reply_preview = reply[:100] if reply else '(空回复)'
                report(f"聊天请求 #{i+1} [{chat_req['question'][:20]}]",
                       True,
                       f"status={r.status_code}, duration={duration:.1f}s, reply={reply_preview}")
            else:
                report(f"聊天请求 #{i+1} [{chat_req['question'][:20]}]",
                       False,
                       f"status={r.status_code}, body={r.text[:200]}")
        except requests.exceptions.Timeout:
            report(f"聊天请求 #{i+1} [{chat_req['question'][:20]}]",
                   False, "超时 (30s)")
        except Exception as e:
            report(f"聊天请求 #{i+1} [{chat_req['question'][:20]}]",
                   False, str(e))

    # 4.3 如果 Gateway 已启动，通过 Gateway 测试完整链路
    try:
        r = requests.get(f"{GATEWAY_URL}/actuator/health", timeout=3)
        if r.status_code == 200:
            report("Gateway 链路", True, "Gateway 已启动")
            
            # 通过 Gateway 发送请求
            gw_headers = {"Content-Type": "application/json"}
            gw_data = {"question": "杭州有什么好吃的？", "userId": test_user_id}
            r2 = requests.post(
                f"{GATEWAY_URL}/assistant/api/math/chat",
                json=gw_data, headers=gw_headers, timeout=30
            )
            report("Gateway→Consumer 链路", r2.status_code == 200,
                   f"status={r2.status_code}")
        else:
            report("Gateway 链路", False, "Gateway health 异常")
    except requests.exceptions.ConnectionError:
        report("Gateway 链路", True, "Gateway 未启动（当前仅 Consumer 运行）")

except Exception as e:
    report("API 接口测试", False, str(e))


# ==================== 5. PostgreSQL 向量数据验证 ====================
print("\n" + "=" * 70)
print("📊 5. PostgreSQL 向量数据验证")
print("=" * 70)

try:
    conn = psycopg2.connect(**DB_CONFIG)
    cur = conn.cursor()

    # 5.1 查看 vector_store 中的 metadata（如果有数据）
    cur.execute("""
        SELECT id, 
               left(content::text, 80) as content_preview,
               metadata::text as metadata,
               length(embedding::text) as emb_length
        FROM vector_store 
        ORDER BY id 
        LIMIT 10
    """)
    rows = cur.fetchall()
    report("vector_store 数据详情", True,
           f"展示前 {len(rows)} 条:\n" + "\n".join(
               [f"      id={r[0]}, content={r[1]}, emb_len={r[3]}" for r in rows]
           ) if rows else "暂无数据")
    
    if rows:
        # 检查 metadata 中的 answer 字段
        for row in rows:
            try:
                meta = json.loads(row[2].replace('"', '\\"').replace("'", '"'))
            except:
                pass

    # 5.2 查看向量维度
    if rows:
        cur.execute("SELECT length(embedding) FROM vector_store LIMIT 1")
        vec_length = cur.fetchone()[0]
        report("向量维度", True,
               f"vector_store 中的 embedding 维度 = {vec_length}")
        
        # 验证维度匹配
        if 'dim' in locals():
            report("维度一致性 (Python ONNX vs pgvector)",
                   dim == vec_length,
                   f"BGE model dim={dim}, pgvector dim={vec_length}")

    # 5.3 向量相似度搜索测试（pgvector 原生）
    if rows:
        # 使用 pgvector 的 <=> 余弦距离进行搜索
        test_q = "成都美食"
        ids_py, mask_py = tokenize(test_q)
        ttype = np.zeros((1, MAX_LEN), dtype=np.int64)
        emb_result = session.run(output_names, {
            'input_ids': ids_py, 'attention_mask': mask_py,
            'token_type_ids': ttype
        })[0][0]
        valid_mask = mask_py[0].astype(bool)
        emb_pooled = emb_result[valid_mask].mean(axis=0)
        norm = np.linalg.norm(emb_pooled)
        if norm > 0:
            emb_pooled = emb_pooled / norm
        emb_list = emb_pooled.tolist()
        
        # pgvector 余弦距离查询
        cur.execute(f"""
            SELECT id, left(content, 60) as content,
                   1 - (embedding <=> %s::vector) as cosine_similarity
            FROM vector_store
            ORDER BY embedding <=> %s::vector
            LIMIT 5
        """, (emb_list, emb_list))
        sim_rows = cur.fetchall()
        for sr in sim_rows:
            print(f"     sim(id={sr[0]}, content={sr[1]}) = {sr[2]:.4f}")
        report("pgvector 余弦相似度搜索", True,
               f"找到 {len(sim_rows)} 个相似结果" if sim_rows else "无相似结果")

    cur.close()
    conn.close()
except Exception as e:
    report("PostgreSQL 向量数据验证", False, str(e))


# ==================== 6. Redis 缓存状态检查 ====================
print("\n" + "=" * 70)
print("⚡ 6. Redis 缓存状态检查")
print("=" * 70)

try:
    import redis as redis_py
    r_client = redis_py.Redis(
        host=REDIS_CONFIG["host"],
        port=REDIS_CONFIG["port"],
        password=REDIS_CONFIG["password"],
        db=REDIS_CONFIG["db"]
    )
    
    # 6.1 检查 Redis 连接
    ping = r_client.ping()
    report("Redis 连接", ping, "")
    
    if ping:
        # 6.2 查看 answer 相关的 keys
        keys = r_client.keys("answer:*")
        answer_keys = [k.decode() if isinstance(k, bytes) else k for k in keys]
        report("Redis answer缓存", True,
               f"{len(answer_keys)} 个 key: {answer_keys[:5]}" if answer_keys else "无缓存")
        
        # 6.3 查看 vector_search 相关的 keys
        vec_keys = r_client.keys("vector_search:*")
        vec_keys_decoded = [k.decode() if isinstance(k, bytes) else k for k in vec_keys]
        report("Redis 向量缓存(方案C)", True,
               f"{len(vec_keys_decoded)} 个 key: {vec_keys_decoded[:5]}" if vec_keys_decoded else "无缓存")

        # 6.4 Redis 信息
        info = r_client.info()
        report("Redis 服务信息", True,
               f"version={info.get('redis_version','?')}, "
               f"used_memory={info.get('used_memory_human','?')}, "
               f"total_keys={info.get('db0',{}).get('keys','?')}")
    
    r_client.close()
except ImportError:
    report("Redis Python 包", False, "redis 未安装")
except Exception as e:
    report("Redis 检查", False, str(e))


# ==================== 7. BGE 模型维度与性能基准 ====================
print("\n" + "=" * 70)
print("⚙️  7. BGE 模型性能基准测试")
print("=" * 70)

try:
    if 'session' in dir() and session is not None:
        # 7.1 批量 embedding 耗时
        batch_sizes = [1, 5, 10]
        for bs in batch_sizes:
            times = []
            for _ in range(3):
                texts = [f"测试文本 {i}" for i in range(bs)]
                start = time.time()
                for t in texts:
                    ids_b, mask_b = tokenize(t)
                    tt = np.zeros((1, MAX_LEN), dtype=np.int64)
                    session.run(output_names, {
                        'input_ids': ids_b, 'attention_mask': mask_b,
                        'token_type_ids': tt
                    })
                times.append(time.time() - start)
            avg = np.mean(times)
            report(f"批量 {bs} 条 embedding 耗时", True,
                   f"平均 {avg*1000:.0f}ms, 单条 {(avg/bs)*1000:.0f}ms")

        # 7.2 模型信息
        providers = ort.get_available_providers()
        report("ONNX Runtime providers", True, f"{providers}")

        # 7.3 验证向量 L2 归一化
        test_vec = embeddings[test_questions[0]]
        l2_norm = float(np.linalg.norm(test_vec))
        report("向量 L2 归一化验证", abs(l2_norm - 1.0) < 0.001,
               f"L2 norm = {l2_norm:.6f}")

except Exception as e:
    report("性能基准测试", False, str(e))


# ==================== 总结 ====================
print("\n" + "=" * 70)
print("📋 测试总结")
print("=" * 70)
s = results["summary"]
print(f"  总测试数: {s['total']}")
print(f"  ✅ 通过: {s['passed']}")
print(f"  ❌ 失败: {s['failed']}")
print(f"  通过率: {s['passed']/s['total']*100:.1f}%" if s['total'] > 0 else "  通过率: N/A")

# 输出 JSON 报告
report_path = "/workspace/SmartAssistant/test-data/bge_vector_test_report.json"
with open(report_path, 'w', encoding='utf-8') as f:
    json.dump(results, f, ensure_ascii=False, indent=2)
print(f"\n📄 详细报告已保存: {report_path}")
