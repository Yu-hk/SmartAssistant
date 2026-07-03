#!/usr/bin/env python3
"""
RAG 检索评测脚本 — 计算 Hit@K / MRR / Recall@K

用法：
    python3 scripts/eval_rag.py

前置条件：
    1. 项目已启动（embedding-service + 对应知识库已索引）
    2. 评测数据集位于 data/rag_eval_dataset.json
    3. 提供 KnowledgeBase.search() 的 REST API 或直接调用 InMemoryKnowledgeBase

输出：
    - 每类问题的 Hit@1 / Hit@3 / Hit@5 / MRR
    - 全局汇总
    - 失败样本列表（便于追查根因）

数据格式：
    {
        "id": "eval-001",
        "category": "订单查询",
        "question": "我的订单 ORD-001 现在是什么状态？",
        "expected_doc_ids": ["ORD-STATUS-v1"],
        "expected_keywords": ["订单状态", "ORD-001"]
    }
"""

import json
import sys
import os
from collections import defaultdict

# ==================== 配置 ====================

DATASET_PATH = os.path.join(os.path.dirname(os.path.dirname(__file__)), "data", "rag_eval_dataset.json")
KB_API_URL = "http://localhost:8081/api/knowledge/search"  # 通过 Gateway
TOP_K = 5

# ==================== 评测指标 ====================

def hit_at_k(retrieved_ids, expected_ids, k):
    """Hit@K: 前 K 个结果中是否包含任意一个期望文档"""
    top_k = set(retrieved_ids[:k])
    expected = set(expected_ids)
    return 1 if top_k & expected else 0

def recall_at_k(retrieved_ids, expected_ids, k):
    """Recall@K: 前 K 个结果中命中的期望文档比例"""
    if not expected_ids:
        return 1.0
    top_k = set(retrieved_ids[:k])
    expected = set(expected_ids)
    return len(top_k & expected) / len(expected)

def mrr(retrieved_ids, expected_ids):
    """MRR: 第一个期望文档的倒数排名"""
    expected = set(expected_ids)
    for rank, doc_id in enumerate(retrieved_ids, 1):
        if doc_id in expected:
            return 1.0 / rank
    return 0.0

# ==================== 加载数据 ====================

def load_dataset(path):
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)

# ==================== 模拟检索（替换为真实 API 调用） ====================

def mock_search(query, top_k=5):
    """
    模拟的检索函数 —— 替换为实际 API 调用或 InMemoryKnowledgeBase 检索。
    
    在实际评测时，应替换为：
    
        import requests
        resp = requests.post(KB_API_URL, json={"query": query, "topK": top_k})
        return resp.json()["doc_ids"]
    
    或者直接调用项目中的 search() 方法。
    """
    # ⚠️ 占位实现：返回空列表（需要替换为真实检索）
    # 替换方案 1: REST API 调用
    # import requests
    # resp = requests.post(KB_API_URL, json={"query": query, "topK": top_k, "tenantId": ""})
    # return resp.json().get("doc_ids", [])
    
    # 替换方案 2: 直接调用 InMemoryKnowledgeBase
    # from smart_assistant_common.rag import InMemoryKnowledgeBase
    # return [hit.getDocument().getId() for hit in kb.search(query, top_k)]
    
    return []

# ==================== 主流程 ====================

def main():
    dataset = load_dataset(DATASET_PATH)
    print(f"📊 加载评测集: {len(dataset)} 条\n")
    
    # 按类别分组统计
    by_category = defaultdict(list)
    for item in dataset:
        by_category[item["category"]].append(item)
    
    all_hits = {1: [], 3: [], 5: []}
    all_recalls = {1: [], 3: [], 5: []}
    all_mrr = []
    failures = []
    
    for item in dataset:
        qid = item["id"]
        question = item["question"]
        expected = item["expected_doc_ids"]
        
        # 检索
        retrieved = mock_search(question, TOP_K)
        
        # 计算各指标
        for k in [1, 3, 5]:
            all_hits[k].append(hit_at_k(retrieved, expected, k))
            all_recalls[k].append(recall_at_k(retrieved, expected, k))
        all_mrr.append(mrr(retrieved, expected))
        
        # 记录失败样本
        if all_hits[5][-1] == 0:
            failures.append((qid, question, expected, retrieved))
    
    # ==================== 输出结果 ====================
    
    print("=" * 60)
    print("  全局汇总")
    print("=" * 60)
    for k in [1, 3, 5]:
        avg_hit = sum(all_hits[k]) / len(all_hits[k]) * 100
        avg_recall = sum(all_recalls[k]) / len(all_recalls[k]) * 100
        print(f"  Hit@{k}: {avg_hit:.1f}%    Recall@{k}: {avg_recall:.1f}%")
    avg_mrr = sum(all_mrr) / len(all_mrr)
    print(f"  MRR:   {avg_mrr:.4f}")
    
    print(f"\n{'=' * 60}")
    print(f"  按类别")
    print(f"{'=' * 60}")
    for cat, items in sorted(by_category.items()):
        cat_hits = {k: [] for k in [1, 3, 5]}
        for item in items:
            qid = item["id"]
            question = item["question"]
            expected = item["expected_doc_ids"]
            retrieved = mock_search(question, TOP_K)
            for k in [1, 3, 5]:
                cat_hits[k].append(hit_at_k(retrieved, expected, k))
        
        avg = {k: sum(v) / len(v) * 100 for k, v in cat_hits.items()}
        print(f"  {cat} ({len(items)} 条): Hit@1={avg[1]:.1f}%  Hit@3={avg[3]:.1f}%  Hit@5={avg[5]:.1f}%")
    
    # 失败样本
    if failures:
        print(f"\n{'=' * 60}")
        print(f"  ❌ 失败样本 ({len(failures)} 条)")
        print(f"{'=' * 60}")
        for qid, question, expected, retrieved in failures:
            print(f"\n  [{qid}] {question}")
            print(f"    期望: {expected}")
            print(f"    实际: {retrieved[:5]}")
    
    print(f"\n{'=' * 60}")
    print(f"  评测完成")
    print(f"{'=' * 60}")

if __name__ == "__main__":
    main()
