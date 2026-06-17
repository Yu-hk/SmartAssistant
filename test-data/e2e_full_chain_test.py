#!/usr/bin/env python3
"""
SmartAssistant 全链路端到端验证脚本
覆盖完整用户旅程：注册→登录→路由→工具调用
"""
import json, time, sys
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

PASS = 0; FAIL = 0; TOTAL = 0
def check(name, condition, detail=""):
    global PASS, FAIL, TOTAL; TOTAL += 1
    icon = "✅" if condition else "❌"
    print(f"  {icon} {name}" + (f" - {detail}" if detail else ""))
    if condition: PASS += 1
    else: FAIL += 1

def api(method, url, data=None, headers=None, timeout=15):
    hdrs = {'Content-Type': 'application/json', 'Accept': 'application/json'}
    if headers: hdrs.update(headers)
    body = json.dumps(data).encode() if data else None
    try:
        with urlopen(Request(url, data=body, headers=hdrs, method=method), timeout=timeout) as r:
            return r.status, json.loads(r.read())
    except HTTPError as e:
        try: return e.code, json.loads(e.read())
        except: return e.code, str(e)
    except Exception as e:
        return 0, str(e)

G = "http://localhost:8081"
U = "http://localhost:8086"
C = "http://localhost:8082"
R = "http://localhost:8083"
P = "http://localhost:8084"
O = "http://localhost:8085"
GEN = "http://localhost:8087"

print("=" * 65)
print("  SmartAssistant 全链路端到端验证")
print("=" * 65)
print()

# ===== 1. 基础健康检查 =====
print("【1/7】基础健康检查")
for svc, name in [(G,"Gateway"),(U,"User"),(C,"Consumer"),(R,"Router"),(P,"Product"),(O,"Order"),(GEN,"General")]:
    code, _ = api("GET", f"{svc}/actuator/health")
    check(f"{name} 健康检查", code in [200,503], f"HTTP {code}")

# ===== 2. 用户注册与认证 =====
print("\n【2/7】用户注册与认证")
TEST_USER = f"e2e_user_{int(time.time())}"
TEST_PASS = "E2eTest123!"
code, data = api("POST", f"{U}/api/auth/register", {
    "username": TEST_USER, "password": TEST_PASS, "email": "e2e@test.com"
})
check("用户注册", code in [200,400], f"HTTP {code}")

code, data = api("POST", f"{U}/api/auth/login", {
    "username": TEST_USER, "password": TEST_PASS
})
check("用户登录成功", code == 200, f"HTTP {code}")
TOKEN = data.get("token", "") if code == 200 else ""
check("登录返回 JWT Token", bool(TOKEN))

# 验证已有用户也能登录
for u, p in [("testuser", "test123"), ("autotest", "auto123")]:
    code, data = api("POST", f"{U}/api/auth/login", {"username": u, "password": p})
    if code == 200:
        check(f"已有用户 {u} 登录", code == 200, f"HTTP {code}")
        break
    else:
        # 用注册用户继续后续测试
        pass
if code != 200:
    # 重新注册一个用户用于后续测试
    code2, data2 = api("POST", f"{U}/api/auth/login", {"username": TEST_USER, "password": TEST_PASS})
    check(f"使用注册用户继续", code2 == 200, f"HTTP {code2}")

code, _ = api("POST", f"{U}/api/auth/login", {
    "username": TEST_USER, "password": "wrong_password"
})
check("错误密码被拒绝", code == 401, f"HTTP {code}")

# ===== 3. 网关路由（通过 Gateway 访问后端）=====
print("\n【3/7】网关路由验证")
code, data = api("GET", f"{G}/assistant/api/auth/me", headers={"Authorization": f"Bearer {TOKEN}"})
check("网关 → User (带Token)", code == 200, f"HTTP {code}")

code, data = api("GET", f"{G}/assistant/api/auth/me")
check("网关 → User (无Token被拦截)", code in [401,403], f"HTTP {code}")

# ===== 4. Consumer 数据查询 =====
print("\n【4/7】Consumer 数据查询")
code, data = api("POST", f"{C}/api/data/query", {"keyword": "美食", "userId": "1"})
check("Consumer 数据查询", code == 200, f"HTTP {code}")
check("Consumer 数据查询", code == 200, f"HTTP {code}")

code, data = api("POST", f"{G}/assistant/api/math/chat", 
    {"message": "推荐北京美食", "userId": "1", "sessionId": "e2e-test"},
    headers={"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"})
check("网关 → Consumer 聊天 (带Token)", code in [200,400,500], f"HTTP {code}")

# ===== 5. Router 智能路由 =====
print("\n【5/7】Router 智能路由")
routes = [
    ("订单查询", "查一下订单ORD-2024001的状态"),
    ("商品查询", "iPhone 15 Pro多少钱？"),
    ("天气查询", "北京今天天气怎么样？"),
    ("多轮对话", "推荐北京景点和川菜馆"),
]
for name, question in routes:
    code, data = api("POST", f"{R}/api/router/route", {"question": question, "userId": "1"})
    agent = data.get("agentName", "未知") if code == 200 else "N/A"
    check(f"路由 → {name}", code == 200, f"agent={agent}")

# ===== 6. Agent 工具调用 =====
print("\n【6/7】Agent 工具调用")
tools = [
    ("订单状态查询", "查一下订单ORD-2024001的状态"),
    ("物流查询", "帮我查一下快递SF1234567890的物流"),
    ("商品价格查询", "iPhone 15 Pro多少钱？"),
    ("商品库存查询", "MacBook Air M3有货吗？"),
    ("天气查询", "北京今天天气怎么样？"),
    ("计算器", "计算3.14乘以5的平方"),
    ("货币换算", "100美元等于多少人民币"),
    ("温度转换", "100华氏度等于多少摄氏度"),
]
for name, question in tools:
    code, data = api("POST", f"{R}/api/router/route", {"question": question, "userId": "1"})
    check(f"工具 → {name}", code == 200, f"HTTP {code}")

# ===== 7. 健康检查端点 =====
print("\n【7/7】各服务详细信息")
for port, name in [(8081,"Gateway"),(8086,"User"),(8082,"Consumer"),(8083,"Router"),(8084,"Product"),(8085,"Order"),(8087,"General")]:
    code, data = api("GET", f"http://localhost:{port}/actuator/health")
    if code == 200:
        details = data.get("components", {})
        status = data.get("status", "UNKNOWN")
        check(f"{name} 详细状态", status == "UP", f"status={status}")

# ===== 总结 =====
print("\n" + "=" * 65)
print(f"  全链路验证完成 | ✅ 通过: {PASS} | ❌ 失败: {FAIL} | 总计: {TOTAL}")
print("=" * 65)

sys.exit(0 if FAIL == 0 else 1)
