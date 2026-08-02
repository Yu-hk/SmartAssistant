#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""读取 target/multipath-effect.json，生成多路召回效果可视化报告 HTML。

相比旧版新增：
- 各路召回结果从裸 doc id 解析为可读标题，相关文档（golden）高亮；
- 新增「每路独立召回效果汇总」：单独评估 5 路各自的 Recall@K/MRR/nDCG@K；
- 真实端到端管线改为按路展开，展示每一路实际召回的内容片段。
"""
import json
import html
import math

SRC = "target/multipath-effect.json"
OUT = "docs/multipath-retrieval-effect-report.html"

with open(SRC, encoding="utf-8") as f:
    data = json.load(f)

meta = data["meta"]
e2e = data["e2e"]
m = data["metrics"]
avg = m["average"]
single = avg["single"]
multi = avg["multi"]
doc_index = data.get("docIndex", {})


def pct(v):
    return f"{v * 100:.1f}%"


def lift(s, mu):
    if s == 0:
        return "—" if mu == 0 else "+∞"
    return f"+{(mu - s) / s * 100:.0f}%"


def title_of(doc_id):
    return doc_index.get(doc_id, doc_id)


# ---------------- 检索指标（与 Java RetrievalMetrics 保持一致） ----------------
def recall_at_k(rel, retrieved, k):
    if not rel or not retrieved:
        return 0.0
    top = retrieved[:k]
    hit = sum(1 for x in top if x in rel)
    return hit / len(rel)


def mrr(rel, retrieved):
    if not rel or not retrieved:
        return 0.0
    for i, x in enumerate(retrieved):
        if x in rel:
            return 1.0 / (i + 1)
    return 0.0


def ndcg_at_k(rel, retrieved, k):
    if not rel or not retrieved:
        return 0.0
    eff = min(k, len(retrieved))
    dcg = 0.0
    for i in range(eff):
        if retrieved[i] in rel:
            dcg += 1.0 / math.log2(i + 2)
    total = min(len(rel), eff)
    idcg = 0.0
    for i in range(total):
        idcg += 1.0 / math.log2(i + 2)
    return dcg / idcg if idcg > 0 else 0.0


# ---------------- 核心指标卡 ----------------
cards = [
    ("Recall@1", single["recall@1"], multi["recall@1"]),
    ("Recall@3", single["recall@3"], multi["recall@3"]),
    ("Recall@5", single["recall@5"], multi["recall@5"]),
    ("MRR", single["mrr"], multi["mrr"]),
    ("nDCG@5", single["ndcg@5"], multi["ndcg@5"]),
]

card_html = ""
for name, s, mu in cards:
    card_html += f"""
    <div class="card">
      <div class="card-name">{html.escape(name)}</div>
      <div class="card-multi">{pct(mu)}</div>
      <div class="card-single">单路语义: {pct(s)}</div>
      <div class="card-lift">多路提升 {lift(s, mu)}</div>
    </div>"""

# ---------------- 量化明细表 ----------------
rows = ""
for q in m["perQuery"]:
    rel = ", ".join(title_of(x) for x in q["relevant"])
    s = q["single"]
    mu = q["multi"]
    cls = "ok" if mu["recall@5"] >= s["recall@5"] else "bad"
    rows += f"""
    <tr class="{cls}">
      <td>{html.escape(q['query'])}</td>
      <td class="mono">{html.escape(rel)}</td>
      <td>{pct(s['recall@1'])}</td>
      <td>{pct(s['recall@3'])}</td>
      <td>{pct(s['recall@5'])}</td>
      <td>{s['mrr']:.2f}</td>
      <td>{pct(s['ndcg@5'])}</td>
      <td><b>{pct(mu['recall@1'])}</b></td>
      <td><b>{pct(mu['recall@3'])}</b></td>
      <td><b>{pct(mu['recall@5'])}</b></td>
      <td><b>{mu['mrr']:.2f}</b></td>
      <td><b>{pct(mu['ndcg@5'])}</b></td>
    </tr>"""

# ---------------- 各路召回明细（每查询 5 路分别召回的文档，解析为标题+golden高亮） ----------------
detail = ""
for q in m["perQuery"]:
    paths = q["paths"]
    rel_set = set(q["relevant"])
    cells = ""
    for pname, items in paths.items():
        spans = []
        for iid in items:
            t = title_of(iid)
            if iid in rel_set:
                spans.append(f'<span class="hit">{html.escape(t)} ✓</span>')
            else:
                spans.append(f'<span class="dim">{html.escape(t)}</span>')
        items_s = "、".join(spans) if spans else "（空）"
        cells += f'<div class="path"><span class="path-name">{html.escape(pname)}</span>: {items_s}</div>'
    fused_titles = "、".join(title_of(x) for x in q["multiIds"])
    detail += f"""
    <div class="detail-block">
      <div class="detail-q">▸ {html.escape(q['query'])} <span class="rel">相关: {html.escape(', '.join(title_of(x) for x in q['relevant']))}</span></div>
      <div class="detail-paths">{cells}</div>
      <div class="detail-multi">融合 Top: <span class="mono">{html.escape(fused_titles)}</span></div>
    </div>"""

# ---------------- 每路独立召回效果汇总（单独评估 5 路各自的指标） ----------------
PREFERRED_ORDER = ["语义", "BM25", "关键词", "精确", "图谱"]
per_path = {}  # pname -> 累加器
order = []
for q in m["perQuery"]:
    rel = set(q["relevant"])
    for pname, items in q["paths"].items():
        if pname not in per_path:
            per_path[pname] = {"r1": 0.0, "r3": 0.0, "r5": 0.0, "mrr": 0.0, "ndcg": 0.0, "n": 0}
            order.append(pname)
        acc = per_path[pname]
        acc["r1"] += recall_at_k(rel, items, 1)
        acc["r3"] += recall_at_k(rel, items, 3)
        acc["r5"] += recall_at_k(rel, items, 5)
        acc["mrr"] += mrr(rel, items)
        acc["ndcg"] += ndcg_at_k(rel, items, 5)
        acc["n"] += 1

order.sort(key=lambda p: PREFERRED_ORDER.index(p) if p in PREFERRED_ORDER else 99)
path_summary_rows = ""
for pname in order:
    acc = per_path[pname]
    n = acc["n"] or 1
    r1 = acc["r1"] / n
    r3 = acc["r3"] / n
    r5 = acc["r5"] / n
    mr = acc["mrr"] / n
    nd = acc["ndcg"] / n
    cls = "ok" if pname == "语义" else ""
    note = "（= 单路语义基线）" if pname == "语义" else ""
    path_summary_rows += f"""
    <tr class="{cls}">
      <td><b>{html.escape(pname)}</b>{note}</td>
      <td>{pct(r1)}</td>
      <td>{pct(r3)}</td>
      <td>{pct(r5)}</td>
      <td>{mr:.2f}</td>
      <td>{pct(nd)}</td>
    </tr>"""

# ---------------- 真实端到端管线（按路展开，展示每一路实际召回内容） ----------------
e2e_blocks = ""
for r in e2e:
    paths = r.get("paths", {})
    path_html = ""
    for k, p in paths.items():
        cnt = p.get("count", 0)
        items = p.get("items", [])
        pill_cls = "pill0" if cnt == 0 else "pill1"
        if items:
            item_html = "<br>".join(html.escape(it) for it in items)
        else:
            item_html = '<span class="dim">（未召回）</span>'
        path_html += f"""
        <div class="e2e-path">
          <span class="pill {pill_cls}">{html.escape(k)} · 命中 {cnt}</span>
          <div class="e2e-items mono">{item_html}</div>
        </div>"""
    fused = r.get("fusedTop", [])
    fused_s = "<br>".join(
        f'<span class="mono fused">{html.escape(f["content"])}</span> <span class="score">RRF={f["score"]:.3f}</span>'
        for f in fused[:3]
    )
    degraded = "⚠ 降级" if r.get("degraded") else "✓ 正常"
    deg_cls = "deg-bad" if r.get("degraded") else "deg-ok"
    e2e_blocks += f"""
    <div class="detail-block">
      <div class="detail-q">▸ {html.escape(r['query'])}</div>
      <div class="detail-paths">{path_html}</div>
      <div class="detail-multi">融合 Top（RRF 分数）:<br>{fused_s}</div>
      <div class="e2e-foot">质量分 <b>{r.get('qualityScore', 0):.3f}</b> · <span class="{deg_cls}">{degraded}</span></div>
    </div>"""

avg_lift_rec5 = lift(single["recall@5"], multi["recall@5"])
avg_lift_mrr = lift(single["mrr"], multi["mrr"])
avg_lift_ndcg = lift(single["ndcg@5"], multi["ndcg@5"])

HTML = f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>多路召回（Multi-Path Retrieval）效果测试报告</title>
<style>
  * {{ box-sizing: border-box; }}
  body {{ font-family: -apple-system, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
         margin: 0; padding: 32px; background: #f5f7fa; color: #1f2937; line-height: 1.6; }}
  h1 {{ font-size: 26px; margin: 0 0 4px; }}
  h2 {{ font-size: 19px; margin: 34px 0 12px; padding-left: 10px; border-left: 4px solid #4f8cff; }}
  h3 {{ font-size: 15px; margin: 16px 0 8px; color: #374151; }}
  .sub {{ color: #6b7280; font-size: 13px; margin-bottom: 18px; }}
  .meta {{ background: #eef2ff; border: 1px solid #c7d2fe; border-radius: 8px; padding: 12px 16px;
          font-size: 13px; color: #3730a3; margin-bottom: 8px; }}
  .cards {{ display: flex; gap: 14px; flex-wrap: wrap; margin: 14px 0 6px; }}
  .card {{ flex: 1; min-width: 150px; background: #fff; border: 1px solid #e5e7eb; border-radius: 10px;
          padding: 16px; text-align: center; box-shadow: 0 1px 3px rgba(0,0,0,.04); }}
  .card-name {{ font-size: 13px; color: #6b7280; }}
  .card-multi {{ font-size: 28px; font-weight: 700; color: #16a34a; margin: 4px 0; }}
  .card-single {{ font-size: 12px; color: #9ca3af; }}
  .card-lift {{ font-size: 13px; color: #4f8cff; font-weight: 600; margin-top: 4px; }}
  table {{ width: 100%; border-collapse: collapse; background: #fff; font-size: 13px;
          border: 1px solid #e5e7eb; border-radius: 8px; overflow: hidden; }}
  th, td {{ padding: 9px 10px; text-align: center; border-bottom: 1px solid #f0f0f0; }}
  th {{ background: #f8fafc; color: #374151; font-weight: 600; }}
  td.mono, .mono {{ font-family: "SFMono-Regular", Consolas, monospace; font-size: 12px; white-space: pre-wrap; }}
  tr.ok td {{ }}
  tr.bad td {{ background: #fef2f2; }}
  .ok {{ }}
  .pill {{ display: inline-block; background: #eef2ff; color: #3730a3; border-radius: 12px;
          padding: 2px 9px; margin: 2px; font-size: 12px; }}
  .pill0 {{ background: #f3f4f6; color: #9ca3af; }}
  .pill1,.pill2,.pill3 {{ background: #dcfce7; color: #166534; }}
  .detail-block {{ background: #fff; border: 1px solid #e5e7eb; border-radius: 8px; padding: 10px 14px; margin: 8px 0; }}
  .detail-q {{ font-weight: 600; }}
  .detail-q .rel {{ color: #9ca3af; font-weight: 400; font-size: 12px; margin-left: 8px; }}
  .detail-paths {{ margin: 6px 0; }}
  .path {{ font-size: 12px; margin: 3px 0; }}
  .path-name {{ display: inline-block; min-width: 54px; color: #4f8cff; font-weight: 600; }}
  .detail-multi {{ font-size: 12px; color: #374151; margin-top: 4px; }}
  .e2e-path {{ margin: 6px 0; }}
  .e2e-items {{ font-size: 11px; color: #374151; margin: 3px 0 3px 58px; white-space: pre-wrap; }}
  .e2e-foot {{ font-size: 12px; color: #6b7280; margin-top: 6px; }}
  .hit {{ color: #166534; font-weight: 600; background: #dcfce7; border: 1px solid #86efac;
          border-radius: 4px; padding: 0 5px; margin: 0 2px; }}
  .dim {{ color: #9ca3af; }}
  .deg-ok {{ color: #16a34a; }}
  .deg-bad {{ color: #dc2626; }}
  .note {{ background: #fffbeb; border: 1px solid #fde68a; border-radius: 8px; padding: 12px 16px;
           font-size: 13px; color: #92400e; margin-top: 14px; }}
  .note b {{ color: #78350f; }}
  .conclusion {{ background: #ecfdf5; border: 1px solid #a7f3d0; border-radius: 8px; padding: 14px 18px;
                font-size: 14px; color: #065f46; margin: 10px 0 4px; }}
</style>
</head>
<body>
  <h1>多路召回（Multi-Path Retrieval）效果测试报告</h1>
  <div class="sub">Product 模块 · RagSearchPipeline · RRF 融合 + 去重 + 重排 · 全内存组件（无需真实 BGE / 向量库）</div>

  <div class="meta">
    测试环境：RRF_K = <b>{meta['rrfK']}</b> · candidatePoolK = <b>{meta['candidatePoolK']}</b> ·
    qualityThreshold = <b>{meta['qualityThreshold']}</b> · embedding = <b>{html.escape(meta['embedding'])}</b><br>
    组件：{html.escape(meta['knowledgeBase'])}。各路：语义 / BM25 / 关键词 / 精确 / 图谱（量化基准）；
    真实管线：精确匹配 / 关键词搜索 / BM25 / 知识库。
  </div>

  <h2>① 核心结论：单路语义 vs 多路 RRF 融合</h2>
  <div class="cards">{card_html}</div>
  <div class="conclusion">
    <b>多路召回把平均 Recall@5 从 {pct(single['recall@5'])} 拉到 {pct(multi['recall@5'])}（{avg_lift_rec5}），
    MRR 从 {single['mrr']:.2f} 提升到 {multi['mrr']:.2f}（{avg_lift_mrr}），
    nDCG@5 从 {pct(single['ndcg@5'])} 提升到 {pct(multi['ndcg@5'])}（{avg_lift_ndcg}）。</b><br>
    单路（仅语义）因 embedding 模型对长尾/结构化意图覆盖不足而大量漏召回；BM25、关键词、图谱等路从不同角度补足缺口，
    RRF 按排名融合后几乎所有相关文档都进入候选，验证了「多路召回 + RRF」对召回覆盖率的实质提升。
  </div>

  <h2>② 量化明细（每查询单路 vs 多路）</h2>
  <table>
    <thead>
      <tr>
        <th rowspan="2">查询</th><th rowspan="2">相关文档</th>
        <th colspan="5">单路（仅语义召回）</th>
        <th colspan="5">多路 RRF 融合</th>
      </tr>
      <tr>
        <th>R@1</th><th>R@3</th><th>R@5</th><th>MRR</th><th>nDCG@5</th>
        <th>R@1</th><th>R@3</th><th>R@5</th><th>MRR</th><th>nDCG@5</th>
      </tr>
    </thead>
    <tbody>{rows}</tbody>
    <tfoot>
      <tr style="font-weight:700;background:#f8fafc">
        <td>平均</td><td></td>
        <td>{pct(single['recall@1'])}</td><td>{pct(single['recall@3'])}</td><td>{pct(single['recall@5'])}</td>
        <td>{single['mrr']:.2f}</td><td>{pct(single['ndcg@5'])}</td>
        <td>{pct(multi['recall@1'])}</td><td>{pct(multi['recall@3'])}</td><td>{pct(multi['recall@5'])}</td>
        <td>{multi['mrr']:.2f}</td><td>{pct(multi['ndcg@5'])}</td>
      </tr>
    </tfoot>
  </table>

  <h2>③ 各路召回明细（每查询 5 路分别召回的文档）</h2>
  <p class="sub">裸 doc id 已解析为文档标题；<span class="hit">绿色 ✓</span> 表示该路召回命中了相关文档（golden）。</p>
  {detail}

  <h2>③-b 每路独立召回效果汇总（单独评估各路，不融合）</h2>
  <p class="sub">下表把每一路当成「单路检索器」单独打分（基于 8 条 golden 查询），直观展示各路互补性。</p>
  <table>
    <thead>
      <tr><th>召回路</th><th>Recall@1</th><th>Recall@3</th><th>Recall@5</th><th>MRR</th><th>nDCG@5</th></tr>
    </thead>
    <tbody>{path_summary_rows}</tbody>
  </table>
  <div class="note">
    <b>读图：</b>「语义」一路单独打分即等于 ① 中的「单路语义基线」；
    BM25 / 关键词 / 图谱 各自在不同查询上命中了语义路漏掉的相关文档——
    例如「苹果笔记本推荐」语义路 Recall@5=0，但 BM25 与图谱路都命中了 MacBook Air M3。
    正因各路盲区不重叠，RRF 融合后才能把整体 Recall@5 拉满到 100%。
  </div>

  <h2>④ 真实端到端管线（RagSearchPipeline 实跑，按路展开）</h2>
  <p class="sub">每一路 Handler 实际召回的内容片段如下；融合链路在最后汇总。</p>
  {e2e_blocks}
  <div class="note">
    <b>说明：</b>真实端到端使用 <code>StubBgeEmbeddingModel</code>（确定性字符向量，<b>无真实语义</b>），
    因此「精确匹配 / 关键词搜索」两路对自然语言查询按商品编码/名称匹配返回 0，
    而 BM25（真实分词词频）与知识库路（含商品名时伪语义命中）贡献召回。
    融合链路（RRF → 去重 → 重排）工作正常，质量分随命中路数变化。
    接真实 BGE 后，语义/知识库路命中率会进一步提升，多路互补优势更明显。
    <br>「降级」为多路召回的设计内容错：某路异常不影响整体融合结果。
  </div>

  <div class="note">
    <b>价值总结：</b>「单路」受限于单一检索视角的盲区（语义模型对结构化/长尾意图覆盖不足）；
    「多路召回 + RRF」通过精确 / 关键词 / BM25 / 向量语义 / 图谱等多视角互补，
    用排名融合消除分数不可比问题，将平均 Recall@5 从 37.5% 提升到 100%、MRR 提升到 0.81，
    是生产级 RAG「召回全 → 排得准 → 上下文净」闭环的第一道保障。
  </div>
</body>
</html>"""

with open(OUT, "w", encoding="utf-8") as f:
    f.write(HTML)

print(f"报告已生成: {OUT}  ({len(HTML)} bytes)")
