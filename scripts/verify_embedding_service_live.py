"""Internal-only synthetic embedding contract probe. No LLM, business or restart calls."""
import argparse
import ipaddress
import json
import math
import pathlib
import subprocess
import urllib.request

SERVICES = ('smart-embedding-service', 'smart-embedding', 'smart-embedding-contract-canary-20261001')
NETWORK = 'smart-network'


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        # A scoped private probe must never forward to a different host/path/port.
        return None


def address(service):
    if service not in SERVICES:
        raise ValueError('Only the dedicated embedding service is allowed')
    raw = subprocess.check_output(['docker', 'inspect', '--format',
        '{{.State.Running}} {{json .NetworkSettings.Networks}}', service], timeout=15).decode().strip()
    running, networks = raw.split(' ', 1)
    if running != 'true':
        raise ValueError('Embedding service is not running')
    entries = json.loads(networks)
    if set(entries) != {NETWORK}:
        raise ValueError('Unexpected service network')
    host = entries[NETWORK]['IPAddress']
    parsed = ipaddress.ip_address(host)
    rfc1918 = ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16')
    if parsed.version != 4 or not any(parsed in ipaddress.ip_network(block) for block in rfc1918):
        raise ValueError('Internal service IPv4 required')
    return 'http://' + str(parsed) + ':8091'


def request(base, route, payload=None):
    if route not in ('/actuator/health', '/api/embedding/health', '/api/embedding/dimensions',
                     '/api/embedding', '/api/embedding/batch'):
        raise ValueError('Endpoint outside embedding contract')
    body = None if payload is None else json.dumps(payload, ensure_ascii=False).encode('utf-8')
    req = urllib.request.Request(base + route, data=body,
        headers={'Content-Type': 'application/json'}, method='GET' if body is None else 'POST')
    # Container-private URLs must not accidentally go through a configured external proxy.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    with opener.open(req, timeout=20) as response:
        if response.status != 200:
            raise ValueError('Embedding contract HTTP status mismatch')
        raw = response.read(4 * 1024 * 1024 + 1)
        if len(raw) > 4 * 1024 * 1024:
            raise ValueError('Embedding response exceeds bounded probe size')
        return json.loads(raw.decode('utf-8'))


def vector(values, dimensions):
    if not isinstance(values, list) or len(values) != dimensions:
        raise ValueError('Vector shape mismatch')
    if any(isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v) for v in values):
        raise ValueError('Vector must contain only finite numbers')
    if not any(abs(v) > 0 for v in values):
        raise ValueError('All-zero vector does not verify a loaded model')


def verify(service):
    base = address(service)
    if request(base, '/actuator/health').get('status') != 'UP':
        raise ValueError('Application health is not UP')
    health = request(base, '/api/embedding/health')
    dims = health.get('dimensions')
    if health.get('status') != 'UP' or health.get('available') is not True:
        raise ValueError('Actual embedding model is unavailable')
    if isinstance(dims, bool) or not isinstance(dims, int) or not 1 <= dims <= 8192:
        raise ValueError('Invalid model dimensions')
    if request(base, '/api/embedding/dimensions').get('dimensions') != dims:
        raise ValueError('Dimensions endpoints disagree')
    text = '嵌入服务合成只读验收：库存状态'
    single = request(base, '/api/embedding', {'text': text})
    vector(single.get('embedding'), dims)
    if single.get('dimensions') != dims:
        raise ValueError('Single embedding dimensions disagree')
    batch = request(base, '/api/embedding/batch', {'texts': [text, '嵌入服务合成只读验收：商品价格']})
    rows = batch.get('embeddings')
    if batch.get('count') != 2 or batch.get('dimensions') != dims or not isinstance(rows, list) or len(rows) != 2:
        raise ValueError('Batch cardinality mismatch')
    for row in rows:
        vector(row, dims)
    if any(not math.isclose(a, b, rel_tol=1e-5, abs_tol=1e-6) for a, b in zip(single['embedding'], rows[0])):
        raise ValueError('Batch must preserve input order and the same first embedding')
    for payload in ({}, {'text': '  '}):
        if request(base, '/api/embedding', payload) != {'error': 'text 不能为空'}:
            raise ValueError('Legacy empty text error contract changed')
    if request(base, '/api/embedding/batch', {'texts': []}) != {'error': 'texts 不能为空'}:
        raise ValueError('Legacy empty batch error contract changed')
    return {'status': 'passed', 'service': service, 'checks': 8, 'dimensions': dims,
            'scope': 'synthetic-internal-embedding-http-not-browser-or-model-quality',
            'business_writes': 0, 'restarts': 0}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--service', choices=SERVICES, default='smart-embedding-service')
    parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError('Refusing to overwrite an existing receipt')
    result = verify(args.service)
    with args.output.open('x', encoding='utf-8') as output:
        json.dump(result, output, ensure_ascii=False, indent=2)
    args.output.chmod(0o600)
    print('EMBEDDING_LIVE_CONTRACT_VERIFIED ' + str(result['dimensions']))


if __name__ == '__main__':
    main()
