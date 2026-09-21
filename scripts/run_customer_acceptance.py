"""Read-only business smoke using one new synthetic account (registration writes only).

Not a held-out benchmark or automatic semantic-quality certification. Answers and
review rubrics are retained for review; credentials and raw tool arguments are not.
No production fault injection, order submission, refunds or profile deletion.
"""
import argparse
import json
import pathlib
import secrets
import urllib.error
import urllib.parse
import urllib.request
import uuid

DATASET = pathlib.Path(__file__).resolve().parents[1] / 'data/customer_acceptance_cases.json'
ERROR_EVENTS = {'error', 'timeout', 'request_blocked', 'conversation_suspended', 'node_criteria_rejected'}


def inspect_answer(rule, events):
    types = [e.get('type') for e in events]
    answer = ''.join(str(e.get('content', e.get('message', ''))) for e in events
                     if e.get('type') in ('text', 'response'))
    checks = {'completed': 'done' in types, 'no_error_event': not bool(ERROR_EVENTS & set(types)),
              'nonempty': bool(answer.strip()),
              'not_failure_reply': not any(x in answer for x in ('没能完成查询', '没能完成您的请求', '无法完成查询', '未能完成以下必要步骤')),
              'no_internal_exception': not any(x in answer for x in ('Exception:', 'FATAL_FAILED', 'No static resource'))}
    blocks = answer.strip().split('\n\n')
    checks['no_repeated_full_answer'] = not (len(blocks) > 1 and len(blocks) % 2 == 0
        and blocks[:len(blocks)//2] == blocks[len(blocks)//2:])
    if rule == 'price-stock':
        # These only check that the question was addressed, not truth of price/stock.
        checks['addresses_price_stock'] = 'AirPods' in answer and '元' in answer and any(x in answer for x in ('库存', '有货', '缺货', '售罄', '现货'))
    elif rule == 'spec-only':
        checks['answers_spec'] = any(x in answer for x in ('降噪', 'USB', '音频'))
        checks['no_unasked_color'] = not any(x in answer for x in ('颜色', '白色', '黑色'))
    elif rule == 'policy':
        checks['has_citation'] = '[CID:' in answer
        checks['not_order_clarification'] = not any(x in answer for x in ('请提供订单号', '请您提供订单号'))
    elif rule != 'nonempty':
        raise ValueError('Unknown acceptance rule')
    return {'checks': checks, 'checksPassed': all(checks.values()), 'answer': answer,
            'eventTypes': sorted(set(str(t) for t in types)),
            'toolUsageObserved': 'tool_usage' in types, 'tokenUsageObserved': 'token_usage' in types,
            'semanticQualityCertified': False}


def request(base, path, token=None, payload=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(base + path, headers=headers,
                                 data=None if payload is None else json.dumps(payload, ensure_ascii=False).encode('utf-8'))
    with urllib.request.urlopen(req, timeout=45) as res:
        return json.load(res)


def chat(base, token, session, question):
    payload = {'message': question, 'sessionId': session, 'requestId': str(uuid.uuid4())}
    req = urllib.request.Request(base + '/api/math/stream/chat',
        data=json.dumps(payload, ensure_ascii=False).encode('utf-8'),
        headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json', 'Accept': 'text/event-stream'})
    with urllib.request.urlopen(req, timeout=180) as res:
        raw = res.read(2 * 1024 * 1024 + 1)
    if len(raw) > 2 * 1024 * 1024:
        raise ValueError('Response exceeded test limit')
    return [json.loads(line[5:].strip()) for line in raw.decode('utf-8').splitlines() if line.startswith('data:')]


def run(base, cases, output):
    parsed = urllib.parse.urlsplit(base)
    if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password or parsed.path not in ('', '/') or parsed.query or parsed.fragment:
        raise ValueError('Expected HTTPS origin without credentials')
    base = base.rstrip('/')
    report = {'schema': 1, 'syntheticAccount': True, 'businessWrites': False, 'browserVerified': False,
              'semanticQualityCertified': False, 'results': [], 'closedSessions': 0, 'tokenRevoked': False}
    # Reserve the output before creating an account; never overwrite old evidence.
    with output.open('x', encoding='utf-8') as out:
        user = None
        try:
            registration = request(base, '/api/auth/register', payload={
                'username': 'acceptance_' + secrets.token_hex(6), 'password': secrets.token_urlsafe(24)})
            if registration.get('code') != 0:
                raise ValueError('Synthetic registration failed')
            user = registration['data']
            for case in cases:
                session = str(uuid.uuid4())
                try:
                    for index, turn in enumerate(case['turns']):
                        result = {'caseId': case['id'], 'turn': index + 1, 'review': turn['review']}
                        try:
                            result.update(inspect_answer(turn['rule'], chat(base, user['token'], session, turn['question'])))
                        except Exception as error:
                            result.update(checksPassed=False, errorType=type(error).__name__)
                        report['results'].append(result)
                        print(json.dumps({'caseId': case['id'], 'turn': index + 1, 'checksPassed': result['checksPassed']}), flush=True)
                finally:
                    try:
                        request(base, '/api/sessions/' + session + '/close', user['token'], {})
                        report['closedSessions'] += 1
                    except Exception as error:
                        report['sessionCleanupError'] = type(error).__name__
        except Exception as error:
            report['runError'] = type(error).__name__
        finally:
            if user:
                try:
                    logout = request(base, '/api/auth/logout', user['token'], {'refreshToken': user['refreshToken']})
                    if logout.get('code') != 0:
                        raise ValueError('Logout rejected')
                    try:
                        request(base, '/api/privacy/profile', user['token'])
                    except urllib.error.HTTPError as error:
                        report['tokenRevoked'] = error.code == 401
                except Exception as error:
                    report['tokenCleanupError'] = type(error).__name__
            expected = sum(len(c['turns']) for c in cases)
            report['contractPassed'] = (expected > 0 and len(report['results']) == expected
                and all(r['checksPassed'] for r in report['results'])
                and report['closedSessions'] == len(cases) and report['tokenRevoked']
                and not any(k.endswith('Error') for k in report))
            json.dump(report, out, ensure_ascii=False, indent=2)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', required=True)
    parser.add_argument('--dataset', type=pathlib.Path, default=DATASET)
    parser.add_argument('--report', type=pathlib.Path, required=True)
    args = parser.parse_args()
    report = run(args.base_url, json.loads(args.dataset.read_text(encoding='utf-8')), args.report)
    print('SEMANTIC_REVIEW_REQUIRED (contract checks do not certify answer quality)', flush=True)
    return 0 if report['contractPassed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
