"""Synthetic read-only, exact Product binary + request trace verification; no business writes."""
import argparse
import hashlib
import json
import re
import urllib.request
import uuid
from pathlib import Path
from verify_bounded_feedback_live import command, read_trace
from ragas_feedback import write_new

SERVICES = ('smart-product', 'smart-product-knowledge-guard-canary-20261001')
QUESTIONS = (
    '请根据知识库回答：可售库存的计算规则是什么？锁定库存和质检库存分别如何处理？',
    '请根据知识库说明：库存锁定和库存释放流程如何区别？',
    'AirPods Pro 2件和MacBook Air M3 1台合计多少钱？')
GENERIC_FAILURE = re.compile('检测到 Agent 报告被阻塞|检测到基础设施故障|循环守卫暂停|处理失败[:：]')


def verify(name, expected, output):
    if name not in SERVICES:
        raise ValueError('Confined service required')
    info = json.loads(command('docker', 'inspect', name))[0]
    mount = next(m['Source'] for m in info['Mounts'] if m['Destination'] == '/app/app.jar')
    if hashlib.sha256(Path(mount).read_bytes()).hexdigest() != expected:
        raise ValueError('Candidate drift')
    if command('docker', 'exec', name, 'sha256sum', '/app/app.jar').split()[0] != expected:
        raise ValueError('Mounted candidate drift')
    ip = info['NetworkSettings']['Networks']['smart-network']['IPAddress']
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    rows = []
    for index, question in enumerate(QUESTIONS):
        rid = 'qa-knowledge-guard-' + uuid.uuid4().hex
        payload = {'protocolVersion': '1.0', 'executionId': rid, 'nodeId': 'readonly',
                   'userId': 'qa-knowledge-guard-readonly', 'operation': 'ANSWER',
                   'question': question, 'input': {}}
        request = urllib.request.Request('http://' + ip + ':8084/internal/agents/product/execute',
            data=json.dumps(payload, ensure_ascii=False).encode(),
            headers={'Content-Type': 'application/json'}, method='POST')
        with opener.open(request, timeout=120) as response:
            answer = json.load(response)
        text = answer.get('answer', '')
        if answer.get('status') != 'SUCCEEDED' or not text or GENERIC_FAILURE.search(text):
            raise ValueError('Synthetic answer failed; request ' + rid)
        row = {'request_id': rid, 'question': question, 'answer': text, 'quality': answer.get('quality')}
        if index == 2:
            if '12997' not in text:
                raise ValueError('Catalog arithmetic regression')
        else:
            trace = read_trace(info, rid)
            stages = [s for s in trace.get('stages', []) if s.get('stage') == 'RETRIEVAL']
            if not stages:
                raise ValueError('Request-bound retrieval trace missing')
            evidence = stages[-1].get('metrics', {}).get('evidenceTrace', {})
            feedback = evidence.get('feedback', {})
            if (feedback.get('enabled') is not True or feedback.get('maxRetries') != 1
                    or feedback.get('timeoutMs') != 1500):
                raise ValueError('Bounded retry settings drift')
            if index == 0 and not re.search('没有|不足|未提供|未说明|未包含|未找到|缺少|无法|未定义|未明确|暂无|未给出|未涉及', text):
                raise ValueError('Missing knowledge was not disclosed')
            row['feedback'] = feedback
        rows.append(row)
    if not any(row.get('feedback', {}).get('attempted') is True for row in rows):
        raise ValueError('No actual bounded retry')
    report = {'status': 'passed', 'service': name, 'sha256': expected, 'rows': rows,
              'orders_written': 0, 'scope': 'synthetic-product-internal-plus-exact-trace-not-ui'}
    write_new(output, report)
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--service', choices=SERVICES, default=SERVICES[0])
    parser.add_argument('--sha', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    report = verify(args.service, args.sha, args.output)
    print(json.dumps({'status': report['status'], 'requests': len(report['rows']), 'business_writes': 0}))
