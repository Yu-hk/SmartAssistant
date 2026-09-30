"""Read-only production checks. No account, order, catalog or balance writes."""
import json
import subprocess
import urllib.request
import uuid
from decimal import Decimal

OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))

def command(*args, input_data=None):
    result = subprocess.run(args, input=input_data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
    if result.returncode:
        raise RuntimeError('Read-only QA command failed: ' + args[0])
    return result.stdout.decode('utf-8').strip()

def expect(condition, label):
    if not condition:
        raise AssertionError(label)
    print('PASS ' + label, flush=True)

def main():
    sql = "SELECT product_code,price,stock FROM products WHERE product_code IN ('AIRPODS-PRO','MACBOOK-AIR-M3');"
    rows = command('docker', 'exec', '-i', 'smart-postgres', 'psql', '-U', 'postgres',
                   '-d', 'a2a_system', '-Atq', input_data=sql.encode()).splitlines()
    catalog = {parts[0]: parts[1:] for parts in (row.split('|') for row in rows)}
    expect(set(catalog) == {'AIRPODS-PRO', 'MACBOOK-AIR-M3'}, 'two existing catalog identities')
    total = sum(Decimal(row[0]) for row in catalog.values())
    info = json.loads(command('docker', 'inspect', 'smart-product'))[0]
    ip = info['NetworkSettings']['Networks']['smart-network']['IPAddress']
    url = 'http://' + ip + ':8084/internal/agents/product/execute'

    def ask(question, operation='QUERY_PRODUCT', original=None):
        payload = {'protocolVersion': '1.0', 'executionId': 'qa-multi-' + uuid.uuid4().hex,
                   'nodeId': 'readonly', 'userId': 'qa-multi-readonly',
                   'operation': operation, 'question': question,
                   'input': {'_replyScopeQuestion': original} if original else {}}
        req = urllib.request.Request(url, data=json.dumps(payload, ensure_ascii=False).encode(),
                                     headers={'Content-Type': 'application/json'}, method='POST')
        with OPENER.open(req, timeout=30) as response:
            result = json.load(response)
            expect(response.status == 200 and result['status'] == 'SUCCEEDED', operation + ' success')
            return result, dict(response.headers)

    for operation in ('ANSWER', 'QUERY_PRODUCT', 'DISCOVER_PRODUCTS', 'ANALYZE_PRODUCT_DATA', 'RECOMMEND_PRODUCT'):
        result, _ = ask('查询AirPods Pro', operation,
                        'AirPods Pro和MacBook Air M3合计不超过10000元可以吗？')
        data = result['data']
        expect(data.get('handled') and data['productCoverage'] == {'requested': 2, 'resolved': 2},
               operation + ' preserves full multi-product scope')
        expect(str(total.quantize(Decimal('1'))) + ' 元' in result['answer']
               and data['queryPlan']['budgetScope'] == 'TOTAL', operation + ' total verified against database')
        expect('orderQuote' not in data, operation + ' no executable order quote')

    result, _ = ask('AirPods Pro和MacBook Air M3每款不超过10000元可以吗？')
    expect(result['data']['queryPlan']['budgetScope'] == 'EACH' and '合计' not in result['answer'],
           'per-product budget stays separate')
    result, _ = ask('AirPods Pro和MacBook Air M3分别多少钱？有货吗？')
    for code, row in catalog.items():
        item = next(p for p in result['data']['products'] if p['code'] == code)
        expect(Decimal(str(item['price'])) == Decimal(row[0]) and item['stock'] == row[1],
               code + ' identity scoped price and stock')
    result, _ = ask('AirPods Pro和QA不存在的耳机XYZ合计多少钱？')
    expect(result['data']['clarificationRequired'] and '暂不能核算总价' in result['answer'],
           'missing product prevents fabricated total')
    result, _ = ask('AirPods Pro和QA不存在的耳机XYZ合计多少钱？', 'ANSWER')
    expect(result['data'].get('multiProductQueryVersion') == 1
           and result['data']['queryPlan']['originalQuestion'] == 'AirPods Pro和QA不存在的耳机XYZ合计多少钱？',
           'legacy ANSWER retains complete evidence and scope')
    result, _ = ask('AirPods Pro和MacBook Air M3能否一起使用？')
    expect('不足以核实' in result['answer'], 'compatibility not inferred from unrelated specs')
    print('READ_ONLY_PRODUCTION_CHECKS_COMPLETE', flush=True)

if __name__ == '__main__':
    main()
