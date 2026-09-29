"""Scoped production QA: synthetic catalog row only, removed in finally."""
import json
import subprocess
import sys
import urllib.error
import urllib.request


CODE = 'QA-SUITABILITY-20260929'
NAME = 'QA适用标签验收商品（虚构）'
HEADERS = {'Content-Type': 'application/json', 'X-User-Role': 'ROLE_ADMIN',
           'X-User-Id': '9999999999'}
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def run(*args, input_data=None):
    result = subprocess.run(args, input=input_data, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, timeout=30)
    if result.returncode:
        raise RuntimeError('Local QA command failed: ' + args[0] + ' ' + args[1])
    return result.stdout.decode('utf-8').strip()


def address(name, port):
    info = json.loads(run('docker', 'inspect', name))[0]
    if not info['State']['Running']:
        raise RuntimeError('Target service is not running')
    ip = info['NetworkSettings']['Networks']['smart-network']['IPAddress']
    return 'http://' + ip + ':' + str(port)


def request(method, url, payload=None, admin=True):
    data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode('utf-8')
    headers = HEADERS if admin else {}
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with OPENER.open(req, timeout=20) as response:
            return response.status, response.read().decode('utf-8')
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode('utf-8')


def expect(condition, label):
    if not condition:
        raise RuntimeError('QA check failed: ' + label)
    print('PASS ' + label, flush=True)


def cleanup():
    # Never remove a non-QA row or one whose identity/price/status has changed.
    sql = ("DELETE FROM products WHERE product_code='" + CODE + "' AND product_name='" + NAME
           + "' AND price=1.00 AND stock='缺货' RETURNING product_code;")
    removed = run('docker', 'exec', '-i', 'smart-postgres', 'psql', '-v', 'ON_ERROR_STOP=1',
                  '-U', 'postgres', '-d', 'a2a_system', '-Atq', input_data=sql.encode('utf-8'))
    expect(removed == CODE, 'strict QA row cleanup')
    remaining = run('docker', 'exec', 'smart-postgres', 'psql', '-U', 'postgres', '-d',
                    'a2a_system', '-Atqc', "SELECT count(*) FROM product_suitability_tags WHERE product_code='" + CODE + "'")
    expect(remaining == '0', 'no QA suitability tags remain')


def main():
    intake = address('smart-data-intake', 8092)
    product = address('smart-product', 8084)
    path = intake + '/api/admin/products/' + CODE + '/suitability'
    status, _ = request('GET', path, admin=False)
    expect(status == 403, 'internal admin endpoint rejects missing identity')

    status, body = request('POST', intake + '/api/admin/products/extract-features',
                           {'description': '整机净重200g，视频播放续航10小时。', 'spec': '支持主动降噪。'})
    expect(status == 200 and json.loads(body)['features']['weightGrams'] == 200,
           'deterministic feature extraction')

    created = False
    try:
        status, body = request('POST', intake + '/api/admin/products', {
            'productCode': CODE, 'productName': NAME, 'category': 'QA测试品类',
            'price': 1.00, 'stock': '缺货', 'description': '整机净重200g，视频播放续航10小时。',
            'spec': '支持主动降噪。', 'color': '', 'featuresConfirmed': True,
            'suitability': {'audiences': ['QA测试人员'], 'useCases': ['系统验收'],
                            'source': 'Synthetic QA fixture 2026-09-29', 'confirmed': True}
        })
        created = status == 201
        expect(created, 'synthetic QA product creation')
        expect(json.loads(body)['suitability']['revision'] == 1, 'suitability revision 1')

        status, body = request('GET', path)
        tags = json.loads(body)
        expect(status == 200 and tags['audiences'] == ['QA测试人员']
               and tags['useCases'] == ['系统验收'] and tags['updatedBy'] == 9999999999,
               'admin read and actor audit')

        status, body = request('GET', product + '/api/product/' + CODE + '/info', admin=False)
        expect(status == 200 and '目录标注（非性能保证）' in body and 'QA测试人员' in body
               and '结构化参数' in body, 'Product reads measured features and declared suitability')

        payload = {'expectedRevision': 1, 'suitability': {'audiences': ['QA测试人员'],
                   'useCases': ['回归测试'], 'source': 'Synthetic QA fixture 2026-09-29',
                   'confirmed': True}}
        status, body = request('PUT', path, payload)
        expect(status == 200 and json.loads(body)['revision'] == 2,
               'optimistic revision update')
        status, _ = request('PUT', path, payload)
        expect(status == 409, 'stale revision rejected')
    finally:
        if created:
            cleanup()


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('PRODUCTION_QA_FAILED ' + str(error), file=sys.stderr)
        sys.exit(1)
