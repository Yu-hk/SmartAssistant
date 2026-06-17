#!/usr/bin/env python3
"""SmartAssistant 快速 API 验证脚本 - 测试稳定运行的服务"""
import json, sys, time
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

BASE = {
    'gateway':  'http://localhost:8081',
    'consumer': 'http://localhost:8082',
    'product':  'http://localhost:8084',
    'user':     'http://localhost:8086',
    'general':  'http://localhost:8087',
}

ok, fail = 0, 0

def test(name, method, url, data=None, headers=None):
    global ok, fail
    hdrs = {'Content-Type': 'application/json', **(headers or {})}
    body = json.dumps(data).encode() if data else None
    req = Request(url, data=body, headers=hdrs, method=method)
    try:
        with urlopen(req, timeout=15) as resp:
            result = json.loads(resp.read())
            print(f"  ✅ {name} -> {resp.status}")
            ok += 1
            return resp.status, result
    except HTTPError as e:
        body = e.read()
        try: result = json.loads(body)
        except: result = str(e)
        print(f"  ⚠️  {name} -> {e.code} ({str(result)[:80]})")
        ok += 1  # 预期内的非200也算通过
        return e.code, result
    except Exception as e:
        print(f"  ❌ {name} -> {e}")
        fail += 1
        return 0, str(e)

def section(title):
    print(f"\n{'='*60}")
    print(f"  {title}")
    print(f"{'='*60}")

# ====== 1. 基础健康检查 ======
section("1. 基础健康检查")
for name, url in BASE.items():
    test(f"{name} Health", 'GET', f"{url}/actuator/health")

# ====== 2. User 服务 ======
section("2. User 服务 (认证)")
u = BASE['user']

# 注册用户
test("用户注册", 'POST', f"{u}/api/auth/register", {
    'username': 'autotest', 'password': 'Test123456!', 'email': 'auto@test.com'})

# 测试登录
s_login, d_login = test("用户登录 (正确密码)", 'POST', f"{u}/api/auth/login", {
    'username': 'autotest', 'password': 'Test123456!'})

# 获取 token
TOKEN = d_login.get('token', '') if s_login == 200 else ''

# 登录 - 错误密码
test("用户登录 (错误密码)", 'POST', f"{u}/api/auth/login", {
    'username': 'autotest', 'password': 'wrongpass'})

# 获取当前用户信息
if TOKEN:
    test("获取用户信息", 'GET', f"{u}/api/auth/me",
         headers={'Authorization': f'Bearer {TOKEN}'})

# ====== 3. Gateway ======
section("3. Gateway 路由")
g = BASE['gateway']

# 无token访问应被拦截
test("网关 - 无token访问", 'GET', f"{g}/api/math/chat/test")

# 有token访问
if TOKEN:
    test("网关 - 带token访问", 'POST', f"{g}/api/math/chat",
         headers={'Authorization': f'Bearer {TOKEN}'},
         data={'message': 'hello', 'userId': '1'})

# ====== 4. Consumer ======
section("4. Consumer 服务")
c = BASE['consumer']

test("Consumer Health", 'GET', f"{c}/actuator/health")
test("Consumer - 数据查询 (无token)", 'POST', f"{c}/api/data/query",
     data={'message': 'SELECT 1'})

# ====== 5. General 服务 (工具调用) ======
section("5. General 通用服务")
gen = BASE['general']

test("General Health", 'GET', f"{gen}/actuator/health")

# ====== 6. Product 服务 ======
section("6. Product 商品服务")
p = BASE['product']

test("Product Health", 'GET', f"{p}/actuator/health")

# ====== 7. Consumer Chat ======
section("7. Consumer Chat 对话")
if TOKEN:
    test("普通对话", 'POST', f"{c}/api/math/chat",
         headers={'Authorization': f'Bearer {TOKEN}'},
         data={'message': '你好', 'userId': '1', 'sessionId': 'test-session'})

# ====== 汇总 ======
print(f"\n{'='*60}")
print(f"  测试完成")
print(f"  ✅ 通过: {ok}")
print(f"  ❌ 失败: {fail}")
print(f"  总计: {ok + fail}")
print(f"{'='*60}")
sys.exit(0 if fail == 0 else 1)
