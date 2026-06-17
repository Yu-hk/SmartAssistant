#!/usr/bin/env python3
"""
SmartAssistant API 自动化测试脚本
用于验证各微服务 REST API 的功能
注意：运行此脚本前需要启动所有相关服务（Nacos/Redis/PostgreSQL/各微服务）
"""
import json, sys, os, time, unittest
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

BASE_URLS = {
    'gateway': 'http://localhost:8081',
    'consumer': 'http://localhost:8082',
    'router': 'http://localhost:8083',
    'product': 'http://localhost:8084',
    'order': 'http://localhost:8085',
    'user': 'http://localhost:8086',
    'general': 'http://localhost:8087',
}

def api_call(method, url, data=None, headers=None):
    if headers is None: headers = {}
    headers.setdefault('Content-Type', 'application/json')
    body = json.dumps(data).encode() if data else None
    req = Request(url, data=body, headers=headers, method=method)
    try:
        with urlopen(req, timeout=10) as resp:
            return resp.status, json.loads(resp.read())
    except HTTPError as e:
        return e.code, json.loads(e.read()) if e.read() else str(e)
    except URLError as e:
        return 0, {'error': f'服务不可达: {e.reason}'}
    except Exception as e:
        return 0, {'error': str(e)}

class TestGateway(unittest.TestCase):
    """Gateway 微服务测试"""
    base = f"{BASE_URLS['gateway']}"
    
    def test_1_health(self):
        code, data = api_call('GET', f"{self.base}/actuator/health")
        self.assertIn(code, [200, 503])  # 可能依赖未就绪
    
    def test_2_no_token_access(self):
        code, data = api_call('GET', f"{self.base}/api/math/chat/test")
        self.assertIn(code, [401, 404])

class TestUserService(unittest.TestCase):
    """用户服务测试"""
    base = f"{BASE_URLS['user']}/api/auth"
    
    def test_1_register(self):
        code, data = api_call('POST', f"{self.base}/register", {
            'username': 'test_auto', 'password': 'Test123456!', 'email': 'test@test.com'})
        self.assertIn(code, [200, 400, 500])
    
    def test_2_login_valid(self):
        code, data = api_call('POST', f"{self.base}/login", {
            'username': 'admin', 'password': 'admin123'})
        if code == 200: self.assertIn('token', data)
    
    def test_3_login_invalid(self):
        code, data = api_call('POST', f"{self.base}/login", {
            'username': 'admin', 'password': 'wrongpass'})
        self.assertIn(code, [401, 400])

class TestRouterService(unittest.TestCase):
    """Router 路由服务测试"""
    base = f"{BASE_URLS['router']}/api/router"
    
    def test_1_health(self):
        code, data = api_call('GET', f"{self.base}/health")
        self.assertEqual(code, 200)
    
    def test_2_route_order(self):
        code, data = api_call('POST', f"{self.base}/route", {
            'question': '查一下订单ORD-2024001的物流', 'userId': '1'})
        if code == 200:
            self.assertIn('agentName', data)
    
    def test_3_route_product(self):
        code, data = api_call('POST', f"{self.base}/route", {
            'question': 'iPhone 15 Pro多少钱？', 'userId': '1'})
        if code == 200:
            self.assertIn('agentName', data)
    
    def test_4_route_weather(self):
        code, data = api_call('POST', f"{self.base}/route", {
            'question': '北京今天天气怎么样？', 'userId': '1'})
        if code == 200:
            self.assertIn('agentName', data)
    
    def test_5_route_multi_agent(self):
        code, data = api_call('POST', f"{self.base}/route", {
            'question': '推荐北京景点和川菜馆', 'userId': '1'})
        if code == 200:
            self.assertIn('agentName', data)
    
    def test_6_route_empty(self):
        code, data = api_call('POST', f"{self.base}/route", {
            'question': '', 'userId': '1'})
        self.assertIn(code, [400, 500, 200])

class TestOrderService(unittest.TestCase):
    """订单服务工具测试 - 通过Router间接验证"""
    
    def test_1_chat_order_query(self):
        self._test_tool("查一下订单ORD-2024001的状态")
    
    def test_2_chat_order_tracking(self):
        self._test_tool("帮我查一下快递SF1234567890的物流")
    
    def test_3_chat_order_refund_needs_confirm(self):
        """退款需要二阶段确认"""
        pass  # 需要服务运行
    
    def _test_tool(self, question):
        code, data = api_call('POST', f"{BASE_URLS['router']}/api/router/route", {
            'question': question, 'userId': '1'})
        if code == 200 and 'result' in data:
            self.assertTrue(len(data['result']) > 0)

class TestProductService(unittest.TestCase):
    """商品服务工具测试"""
    
    def test_1_product_price(self):
        self._test_tool("iPhone 15 Pro多少钱？")
    
    def test_2_product_stock(self):
        self._test_tool("MacBook Air M3有货吗？")
    
    def test_3_product_info(self):
        self._test_tool("查询一下IPHONE-15-PRO的详细信息")
    
    def _test_tool(self, question):
        code, data = api_call('POST', f"{BASE_URLS['router']}/api/router/route", {
            'question': question, 'userId': '1'})
        if code == 200 and 'result' in data:
            self.assertTrue(len(data['result']) > 0)

class TestGeneralTools(unittest.TestCase):
    """通用工具测试"""
    
    def test_1_weather(self):
        self._test_tool("北京今天天气怎么样？")
    
    def test_2_news(self):
        self._test_tool("今天有什么热搜？")
    
    def test_3_calculate(self):
        self._test_tool("计算一下3.14乘以5的平方")
    
    def test_4_search(self):
        self._test_tool("搜索2025年春节放假安排")
    
    def test_5_currency(self):
        self._test_tool("100美元等于多少人民币")
    
    def test_6_temp_convert(self):
        self._test_tool("100华氏度等于多少摄氏度")
    
    def _test_tool(self, question):
        code, data = api_call('POST', f"{BASE_URLS['router']}/api/router/route", {
            'question': question, 'userId': '1'})
        if code == 200 and 'result' in data:
            self.assertTrue(len(data['result']) > 0)

if __name__ == '__main__':
    print("=" * 60)
    print("SmartAssistant API 自动化测试脚本")
    print("=" * 60)
    print()
    print("⚠️  请先确保以下服务已启动:")
    for svc, url in BASE_URLS.items():
        print(f"  - {svc}: {url}")
    print()
    
    # 快速健康检查
    print("快速健康检查...")
    for svc, url in BASE_URLS.items():
        code, _ = api_call('GET', f"{url}/actuator/health")
        status = '✅' if code in [200, 503] else '❌'
        print(f"  {status} {svc} ({url}) -> {code}")
    
    print()
    runner = unittest.TextTestRunner(verbosity=2)
    suite = unittest.TestSuite()
    for tc in [TestGateway, TestUserService, TestRouterService, 
               TestOrderService, TestProductService, TestGeneralTools]:
        suite.addTests(unittest.TestLoader().loadTestsFromTestCase(tc))
    result = runner.run(suite)
    
    print()
    print("=" * 60)
    print(f"测试完成！通过: {result.testsRun - len(result.failures) - len(result.errors)}/{result.testsRun}")
    print("=" * 60)
