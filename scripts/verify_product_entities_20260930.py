"""Read-only live catalog/entity protocol verification; no orders or metadata writes."""
import json
import urllib.request
import uuid
from verify_multi_product_query_20260930 import command, expect, OPENER


def main():
    info = json.loads(command('docker', 'inspect', 'smart-product'))[0]
    ip = info['NetworkSettings']['Networks']['smart-network']['IPAddress']
    url = 'http://' + ip + ':8084/internal/agents/product/execute'
    checked = 0

    def ask(question, history=None):
        payload = {'protocolVersion': '1.0', 'executionId': 'qa-entities-' + uuid.uuid4().hex,
                   'nodeId': 'readonly', 'userId': 'qa-entities-readonly', 'operation': 'RESOLVE_READ_ONLY_PRODUCT',
                   'question': question, 'input': {'conversationHistory': history or []}}
        request = urllib.request.Request(url, data=json.dumps(payload, ensure_ascii=False).encode(),
                                         headers={'Content-Type': 'application/json'}, method='POST')
        with OPENER.open(request, timeout=30) as response:
            result = json.load(response)
        expect(result['status'] == 'SUCCEEDED', 'protocol request completed')
        return result

    result = ask('想了解AirPods Pro重量和MacBook Air M3价格？')
    tasks = result['data']['entityTasks']
    expect(tasks[0]['entity']['code'] == 'AIRPODS-PRO' and tasks[0]['fields'] == ['WEIGHT'], 'first product weight-only binding')
    expect(tasks[1]['entity']['code'] == 'MACBOOK-AIR-M3' and tasks[1]['fields'] == ['PRICE'], 'second product price-only binding')
    expect(not result['data']['clarificationRequired'] and '1999' not in result['answer'], 'no cross-product price leakage')
    checked += 1
    result = ask('AirPods Pro 2件和MacBook Air M3 1台合计多少钱？')
    expect('12997' in result['answer'] and not result['data']['clarificationRequired'], 'explicit two/one catalog total')
    expect('orderQuote' not in result['data'], 'read-only multi-product result has no order quote')
    checked += 1
    for question in ('AirPods Pro和QA不存在的耳机XYZ合计多少钱？', 'QA不存在的耳机XYZ和AirPods Pro合计多少钱？'):
        result = ask(question)
        expect(result['data']['productCoverage'] == {'requested': 2, 'resolved': 1}, 'unknown entity retained in either position')
        expect('暂不能核算总价' in result['answer'], 'incomplete identities never produce total')
        checked += 1
    history = ['用户：AirPods Pro和MacBook Air M3多少钱？',
               '助手：MacBook Air M3：8999元。AirPods Pro（第二代）：1999元。']
    result = ask('第二款价格？', history)
    expect(result['data']['entityTasks'][0]['entity']['code'] == 'AIRPODS-PRO', 'reference follows assistant display order')
    checked += 1
    result = ask('前面两款合计多少钱？', history)
    expect('10998' in result['answer'], 'plural reference covers both products')
    checked += 1
    for contextual in ([], history + ['用户：明天天气如何？']):
        result = ask('第二款价格？', contextual)
        expect(result['data']['clarificationRequired'] and not result['data']['products'], 'missing context/topic switch does not reuse identity')
        checked += 1
    result = ask('AirPods Pro Max价格？')
    expect(result['data']['clarificationRequired'] and not result['data']['products'], 'unknown suffix not silently stripped')
    checked += 1
    result = ask('AirPods Pro有货吗，支持卫星通信吗？')
    expect(result['data']['clarificationRequired'] and result['data']['unsupportedConditions'], 'unsupported requirement visible')
    checked += 1
    result = ask('ＡｉｒＰｏｄｓ　Ｐｒｏ 价格？')
    expect(result['data']['entityTasks'][0]['entity']['code'] == 'AIRPODS-PRO', 'full-width spelling normalization')
    checked += 1
    result = ask('AirPods Pro价格不超过1999元？')
    expect('价格条件符合' in result['answer'], 'inclusive catalog price condition')
    checked += 1
    result = ask('AirPods Pro价格低于1999元？')
    expect('价格条件不符合' in result['answer'], 'strict price condition boundary')
    checked += 1
    print('READ_ONLY_ENTITY_CHECKS_COMPLETE questions=' + str(checked), flush=True)


if __name__ == '__main__':
    main()
