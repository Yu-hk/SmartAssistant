#!/usr/bin/env python3
"""
SmartAssistant 全流程 E2E 测试脚本
测试范围：Auth → Analytics → Order(Agent/TestTools) → Product → Router → Embedding
"""

import json, sys, time, uuid, traceback
from datetime import datetime
from urllib.request import Request, urlopen
from urllib.error import URLError, HTTPError
from urllib.parse import urlencode, quote

# ============ Config ============
GATEWAY = "http://localhost:8081"
USER_SVC = "http://localhost:8086"
CONSUMER_SVC = "http://localhost:8082"
ROUTER_SVC = "http://localhost:8083"
PRODUCT_SVC = "http://localhost:8084"
ORDER_SVC = "http://localhost:8085"
EMBEDDING_SVC = "http://localhost:8091"

BASE = GATEWAY  # 统一走网关

token = None   # 当前登录用户的 JWT token
user_id = None # 当前用户 ID
test_results = [] # 所有结果

PASS = "✅ PASS"
FAIL = "❌ FAIL"
WARN = "⚠️ WARN"

def log(name, status, detail, input_data=None, output_data=None):
    test_results.append({
        "name": name, "status": status,
        "detail": detail,
        "input": input_data, "output": output_data
    })
    print(f"  {status} | {name}")
    if detail and status != PASS:
        print(f"       {detail}")

def http_request(method, url, body=None, headers=None, timeout=15):
    if headers is None:
        headers = {}
    headers.setdefault("Content-Type", "application/json")
    if token and "Authorization" not in headers:
        headers["Authorization"] = f"Bearer {token}"
    data = json.dumps(body).encode("utf-8") if body else None
    req = Request(url, data=data, headers=headers, method=method)
    try:
        resp = urlopen(req, timeout=timeout)
        raw = resp.read().decode("utf-8")
        status = resp.status
        ct = resp.headers.get("Content-Type", "")
        if "application/json" in ct or raw.startswith("{"):
            return status, json.loads(raw)
        return status, raw
    except HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        try:
            return e.code, json.loads(raw)
        except:
            return e.code, raw
    except URLError as e:
        return 0, {"error": str(e.reason)}
    except Exception as e:
        return 0, {"error": str(e)}

def wait_for_service(url, name, retries=15, delay=2):
    print(f"\n⏳ 等待 {name} 启动 ({url})...")
    for i in range(retries):
        try:
            status, data = http_request("GET", f"{url}/actuator/health", timeout=3)
            if status == 200:
                print(f"  ✅ {name} 已就绪 (第{i+1}次尝试)")
                return True
        except:
            pass
        time.sleep(delay)
    print(f"  ⚠️ {name} 未就绪，继续测试")
    return False

# ============================================================
#  测试用例
# ============================================================

def test_health_checks():
    """TC-001 ~ TC-008: 各服务健康检查"""
    print("\n" + "="*60)
    print("🔍 测试组 1: 服务健康检查")
    print("="*60)

    endpoints = [
        ("TC-001 Gateway", GATEWAY, "/actuator/health"),
        ("TC-002 User Service", USER_SVC, "/actuator/health"),
        ("TC-003 Consumer Service", CONSUMER_SVC, "/actuator/health"),
        ("TC-004 Router Service", ROUTER_SVC, "/actuator/health"),
        ("TC-005 Product Service", PRODUCT_SVC, "/actuator/health"),
        ("TC-006 Order Service", ORDER_SVC, "/actuator/health"),
        ("TC-007 Embedding Service", EMBEDDING_SVC, "/actuator/health"),
    ]

    for name, base, path in endpoints:
        url = f"{base}{path}"
        try:
            status, data = http_request("GET", url, timeout=5)
            if status == 200:
                log(name, PASS, "", {"url": url}, data)
            elif status == 404:
                # Try without actuator
                status2, data2 = http_request("GET", f"{base}/health", timeout=3)
                if status2 in (200, 404):
                    log(name, WARN if status2 != 200 else PASS,
                        "通过 /health 检查", {"url": f"{base}/health"}, data2)
                else:
                    log(name, FAIL, f"HTTP {status}", {"url": url}, data)
            else:
                log(name, FAIL, f"HTTP {status}", {"url": url}, data)
        except Exception as e:
            log(name, FAIL, str(e), {"url": url}, str(e))

def test_auth_flow():
    """TC-009~TC-012: 用户认证全流程"""
    global token, user_id
    print("\n" + "="*60)
    print("🔐 测试组 2: 用户认证流程")
    print("="*60)

    test_username = f"testuser_{int(time.time())%100000}"

    # TC-009: 注册
    url = f"{BASE}/assistant/api/auth/register"
    body = {"username": test_username, "password": "Test1234!", "email": f"{test_username}@test.com"}
    status, data = http_request("POST", url, body)
    if status == 200:
        log("TC-009 用户注册", PASS, "", body, data)
    else:
        log("TC-009 用户注册", WARN if "已存在" in str(data) else FAIL,
            str(data), body, data)

    # TC-010: 登录
    url = f"{BASE}/assistant/api/auth/login"
    body = {"username": test_username, "password": "Test1234!"}
    status, data = http_request("POST", url, body)
    if status == 200 and isinstance(data, dict) and ("token" in data or "data" in data):
        resp_data = data.get("data", data)
        token = resp_data.get("token") or resp_data.get("accessToken") or ""
        user_id = resp_data.get("userId") or resp_data.get("user_id") or ""
        log("TC-010 用户登录", PASS, f"token={token[:30]}...", body,
            {"token_preview": token[:30]+"...", "user_id": user_id})
    else:
        log("TC-010 用户登录", FAIL, str(data), body, data)

    # TC-011: 获取当前用户信息
    if token:
        url = f"{BASE}/assistant/api/auth/me"
        status, data = http_request("GET", url)
        if status == 200:
            log("TC-011 获取用户信息", PASS, "", {}, data)
        else:
            log("TC-011 获取用户信息", FAIL, str(data), {}, data)
    else:
        log("TC-011 获取用户信息", FAIL, "无token，跳过")

def test_analytics():
    """TC-013~TC-018: 分析面板 API"""
    print("\n" + "="*60)
    print("📊 测试组 3: 分析面板 API")
    print("="*60)

    analytics_tests = [
        ("TC-013 面板统计", "/assistant/api/analytics/stats"),
        ("TC-014 热门建议", "/assistant/api/analytics/top-suggestions"),
        ("TC-015 A/B测试", "/assistant/api/analytics/ab-test"),
        ("TC-016 用户偏好", "/assistant/api/analytics/preference-distribution"),
        ("TC-017 点击趋势", "/assistant/api/analytics/click-trend"),
        ("TC-018 Prompt监控", "/assistant/api/prompt-monitoring/stats"),
    ]

    for name, path in analytics_tests:
        url = f"{BASE}{path}"
        try:
            status, data = http_request("GET", url, timeout=10)
            if status in (200, 302):
                log(name, PASS if not (isinstance(data, dict) and "error" in data) else WARN,
                    "", {}, data)
            else:
                log(name, WARN, f"HTTP {status}", {"url": url}, data)
        except Exception as e:
            log(name, FAIL, str(e), {"url": url}, str(e))

def test_sql_monitoring():
    """TC-019~TC-022: SQL 监控"""
    print("\n" + "="*60)
    print("🗄️  测试组 4: SQL 监控")
    print("="*60)

    tests = [
        ("TC-019 SQL健康检查", "/assistant/api/monitor/sql/health", "GET", None),
        ("TC-020 SQL审查", "/assistant/api/monitor/sql/review", "POST",
         {"sql": "SELECT * FROM orders WHERE order_id = 'ORD-2024001'"}),
        ("TC-021 SQL执行计划", "/assistant/api/monitor/sql/explain", "POST",
         {"sql": "SELECT * FROM orders WHERE user_id = 1 LIMIT 10"}),
        ("TC-022 索引检查", "/assistant/api/monitor/sql/check-indexes", "POST", None),
    ]

    for name, path, method, body in tests:
        url = f"{BASE}{path}"
        try:
            status, data = http_request(method, url, body, timeout=10)
            if status in (200, 302):
                log(name, PASS, "", {"url": url, "body": body}, data)
            else:
                log(name, WARN, f"HTTP {status}", {"url": url, "body": body}, data)
        except Exception as e:
            log(name, FAIL, str(e), {"url": url}, str(e))

def test_order_tool():
    """TC-023~TC-033: 订单工具接口测试 (全生命周期)"""
    print("\n" + "="*60)
    print("📦 测试组 5: 订单全生命周期")
    print("="*60)

    order_id = f"E2E-{int(time.time()%1000000)}"
    tracking_no = f"SF{int(time.time())%10000000000}"

    # TC-023 创建订单
    url = f"{ORDER_SVC}/api/test/order/create"
    params = urlencode({"userId": "1", "productName": "E2E测试商品", "amount": "2999.00",
                        "contactName": "测试用户", "contactPhone": "13800138000",
                        "shippingAddress": "测试地址", "productType": "电子产品"})
    status, data = http_request("POST", f"{url}?{params}", timeout=10)
    log("TC-023 创建订单", PASS if "成功" in str(data) or "order_id" in str(data) else WARN,
        str(data)[:200], {"url": f"{url}?{params}"}, data)

    # Extract order_id from response
    if isinstance(data, str):
        import re
        match = re.search(r'order_id[=:]\s*(\S+)', str(data))
        if match:
            order_id = match.group(1).strip().rstrip('.,;')
    elif isinstance(data, dict):
        extracted = data.get("data", data)
        order_id = extracted.get("orderId") or extracted.get("order_id") or order_id

    # TC-024 查询订单
    url = f"{ORDER_SVC}/api/test/order/query?orderId={order_id}"
    status, data = http_request("GET", url, timeout=10)
    log("TC-024 查询订单", PASS if status in (200, 302) else FAIL,
        str(data)[:200], {"orderId": order_id}, data)

    # TC-025 支付
    url = f"{ORDER_SVC}/api/test/order/pay?orderId={order_id}&paymentMethod={quote('微信支付')}"
    status, data = http_request("POST", url, timeout=10)
    log("TC-025 支付订单", PASS if "成功" in str(data) or status == 200 else WARN,
        str(data)[:200], {"orderId": order_id}, data)

    # TC-026 确认支付
    url = f"{ORDER_SVC}/api/test/order/confirm?orderId={order_id}&actionType=payment"
    status, data = http_request("POST", url, timeout=10)
    log("TC-026 确认支付", PASS if "成功" in str(data) or status == 200 else WARN,
        str(data)[:200], {"orderId": order_id}, data)

    # TC-027 发货
    url = f"{ORDER_SVC}/api/test/order/ship?orderId={order_id}&carrier={quote('顺丰速运')}&trackingNo={tracking_no}"
    status, data = http_request("POST", url, timeout=10)
    log("TC-027 发货", PASS if "成功" in str(data) or status == 200 else WARN,
        str(data)[:200], {"orderId": order_id}, data)

    # TC-028 物流查询
    url = f"{ORDER_SVC}/api/test/order/track?trackingNumber={tracking_no}"
    status, data = http_request("GET", url, timeout=10)
    log("TC-028 物流查询", PASS if status == 200 else WARN,
        str(data)[:200], {"trackingNumber": tracking_no}, data)

    # TC-029 确认收货
    url = f"{ORDER_SVC}/api/test/order/deliver?orderId={order_id}"
    status, data = http_request("POST", url, timeout=10)
    log("TC-029 确认收货", PASS if "成功" in str(data) or status == 200 else WARN,
        str(data)[:200], {"orderId": order_id}, data)

    # TC-031 退款
    url = f"{ORDER_SVC}/api/test/order/refund?orderId={order_id}&reason={quote('E2E测试退款')}"
    status, data = http_request("POST", url, timeout=10)
    log("TC-031 退款申请", PASS if "成功" in str(data) or status == 200 else WARN,
        str(data)[:200], {"orderId": order_id}, data)

    # TC-032 Text2SQL
    url = f"{ORDER_SVC}/api/test/order/text2sql?question={quote('查询用户ID为1的所有订单')}"
    status, data = http_request("POST", url, timeout=10)
    log("TC-032 Text2SQL 查询", PASS if status == 200 else WARN,
        str(data)[:200], {"question": "查询用户ID为1的所有订单"}, data)

    # TC-033 取消订单 (创建另一个订单后取消)
    params2 = urlencode({"userId": "1", "productName": "取消测试商品", "amount": "199.00",
                         "contactName": "测试用户", "contactPhone": "13800138000",
                         "shippingAddress": "测试地址", "productType": "其他"})
    url2 = f"{ORDER_SVC}/api/test/order/create?{params2}"
    status2, data2 = http_request("POST", url2, timeout=10)
    cancel_order_id = order_id
    if isinstance(data2, str):
        match = re.search(r'order_id[=:]\s*(\S+)', str(data2))
        if match:
            cancel_order_id = match.group(1).strip().rstrip('.,;')
    url3 = f"{ORDER_SVC}/api/test/order/cancel?orderId={cancel_order_id}&reason={quote('测试取消')}"
    status3, data3 = http_request("POST", url3, timeout=10)
    log("TC-033 取消订单", PASS if "成功" in str(data3) or status3 == 200 else WARN,
        str(data3)[:200], {"orderId": cancel_order_id}, data3)

def test_order_agent():
    """TC-034~TC-035: 订单 Agent 智能处理"""
    print("\n" + "="*60)
    print("🤖 测试组 6: 订单 Agent")
    print("="*60)

    questions = [
        "TC-034 Agent: 订单查询",
        "TC-035 Agent: 物流查询",
    ]
    queries = [
        "查询订单 ORD-2024001 的状态",
        "查询快递 SF1234567890 的物流轨迹",
    ]

    for name, query in zip(questions, queries):
        url = f"{ORDER_SVC}/api/order/agent/process"
        try:
            status, data = http_request("POST", url, {"question": query}, timeout=30)
            if status == 200:
                log(name, PASS, "", {"question": query}, str(data)[:300])
            else:
                log(name, WARN, f"HTTP {status}", {"question": query}, str(data)[:200])
        except Exception as e:
            log(name, FAIL, str(e), {"question": query}, str(e))

def test_router():
    """TC-036~TC-039: 路由服务"""
    print("\n" + "="*60)
    print("🧭 测试组 7: 路由服务")
    print("="*60)

    # TC-036 健康检查
    url = f"{ROUTER_SVC}/api/router/health"
    status, data = http_request("GET", url, timeout=10)
    log("TC-036 Router健康", PASS if status == 200 else FAIL, str(data)[:100], {}, data)

    # TC-037 Agent列表
    url = f"{ROUTER_SVC}/api/router/agents"
    status, data = http_request("GET", url, timeout=10)
    if status == 200:
        agent_count = len(data) if isinstance(data, list) else 0
        log("TC-037 Agent列表", PASS, f"发现 {agent_count} 个 Agent", {}, data)
    else:
        log("TC-037 Agent列表", WARN, str(data)[:200], {}, data)

    # TC-038 路由决策
    url = f"{ROUTER_SVC}/api/router/route"
    body = {"userId": 1, "question": "我想查询订单状态", "sessionId": str(uuid.uuid4()), "requestId": str(uuid.uuid4())}
    status, data = http_request("POST", url, body, timeout=15)
    log("TC-038 路由决策", PASS if status == 200 else WARN, str(data)[:200], body, data)

    # TC-039 经验列表
    url = f"{ROUTER_SVC}/api/experience/stats"
    status, data = http_request("GET", url, timeout=10)
    log("TC-039 经验统计", PASS if status == 200 else WARN, str(data)[:200], {}, data)

def test_embedding():
    """TC-040~TC-042: 向量嵌入服务"""
    print("\n" + "="*60)
    print("🧬 测试组 8: 向量嵌入服务")
    print("="*60)

    # TC-040 健康
    url = f"{EMBEDDING_SVC}/api/embedding/health"
    status, data = http_request("GET", url, timeout=10)
    log("TC-040 Embedding健康", PASS if status == 200 else WARN, str(data)[:100], {}, data)

    # TC-041 单条嵌入
    url = f"{EMBEDDING_SVC}/api/embedding"
    body = {"text": "测试文本"}
    status, data = http_request("POST", url, body, timeout=30)
    log("TC-041 单条文本嵌入", PASS if status == 200 else FAIL,
        str(data)[:150], body, data)

    # TC-042 批量嵌入
    url = f"{EMBEDDING_SVC}/api/embedding/batch"
    body = {"texts": ["第一个测试文本", "第二个测试文本", "第三个测试文本"]}
    status, data = http_request("POST", url, body, timeout=30)
    log("TC-042 批量文本嵌入", PASS if status == 200 else FAIL,
        str(data)[:150], body, data)

def test_product():
    """TC-043~TC-044: 商品咨询"""
    print("\n" + "="*60)
    print("🛒 测试组 9: 商品咨询")
    print("="*60)

    # TC-043 同步咨询
    url = f"{PRODUCT_SVC}/product/stream/chat/sync?message={quote('推荐一款笔记本电脑')}"
    status, data = http_request("POST", url, timeout=30)
    log("TC-043 商品同步咨询", PASS if status == 200 else WARN,
        str(data)[:200], {"message": "推荐一款笔记本电脑"}, data)

def test_database():
    """TC-045~TC-046: 数据库验证"""
    print("\n" + "="*60)
    print("🗄️  测试组 10: 数据库验证")
    print("="*60)

    import subprocess

    # TC-045 表结构
    cmd = ["docker", "exec", "smart-postgres", "psql", "-U", "postgres", "-d", "a2a_system", "-c", "\dt"]
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
        tables = [l.strip() for l in result.stdout.split('\n') if '|' in l and 'table' not in l.lower()]
        log("TC-045 PostgreSQL 表结构", PASS, f"共 {len(tables)} 张表", {}, result.stdout)
    except Exception as e:
        log("TC-045 PostgreSQL 表结构", FAIL, str(e), {}, str(e))

    # TC-046 数据统计
    cmd = ["docker", "exec", "smart-postgres", "psql", "-U", "postgres", "-d", "a2a_system", "-c",
           "SELECT 'orders' as tbl, count(*) FROM orders UNION ALL SELECT 'order_refunds', count(*) FROM order_refunds UNION ALL SELECT 'order_logistics', count(*) FROM order_logistics UNION ALL SELECT 'products', count(*) FROM products UNION ALL SELECT 'experience_embeddings', count(*) FROM experience_embeddings;"]
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
        log("TC-046 数据统计", PASS, "", {}, result.stdout)
    except Exception as e:
        log("TC-046 数据统计", FAIL, str(e), {}, str(e))

# ============================================================
# 主流程
# ============================================================

def print_report():
    """输出完整测试报告"""
    passed = sum(1 for t in test_results if t["status"] == PASS)
    failed = sum(1 for t in test_results if FAIL in t["status"])
    warned = sum(1 for t in test_results if WARN in t["status"])

    print("\n\n")
    print("="*70)
    print("  SmartAssistant 全流程 E2E 测试报告")
    print(f"  执行时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print("="*70)

    print(f"\n📊 汇总: 总计 {len(test_results)}  |  {PASS}: {passed}  |  {WARN}: {warned}  |  {FAIL}: {failed}\n")

    print("-"*70)
    for t in test_results:
        status_icon = "✅" if t["status"] == PASS else ("⚠️" if WARN in t["status"] else "❌")
        print(f"  {status_icon} {t['name']:<45} {t['status']}")
        if t["detail"] and t["status"] != PASS:
            print(f"     📝 {t['detail']}")
    print("-"*70)

    # 详细输入输出
    print("\n\n📋 详细输入输出:")
    print("="*70)
    for t in test_results:
        print(f"\n{'─'*70}")
        print(f"  [{t['status'][0]}][{t['name']}]")
        if t["input"]:
            inp_str = json.dumps(t["input"], ensure_ascii=False, indent=2)
            if len(inp_str) > 500:
                inp_str = inp_str[:500] + "..."
            print(f"  📥 输入: {inp_str}")
        if t["output"]:
            if isinstance(t["output"], str):
                out_str = t["output"]
            else:
                out_str = json.dumps(t["output"], ensure_ascii=False, indent=2)
            if len(out_str) > 500:
                out_str = out_str[:500] + "..."
            print(f"  📤 输出: {out_str}")
        if t["detail"] and t["status"] != PASS:
            print(f"  ℹ️  说明: {t['detail']}")
    print(f"\n{'='*70}")
    print(f"  测试结束: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print(f"  总计 {len(test_results)}  |  {PASS}: {passed}  |  {WARN}: {warned}  |  {FAIL}: {failed}")
    print("="*70)

if __name__ == "__main__":
    print("="*60)
    print("  SmartAssistant 全流程 E2E 测试")
    print(f"  启动时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print("="*60)

    # Step 1: 等待服务
    print("\n🔌 步骤 1: 验证服务存活...")
    wait_for_service("http://localhost:8081", "Gateway")
    wait_for_service("http://localhost:8086", "UserService")
    wait_for_service("http://localhost:8085", "OrderService")
    wait_for_service("http://localhost:8084", "ProductService")
    wait_for_service("http://localhost:8083", "RouterService")
    wait_for_service("http://localhost:8091", "EmbeddingService")

    # Step 2: 执行测试
    print("\n🚀 步骤 2: 执行测试...")

    test_health_checks()
    test_auth_flow()
    test_analytics()
    test_sql_monitoring()
    test_order_tool()
    test_order_agent()
    test_router()
    test_embedding()
    test_product()
    test_database()

    # Final: 输出报告
    print("\n📋 步骤 3: 生成测试报告...")
    print_report()

    # Save report to file
    report_path = "D:/workspace/SmartAssistant/e2e_test_report.json"
    report_data = {
        "summary": {
            "total": len(test_results),
            "passed": sum(1 for t in test_results if t["status"] == PASS),
            "warned": sum(1 for t in test_results if WARN in t["status"]),
            "failed": sum(1 for t in test_results if FAIL in t["status"]),
            "timestamp": datetime.now().isoformat()
        },
        "results": test_results
    }
    with open(report_path, "w", encoding="utf-8") as f:
        json.dump(report_data, f, ensure_ascii=False, indent=2)
    print(f"\n📁 报告已保存: {report_path}")
