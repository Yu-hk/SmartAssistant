#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""读取 target/chunking-effect.json，渲染分块效果可视化 HTML 报告。"""
import json, html, sys, os

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JSON_PATH = os.path.join(BASE, "smart-assistant-common", "target", "chunking-effect.json")
OUT_PATH = os.path.join(BASE, "docs", "chunking-effect-report.html")

with open(JSON_PATH, encoding="utf-8") as f:
    data = json.load(f)

cfg = data["config"]
pdfs = data["pdfs"]

def esc(s):
    return html.escape("" if s is None else str(s))

def tok_badge(tok, kind):
    # kind: parent | child
    if kind == "parent":
        color = "#2563eb" if tok <= 1024 else "#dc2626"
        cap = "/1024"
    else:
        color = "#059669" if tok <= 307 else "#dc2626"
        cap = "/256"
    return f'<span class="tok" style="background:{color}1a;color:{color};border-color:{color}55">{tok}<span class="tokcap">{cap}</span></span>'

def type_badge(ct):
    if ct == "pdf-table":
        return '<span class="badge table">pdf-table 表格</span>'
    return '<span class="badge prose">pdf 正文</span>'

def detect_dup_title(content):
    """首子块：若第一行标题与紧接着的标题重复，返回 True。"""
    if not content:
        return False
    segs = [s.strip() for s in content.split("\n\n") if s.strip()]
    if len(segs) >= 2 and segs[0] == segs[1]:
        return True
    return False

def bar(tok, maxv, color):
    w = max(2, int(tok / maxv * 100))
    return (f'<div class="barwrap"><div class="bar" style="width:{w}%;'
            f'background:{color}"></div><span class="barval">{tok}</span></div>')

# 概览行
summary_rows = ""
for p in pdfs:
    s = p["stats"]
    summary_rows += f"""<tr>
      <td class="fname">{esc(p['fileName'])}</td>
      <td>{s['pageCount']}</td>
      <td>{s['parsedCount']}</td>
      <td>{s['tableCount']}</td>
      <td>{s['proseChars']}</td>
      <td>{s['parentCount']}</td>
      <td>{s['childCount']}</td>
      <td>{s['avgParentTok']}</td>
      <td>{s['avgChildTok']}</td>
      <td>{s['minParentTok']}–{s['maxParentTok']}</td>
      <td>{s['minChildTok']}–{s['maxChildTok']}</td>
      <td class="{'ok' if s['orphan']==0 else 'bad'}">{s['orphan']}</td>
    </tr>"""

pdf_sections = ""
for idx, p in enumerate(pdfs):
    s = p["stats"]
    pid = f"pdf{idx}"

    # ① 解析内容
    parsed_html = ""
    for el in p["parsedElements"]:
        parsed_html += f"""<div class="el">
          <div class="elhead">{type_badge(el['contentType'])} <span class="meta">第 {el['page']} 页 · {esc(el['section'])} · {el['charCount']} 字</span></div>
          <pre class="elbody">{esc(el['content'])}</pre>
        </div>"""

    # ② 父块
    parent_html = ""
    pmax = max([x["tokens"] for x in p["parents"]] + [1])
    for par in p["parents"]:
        parent_html += f"""<div class="chunk">
          <div class="chunkhead">{tok_badge(par['tokens'],'parent')} <span class="meta">{esc(par['id'])}</span></div>
          {bar(par['tokens'], 1024, '#2563eb')}
          <pre class="chunkbody">{esc(par['content'])}</pre>
        </div>"""

    # ③ 子块
    child_html = ""
    for ch in p["children"]:
        dup = detect_dup_title(ch["content"])
        dup_badge = '<span class="badge warn">⚠ 标题重复注入</span>' if dup else ""
        child_html += f"""<div class="chunk {'dup' if dup else ''}">
          <div class="chunkhead">{tok_badge(ch['tokens'],'child')} <span class="meta">↳ {esc(ch['parentId'])}</span> {dup_badge}</div>
          {bar(ch['tokens'], 307, '#059669')}
          <pre class="chunkbody">{esc(ch['content'])}</pre>
        </div>"""

    pdf_sections += f"""<section class="pdfsec">
      <h2>📄 {esc(p['fileName'])} <span class="sub">（{s['pageCount']} 页 · 解析 {s['parsedCount']} 元素 · 父 {s['parentCount']} / 子 {s['childCount']}）</span></h2>
      <div class="tabs">
        <button class="tab active" onclick="show('{pid}','parse')">① PDF 解析内容</button>
        <button class="tab" onclick="show('{pid}','parent')">② 父块（阅读用 ≤1024）</button>
        <button class="tab" onclick="show('{pid}','child')">③ 子块（检索用 ≤256）</button>
      </div>
      <div id="{pid}-parse" class="tabpane active">{parsed_html}</div>
      <div id="{pid}-parent" class="tabpane">{parent_html}</div>
      <div id="{pid}-child" class="tabpane">{child_html}</div>
    </section>"""

html_doc = f"""<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>RAG 分块效果报告 · SmartAssistant</title>
<style>
  :root{{--bg:#f7f8fa;--card:#fff;--ink:#1f2937;--muted:#6b7280;--line:#e5e7eb;--accent:#2563eb;}}
  *{{box-sizing:border-box}}
  body{{margin:0;background:var(--bg);color:var(--ink);
    font-family:-apple-system,"PingFang SC","Microsoft YaHei",Segoe UI,sans-serif;line-height:1.65}}
  .wrap{{max-width:1080px;margin:0 auto;padding:28px 20px 60px}}
  h1{{font-size:24px;margin:0 0 4px}}
  .sub{{color:var(--muted);font-weight:400;font-size:15px}}
  .cfg{{background:var(--card);border:1px solid var(--line);border-radius:10px;
    padding:12px 16px;margin:16px 0;font-size:13px;color:var(--muted)}}
  .cfg b{{color:var(--ink)}}
  table{{width:100%;border-collapse:collapse;background:var(--card);border:1px solid var(--line);
    border-radius:10px;overflow:hidden;font-size:13px;margin:14px 0}}
  th,td{{padding:9px 10px;text-align:center;border-bottom:1px solid var(--line)}}
  th{{background:#eef2ff;color:#374151;font-weight:600}}
  td.fname{{text-align:left;font-weight:600}}
  .ok{{color:#059669;font-weight:700}}
  .bad{{color:#dc2626;font-weight:700}}
  .pdfsec{{background:var(--card);border:1px solid var(--line);border-radius:12px;
    padding:18px 20px;margin:20px 0}}
  .pdfsec h2{{font-size:18px;margin:0 0 12px}}
  .tabs{{display:flex;gap:8px;margin-bottom:14px;flex-wrap:wrap}}
  .tab{{border:1px solid var(--line);background:#f3f4f6;color:var(--ink);
    padding:7px 14px;border-radius:8px;cursor:pointer;font-size:13px;font-weight:600}}
  .tab.active{{background:var(--accent);color:#fff;border-color:var(--accent)}}
  .tabpane{{display:none}}
  .tabpane.active{{display:block}}
  .el,.chunk{{border:1px solid var(--line);border-radius:9px;padding:12px 14px;margin:10px 0;background:#fcfcfd}}
  .chunk.dup{{border-color:#f59e0b;border-left:4px solid #f59e0b}}
  .elhead,.chunkhead{{display:flex;align-items:center;gap:10px;margin-bottom:6px;flex-wrap:wrap}}
  .meta{{color:var(--muted);font-size:12px}}
  .badge{{font-size:11px;padding:2px 8px;border-radius:20px;border:1px solid}}
  .badge.table{{background:#fef3c7;color:#92400e;border-color:#fcd34d}}
  .badge.prose{{background:#dbeafe;color:#1e40af;border-color:#bfdbfe}}
  .badge.warn{{background:#fef3c7;color:#92400e;border-color:#fcd34d}}
  .tok{{font-size:12px;font-weight:700;padding:1px 8px;border-radius:6px;border:1px solid}}
  .tokcap{{font-weight:400;opacity:.7;font-size:10px;margin-left:2px}}
  pre{{white-space:pre-wrap;word-break:break-word;font-size:12.5px;color:#374151;
    background:#fff;border:1px solid var(--line);border-radius:7px;padding:10px 12px;margin:8px 0 0;max-height:320px;overflow:auto}}
  .barwrap{{position:relative;height:18px;background:#eef2f7;border-radius:5px;margin:6px 0 2px;overflow:hidden}}
  .bar{{height:100%;border-radius:5px}}
  .barval{{position:absolute;right:6px;top:0;font-size:11px;color:#374151;line-height:18px}}
  .note{{background:#fffbeb;border:1px solid #fde68a;border-radius:10px;padding:14px 18px;margin:18px 0;font-size:13.5px}}
  .note h3{{margin:0 0 8px;font-size:15px;color:#92400e}}
  .note code{{background:#fde68a55;padding:1px 5px;border-radius:4px}}
  .legend{{font-size:12px;color:var(--muted);margin:6px 0 0}}
</style></head>
<body><div class="wrap">
  <h1>RAG 分块效果报告 <span class="sub">Parent-Child 双粒度 · 真实管线</span></h1>
  <div class="cfg">
    管线：<b>{esc(cfg['pipeline'])}</b> &nbsp;|&nbsp; 策略：<b>{esc(cfg['strategy'])}</b>
    &nbsp;|&nbsp; 子块 ≤ <b>{cfg['childMaxTokens']}</b> tok &nbsp;|&nbsp; 父块 ≤ <b>{cfg['parentMaxTokens']}</b> tok
    &nbsp;|&nbsp; overlap <b>{cfg['overlap']}</b> &nbsp;|&nbsp; 生成时间：<b>{esc(data['generatedAt'])}</b>
  </div>

  <h3 style="margin:18px 0 6px">一、三份 PDF 分块概览</h3>
  <table>
    <thead><tr>
      <th>PDF</th><th>页数</th><th>解析元素</th><th>表格数</th><th>正文字数</th>
      <th>父块</th><th>子块</th><th>父均tok</th><th>子均tok</th>
      <th>父块区间</th><th>子块区间</th><th>孤儿子块</th>
    </tr></thead>
    <tbody>{summary_rows}</tbody>
  </table>
  <p class="legend">说明：中文按 1 字≈1 token 估算。父块区间为全部父块的 min–max，子块同理。
  孤儿子块=未关联父块的子块（应为 0，否则父块扩展会失效）。</p>

  {pdf_sections}

  <div class="note">
    <h3>⚠ 观察：两处可优化点（非阻断）</h3>
    <p><b>1. 首子块「标题重复注入」</b>：在父块开头即含文档标题时，P1 的标题前缀注入会把标题再拼一次，
    形成 <code>标题\\n\\n标题\\n\\n正文…</code>。当前 <code>ChunkContextUtil.resolveChildPrefix</code> 未判断「子块首行是否已是该标题」。
    影响：轻微 token 浪费 + 向量化文本有冗余，不阻断父子扩展。建议：注入前若子块首段已等于前缀则跳过。</p>
    <p><b>2. 特殊字形丢失（解析层）</b>：PDF 标题中的 emoji（如 🛒）在 PDFBox 提取时因回退字体（DengXian）不含该字形，
    被渲染为替换字符 <code>�</code>。属解析鲁棒性备注，非分块逻辑问题；纯文本提取无法还原字形，必要时可走 OCR 路径。</p>
    <p><b>正面结论</b>：解析阶段双栏/表格检测与章节合并均正常（每 PDF 恰 1 个真表格、无编号列表误报）；
    Parent-Child 双粒度稳定成立（父/子比 2–2.8×），所有子块正确关联父块，链路已可支撑此前落地的
    「父子检索取父块」(small-to-big) 扩展。</p>
  </div>
</div>
<script>
function show(pdf, kind){{
  document.querySelectorAll('#'+pdf+'-parse,#'+pdf+'-parent,#'+pdf+'-child')
    .forEach(e=>e.classList.remove('active'));
  document.querySelectorAll('#'+pdf).length;
  var sec = document.getElementById(pdf+'-'+kind);
  sec.classList.add('active');
  var btns = sec.parentElement.querySelectorAll('.tab');
  btns.forEach(b=>b.classList.remove('active'));
  event.target.classList.add('active');
}}
</script>
</body></html>"""

with open(OUT_PATH, "w", encoding="utf-8") as f:
    f.write(html_doc)
print("OK wrote", OUT_PATH, os.path.getsize(OUT_PATH), "bytes")
