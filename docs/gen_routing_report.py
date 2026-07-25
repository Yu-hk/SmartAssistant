#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""渲染 ChunkingStrategyRouter 路由分块效果报告（读 chunking-routing-effect.json）。"""
import json, os

SRC = "smart-assistant-common/target/chunking-routing-effect.json"
OUT = "docs/chunking-routing-effect-report.html"

data = json.load(open(SRC, encoding="utf-8"))
instances = data["instances"]

def tok_bar(tok, maxv=1024):
    pct = max(2, min(100, int(tok / maxv * 100)))
    color = "#2f9e44" if tok <= 256 else ("#f08c00" if tok <= 512 else "#e8590c")
    return f'<div class="bar" style="width:{pct}%;background:{color}"></div>'

def esc(s):
    return (s or "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

def chunk_html(chunks, label):
    if not chunks:
        return '<p class="muted">（无）</p>'
    rows = []
    for c in chunks:
        pid = c.get("parentDocId") or ""
        rel = f'<span class="rel">↳ 父块 {esc(pid[-24:])}</span>' if pid else '<span class="rel root">根块(父)</span>'
        rows.append(f'''
        <div class="chunk">
          <div class="chunk-meta">{label} · {c["tokens"]} tok {rel}</div>
          {tok_bar(c["tokens"])}
          <div class="chunk-text">{esc(c["content"])}</div>
        </div>''')
    return "\n".join(rows)

cards = []
for inst in instances:
    routed = inst["routedStrategy"]
    badge = "BGE" if routed == "BGE" else "RULE"
    badge_cls = "badge-bge" if routed == "BGE" else "badge-rule"
    routed_desc = ("语义切分（embedding 边界检测 + minChunk 护栏）" if routed == "BGE"
                   else "规则切分（递归/结构，免费）")
    cards.append(f'''
    <section class="card">
      <div class="card-head">
        <h2>{esc(inst["name"])}</h2>
        <span class="badge {badge_cls}">{badge}</span>
      </div>
      <div class="meta-row">
        <span>contentType=<code>{esc(inst["contentType"])}</code></span>
        <span>原文 {inst["textTokens"]} tok</span>
        <span>父块 <b>{inst["parentCount"]}</b></span>
        <span>子块 <b>{inst["childCount"]}</b></span>
      </div>
      <p class="routed-desc">路由决策：<b>{badge}</b> —— {routed_desc}</p>
      <details class="src">
        <summary>原文</summary>
        <div class="chunk-text">{esc(inst["text"])}</div>
      </details>
      <h3>父块（阅读用，大块）</h3>
      {chunk_html(inst["parentChunks"], "父")}
      <h3>子块（检索用，小块）</h3>
      {chunk_html(inst["childChunks"], "子")}
    </section>''')

html = f'''<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>RAG 分块路由效果报告</title>
<style>
*{{box-sizing:border-box}}
body{{font-family:-apple-system,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif;
  margin:0;background:#f5f6f8;color:#1f2329;line-height:1.6}}
.wrap{{max-width:1080px;margin:0 auto;padding:28px 20px 60px}}
h1{{font-size:22px;margin:0 0 4px}}
.sub{{color:#6b7280;font-size:13px;margin-bottom:18px}}
.strategy{{background:#e7f5ff;border:1px solid #a5d8ff;border-radius:10px;
  padding:12px 16px;font-size:13px;margin-bottom:22px;color:#1971c2}}
.card{{background:#fff;border:1px solid #e9ecef;border-radius:12px;
  padding:18px 20px;margin-bottom:20px;box-shadow:0 1px 3px rgba(0,0,0,.04)}}
.card-head{{display:flex;align-items:center;justify-content:space-between}}
.card-head h2{{font-size:17px;margin:0}}
.badge{{font-size:12px;font-weight:700;padding:3px 10px;border-radius:20px;color:#fff}}
.badge-bge{{background:#4263eb}}
.badge-rule{{background:#868e96}}
.meta-row{{display:flex;gap:16px;flex-wrap:wrap;font-size:12px;color:#495057;margin:8px 0}}
.meta-row code{{background:#f1f3f5;padding:1px 6px;border-radius:4px}}
.routed-desc{{font-size:13px;background:#f8f9fa;border-left:3px solid #4263eb;
  padding:6px 12px;margin:6px 0 12px;border-radius:0 6px 6px 0}}
.src{{margin:8px 0 14px}}
.src summary{{cursor:pointer;font-size:12px;color:#4263eb}}
h3{{font-size:14px;margin:16px 0 8px;color:#343a40}}
.chunk{{border:1px solid #f1f3f5;border-radius:8px;padding:10px 12px;margin-bottom:10px;background:#fcfcfd}}
.chunk-meta{{font-size:11px;color:#868e96;margin-bottom:5px}}
.rel{{color:#4263eb}}
.rel.root{{color:#2f9e44}}
.bar{{height:6px;border-radius:3px;margin:4px 0 8px}}
.chunk-text{{font-size:13px;white-space:pre-wrap;word-break:break-all;color:#212529;
  max-height:160px;overflow:auto;background:#fff;padding:6px;border-radius:6px}}
.muted{{color:#adb5bd;font-size:13px}}
.legend{{font-size:11px;color:#868e96;margin-top:4px}}
</style></head>
<body><div class="wrap">
<h1>RAG 分块路由效果报告</h1>
<div class="sub">按文件类型路由：txt 短→规则 / txt 长无结构→BGE / 图片→BGE（EmbeddingSemanticChunkStrategy + minChunk 护栏）</div>
<div class="strategy">策略：{esc(data["strategy"])}</div>
{''.join(cards)}
<div class="legend">说明：BGE 分支使用确定性 stub embedder（字符 trigram 向量）离线验证；图片实例因 stub 对高度重复文本不敏感未切出多块，真实 BGE 会按语义差异进一步切分。颜色条：绿≤256 / 橙≤512 / 红>512 tok。</div>
</div></body></html>'''

os.makedirs("docs", exist_ok=True)
open(OUT, "w", encoding="utf-8").write(html)
print("written:", OUT, os.path.getsize(OUT), "bytes")
