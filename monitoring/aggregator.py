#!/usr/bin/env python3
"""
SmartAssistant 指标聚合器
在本地扮演 Prometheus + Grafana 角色（离线环境替代方案）
当网络恢复后，可直接切换为 Docker 版 Prometheus+Grafana
"""

import json, time, threading, http.server, urllib.request, sqlite3, os, math
from datetime import datetime, timedelta

PORT = 9099
SCRAPE_INTERVAL = 10  # 秒
DATA_RETENTION_MINUTES = 60

SERVICES = [
    ("api-gateway", 8081),
    ("consumer-service", 8082),
    ("router-service", 8083),
    ("product-service", 8084),
    ("order-service", 8085),
    ("user-service", 8086),
]

DB_PATH = os.path.join(os.path.dirname(__file__), "metrics.db")

# ========== 数据库初始化 ==========
def init_db():
    conn = sqlite3.connect(DB_PATH)
    c = conn.cursor()
    c.execute("""
        CREATE TABLE IF NOT EXISTS metrics (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            ts TEXT NOT NULL,
            service TEXT NOT NULL,
            metric_name TEXT NOT NULL,
            metric_value REAL
        )
    """)
    c.execute("CREATE INDEX IF NOT EXISTS idx_metrics_ts ON metrics(ts)")
    c.execute("CREATE INDEX IF NOT EXISTS idx_metrics_svc ON metrics(service, metric_name)")
    c.execute("""
        CREATE TABLE IF NOT EXISTS health_log (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            ts TEXT NOT NULL,
            service TEXT NOT NULL,
            status TEXT NOT NULL
        )
    """)
    conn.commit()
    conn.close()

def cleanup_old_data():
    cutoff = (datetime.now() - timedelta(minutes=DATA_RETENTION_MINUTES)).isoformat()
    conn = sqlite3.connect(DB_PATH)
    conn.execute("DELETE FROM metrics WHERE ts < ?", (cutoff,))
    conn.execute("DELETE FROM health_log WHERE ts < ?", (cutoff,))
    conn.commit()
    conn.close()

# ========== 数据抓取 ==========
metric_cache = {
    "health": {},
    "jvm": {},
    "http": {},
    "system": {},
    "last_update": None
}

def scrape_service(name, port):
    """抓取单个服务的指标"""
    result = {"health": None, "metrics": {}, "error": None}
    try:
        # 读取 Prometheus 格式的指标
        req = urllib.request.Request(
            f"http://localhost:{port}/actuator/prometheus",
            headers={"Accept": "text/plain"},
            method="GET"
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            raw = resp.read().decode("utf-8")
        
        # 解析 Prometheus 文本格式
        parsed = {}
        for line in raw.split("\n"):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            # 处理带标签的指标: metric_name{label="val",...} value
            if "{" in line:
                name_part, rest = line.split("{", 1)
                _, value_part = rest.rsplit("}", 1)
                value = float(value_part.strip())
            else:
                parts = line.rsplit(None, 1)
                if len(parts) != 2:
                    continue
                name_part, value = parts[0], float(parts[1])
            parsed[name_part] = value

        result["metrics"] = parsed

        # 健康检查
        hr = urllib.request.Request(
            f"http://localhost:{port}/actuator/health",
            headers={"Accept": "application/json"},
            method="GET"
        )
        with urllib.request.urlopen(hr, timeout=5) as resp:
            health_data = json.loads(resp.read().decode("utf-8"))
            result["health"] = health_data.get("status", "DOWN")
    except Exception as e:
        result["error"] = str(e)
        result["health"] = "DOWN"

    return result

def scrape_all():
    """抓取所有服务"""
    global metric_cache
    results = {}
    for name, port in SERVICES:
        results[name] = scrape_service(name, port)
    
    now = datetime.now().isoformat()
    
    # 存入 SQLite
    conn = sqlite3.connect(DB_PATH)
    for name, data in results.items():
        status = data.get("health") or "DOWN"
        conn.execute("INSERT INTO health_log (ts, service, status) VALUES (?, ?, ?)",
                     (now, name, status))
        for metric, value in data.get("metrics", {}).items():
            if metric.startswith("jvm_") or metric.startswith("system_") or metric.startswith("http_"):
                conn.execute("INSERT INTO metrics (ts, service, metric_name, metric_value) VALUES (?, ?, ?, ?)",
                             (now, name, metric, value))

    conn.commit()
    conn.close()
    cleanup_old_data()
    
    # 更新缓存
    metric_cache = {
        "health": {name: data.get("health") for name, data in results.items()},
        "jvm": {name: {k: v for k, v in data.get("metrics", {}).items() if k.startswith("jvm_")} for name, data in results.items()},
        "http": {name: {k: v for k, v in data.get("metrics", {}).items() if k.startswith("http_")} for name, data in results.items()},
        "system": {name: {k: v for k, v in data.get("metrics", {}).items() if k.startswith("system_")} for name, data in results.items()},
        "last_update": now,
        "errors": {name: data.get("error") for name, data in results.items() if data.get("error")}
    }
    return results

def scrape_loop():
    """后台持续抓取"""
    while True:
        try:
            scrape_all()
        except Exception as e:
            print(f"[Aggregator] Scrape error: {e}")
        time.sleep(SCRAPE_INTERVAL)

# ========== HTTP Server ==========
class AggregatorHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass  # 静默日志

    def _send_json(self, data, status=200):
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(json.dumps(data, ensure_ascii=False).encode("utf-8"))

    def _send_html(self, html, status=200):
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(html.encode("utf-8"))

    def _send_prometheus(self, text, status=200):
        self.send_response(status)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.end_headers()
        self.wfile.write(text.encode("utf-8"))

    def _get_ts_data(self, metric_name, minutes=15):
        """从 SQLite 获取时序数据"""
        cutoff = (datetime.now() - timedelta(minutes=minutes)).isoformat()
        conn = sqlite3.connect(DB_PATH)
        rows = conn.execute(
            "SELECT ts, service, metric_value FROM metrics WHERE metric_name = ? AND ts > ? ORDER BY ts",
            (metric_name, cutoff)
        ).fetchall()
        conn.close()
        return rows

    def do_GET(self):
        if self.path == "/" or self.path == "/dashboard":
            self._send_html(self._build_dashboard())
        elif self.path == "/api/health":
            self._send_json(metric_cache)
        elif self.path == "/api/services":
            self._send_json({name: {"health": metric_cache["health"].get(name)} for name, _ in SERVICES})
        elif self.path.startswith("/api/ts/"):
            metric = self.path.split("/api/ts/")[1]
            data = self._get_ts_data(metric)
            # 整理为 series 格式
            series = {}
            for ts, svc, val in data:
                if svc not in series:
                    series[svc] = []
                series[svc].append({"t": ts, "v": val})
            self._send_json(series)
        elif self.path == "/metrics" or self.path == "/actuator/prometheus":
            self._send_prometheus(self._build_prometheus())
        else:
            self._send_json({"error": "Not Found"}, 404)

    def _build_prometheus(self):
        """生成统一的 Prometheus 文本格式"""
        lines = ["# HELP smartassistant_aggregated SmartAssistant metrics aggregated by local aggregator",
                 "# TYPE smartassistant_aggregated gauge"]
        for name, _ in SERVICES:
            health = metric_cache["health"].get(name, "DOWN")
            val = 1 if health == "UP" else 0
            lines.append(f'smartassistant_service_up{{service="{name}"}} {val}')
        
        for name, metrics in metric_cache["jvm"].items():
            for k, v in metrics.items():
                lines.append(f'{k}{{service="{name}"}} {v}')
        for name, metrics in metric_cache["http"].items():
            for k, v in metrics.items():
                lines.append(f'{k}{{service="{name}"}} {v}')
        for name, metrics in metric_cache["system"].items():
            for k, v in metrics.items():
                lines.append(f'{k}{{service="{name}"}} {v}')
        
        return "\n".join(lines)

    def _build_dashboard(self):
        """构建实时仪表板 HTML"""
        up = sum(1 for v in metric_cache["health"].values() if v == "UP")
        total = len(SERVICES)
        now = metric_cache.get("last_update") or "-"

        cards_html = ""
        for name, port in SERVICES:
            h = metric_cache["health"].get(name, "DOWN")
            status_dot = "🟢" if h == "UP" else "🔴"
            jvm = metric_cache["jvm"].get(name, {})
            heap_used = jvm.get("jvm_memory_used_bytes", 0)
            heap_max = jvm.get("jvm_memory_max_bytes", 1)
            heap_pct = min(100, heap_used / max(heap_max, 1) * 100)
            cpu = metric_cache["system"].get(name, {}).get("system_cpu_usage", 0)
            cpu_pct = round(cpu * 100, 1) if cpu else "-"
            
            gauge_color = "bg-green-400" if heap_pct < 70 else ("bg-yellow-400" if heap_pct < 90 else "bg-red-400")
            
            cards_html += f"""
            <div class="card">
              <div class="card-header">
                <span class="status-dot">{status_dot}</span>
                <span class="card-title">{name}</span>
                <span class="card-port">:{port}</span>
                <span class="card-status {'text-green' if h=='UP' else 'text-red'}">{h}</span>
              </div>
              <div class="card-body">
                <div class="metric">💾 堆内存: {round(heap_used/1024/1024, 1)}MB / {round(heap_max/1024/1024, 1)}MB</div>
                <div class="gauge"><div class="gauge-fill {gauge_color}" style="width:{heap_pct}%"></div></div>
                <div class="metric">⚡ CPU: {cpu_pct}%</div>
                <div class="metric">📨 HTTP: {jvm.get('http_server_requests_seconds_count', '-')}</div>
                <div class="metric">🧵 线程: {jvm.get('jvm_threads_live_threads', '-')}</div>
              </div>
            </div>"""

        return f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>SmartAssistant 实时监控</title>
<script src="https://cdn.jsdelivr.net/npm/chart.js@4"></script>
<style>
  * {{ margin:0; padding:0; box-sizing:border-box; }}
  body {{ font-family: system-ui, sans-serif; background:#0f172a; color:#e2e8f0; padding:20px; }}
  .header {{ display:flex; justify-content:space-between; align-items:center; margin-bottom:20px; }}
  .header h1 {{ font-size:22px; background:linear-gradient(90deg,#38bdf8,#34d399); -webkit-background-clip:text; -webkit-text-fill-color:transparent; }}
  .status-bar {{ display:flex; gap:16px; margin-bottom:20px; }}
  .status-item {{ background:#1e293b; border-radius:8px; padding:12px 20px; }}
  .status-item .num {{ font-size:28px; font-weight:bold; }}
  .status-item .num.green {{ color:#34d399; }}
  .status-item .num.red {{ color:#ef4444; }}
  .status-item .label {{ font-size:12px; color:#94a3b8; }}
  .grid {{ display:grid; grid-template-columns:repeat(auto-fit,minmax(340px,1fr)); gap:12px; margin-bottom:20px; }}
  .card {{ background:#1e293b; border-radius:8px; padding:14px; border:1px solid #334155; transition:all 0.2s; }}
  .card:hover {{ border-color:#38bdf8; }}
  .card-header {{ display:flex; align-items:center; gap:6px; margin-bottom:10px; }}
  .status-dot {{ font-size:12px; }}
  .card-title {{ font-weight:bold; font-size:14px; }}
  .card-port {{ font-size:12px; color:#64748b; }}
  .card-status {{ font-size:11px; padding:2px 8px; border-radius:4px; margin-left:auto; }}
  .text-green {{ color:#34d399; background:rgba(52,211,153,0.1); }}
  .text-red {{ color:#ef4444; background:rgba(239,68,68,0.1); }}
  .card-body .metric {{ font-size:13px; padding:3px 0; }}
  .gauge {{ height:6px; background:#0f172a; border-radius:4px; margin:4px 0 8px 0; overflow:hidden; }}
  .gauge-fill {{ height:100%; border-radius:4px; transition:width 1s ease; }}
  .bg-green-400 {{ background:#34d399; }}
  .bg-yellow-400 {{ background:#facc15; }}
  .bg-red-400 {{ background:#ef4444; }}
  .charts {{ display:grid; grid-template-columns:1fr 1fr; gap:12px; margin-bottom:20px; }}
  .chart-box {{ background:#1e293b; border-radius:8px; padding:14px; border:1px solid #334155; }}
  .chart-box h3 {{ font-size:13px; color:#94a3b8; margin-bottom:8px; }}
  .chart-box canvas {{ max-height:200px; }}
  .footer {{ text-align:center; font-size:12px; color:#64748b; }}
  .links {{ display:flex; gap:10px; margin-bottom:20px; flex-wrap:wrap; }}
  .links a {{ background:#1e293b; border:1px solid #334155; border-radius:6px; padding:8px 14px; text-decoration:none; color:#e2e8f0; font-size:13px; transition:all 0.2s; }}
  .links a:hover {{ border-color:#38bdf8; }}
  @media (max-width:768px) {{ .charts {{ grid-template-columns:1fr; }} }}
</style>
</head>
<body>
<div class="header">
  <h1>📊 SmartAssistant 指标聚合仪表板</h1>
  <span style="font-size:12px;color:#64748b;">最后更新: {now[:19] if now != '-' else '-'}</span>
</div>

<div class="links">
  <a href="http://localhost:9411" target="_blank">🔗 Zipkin</a>
  <a href="http://localhost:8848/nacos" target="_blank">📡 Nacos</a>
  <a href="http://localhost:9099/metrics" target="_blank">📈 聚合 Metrics</a>
  <a href="file:///D:/workspace/SmartAssistant/monitor.html" target="_blank">📊 健康面板</a>
</div>

<div class="status-bar">
  <div class="status-item"><div class="num green">{up}</div><div class="label">✅ 运行中</div></div>
  <div class="status-item"><div class="num red">{total - up}</div><div class="label">❌ 宕机</div></div>
  <div class="status-item"><div class="num green">6</div><div class="label">📦 总数</div></div>
  <div class="status-item"><div class="num green">9099</div><div class="label">🔌 聚合端口</div></div>
</div>

<div class="grid">{cards_html}</div>

<div class="charts">
  <div class="chart-box"><h3>💾 JVM 堆内存使用率 (%)</h3><canvas id="chartHeap"></canvas></div>
  <div class="chart-box"><h3>⚡ CPU 使用率 (%)</h3><canvas id="chartCpu"></canvas></div>
</div>

<div class="footer">
  <p>聚合器运行在 :9099 · 每 {SCRAPE_INTERVAL}s 抓取 · 数据保留 {DATA_RETENTION_MINUTES} 分钟</p>
  <p>注：离线环境临时方案 · 恢复网络后可用 Docker 版 Grafana :3000 + Prometheus :9090 替换</p>
</div>

<script>
async function loadChart(id, metric, label, unit, isPercent=false) {{
  try {{
    const r = await fetch('/api/ts/' + metric);
    const data = await r.json();
    const series = Object.entries(data);
    if (series.length === 0) return;
    const labels = series[0][1].map(p => new Date(p.t).toLocaleTimeString());
    const datasets = series.map(([svc, pts]) => ({{
      label: svc,
      data: pts.map(p => isPercent ? p.v * 100 : p.v),
      borderColor: ['#38bdf8','#34d399','#f472b6','#facc15','#a78bfa','#fb923c'][series.indexOf([svc,pts]) % 6],
      borderWidth: 1.5,
      tension: 0.3,
      fill: false,
      pointRadius: 0
    }}));
    new Chart(document.getElementById(id), {{
      type: 'line',
      data: {{ labels, datasets }},
      options: {{
        responsive: true, maintainAspectRatio: false,
        animation: false,
        scales: {{ y: {{ beginAtZero: true, max: isPercent ? 100 : undefined }} }},
        plugins: {{ legend: {{ display: true, position: 'bottom', labels: {{ boxWidth: 10, font: {{ size: 10 }} }} }} }}
      }}
    }});
  }} catch(e) {{ console.log('Chart load error:', e); }}
}}

loadChart('chartHeap', 'jvm_memory_used_bytes', '堆内存', 'bytes');
loadChart('chartCpu', 'system_cpu_usage', 'CPU', '%', true);
</script>
</body>
</html>"""
        
if __name__ == "__main__":
    init_db()
    print(f"[Aggregator] SmartAssistant 指标聚合器启动在 :{PORT}")
    print(f"[Aggregator] 仪表板: http://localhost:{PORT}/")
    print(f"[Aggregator] 统一Metrics: http://localhost:{PORT}/metrics")
    print(f"[Aggregator] 抓取间隔: {SCRAPE_INTERVAL}s")
    
    # 启动后台抓取线程
    t = threading.Thread(target=scrape_loop, daemon=True)
    t.start()
    
    # 立即抓取一次
    time.sleep(1)
    scrape_all()
    
    # 启动 HTTP 服务
    server = http.server.HTTPServer(("0.0.0.0", PORT), AggregatorHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("[Aggregator] 关闭...")
        server.server_close()
