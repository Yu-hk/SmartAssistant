"""Collect synthetic read-only product evidence on the production host.

Frozen references never go to the Agent. Only RESOLVE_READ_ONLY_PRODUCT is
allowed, and outputs are reduced to answers and ordered field evidence. This
is a catalog-evidence benchmark, not a BM25/vector retrieval trace or Router
end-to-end benchmark. QA conversations/users/orders are not persisted.
"""
import argparse
import hashlib
import ipaddress
import json
import subprocess
import urllib.request
import uuid
from decimal import Decimal
from pathlib import Path

from ragas_feedback import digest, validate_dataset, write_new

OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def command(*args, input_data=None):
    result = subprocess.run(args, input=input_data, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, timeout=30)
    if result.returncode:
        raise RuntimeError('Read-only host command failed')
    return result.stdout.decode().strip()


def artifact():
    mounts = json.loads(command('docker', 'inspect', '--format', '{{json .Mounts}}', 'smart-product'))
    jars = [row['Source'] for row in mounts if row['Destination'].endswith('.jar')]
    if len(jars) != 1:
        raise ValueError('Expected one explicit product JAR mount')
    path = Path(jars[0]).resolve(strict=True)
    try:
        path.relative_to('/opt/smart-assistant/releases')
    except ValueError:
        raise ValueError('Unexpected product artifact path')
    hasher = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            hasher.update(block)
    return hasher.hexdigest()


def sample(case, result):
    data = result.get('data', {})
    if result.get('status') != 'SUCCEEDED' or data.get('productEntityResolutionVersion') != 1 or data.get('deterministic') is not True:
        raise ValueError('Unsupported/failed product response')
    if data.get('queryPlan', {}).get('originalQuestion') != case['question']:
        raise ValueError('Reply scope mismatch')
    contexts, ids = [], []
    for entity in data.get('productEvidence', []):
        if entity.get('status') != 'RESOLVED' or not entity.get('productCode') or not entity.get('productName'):
            raise ValueError('Unresolved product evidence')
        for field, value in entity.get('fields', {}).items():
            if not isinstance(value.get('known'), bool) or not isinstance(value.get('evidence'), str):
                raise ValueError('Missing field evidence')
            if value['known'] and not value['evidence'].strip():
                raise ValueError('Known field has no evidence')
            # Unknown is an explicit metadata fact, NOT a materialized weight
            # value. Use a distinct ID so it never masquerades as known evidence.
            ids.append(entity['productCode'] + ':' + field + ('' if value['known'] else ':UNVERIFIED'))
            evidence = value['evidence'] if value['known'] else field + '：known=false，资料未核实，无具体数值证据'
            contexts.append(entity['productName'] + '，本问题数量 ' + str(entity['quantity'])
                            + ' 件；' + evidence)
    return {**case, 'source': 'live_catalog_readonly', 'response': result.get('answer'),
            'retrieved_contexts': contexts, 'retrieved_context_ids': ids}


def validate_questions(questions):
    if questions.get('schema_version') != 1 or questions.get('origin') != 'synthetic_public_catalog' or questions.get('reference_policy') != 'human_authored_frozen_before_collection':
        raise ValueError('Only frozen synthetic questions accepted')
    cases = questions.get('cases')
    if not isinstance(cases, list) or not 1 <= len(cases) <= 20:
        raise ValueError('Invalid case count')
    identifiers = set()
    for row in cases:
        if set(row) != {'id', 'question', 'reference', 'reference_context_ids'}:
            raise ValueError('Unexpected question field')
        if row['id'] in identifiers:
            raise ValueError('Duplicate question ID')
        identifiers.add(row['id'])
        if not all(isinstance(row[key], str) and row[key].strip() and len(row[key]) <= 8000 for key in ('id', 'question', 'reference')):
            raise ValueError('Invalid question or reference')
        if not isinstance(row['reference_context_ids'], list) or not row['reference_context_ids']:
            raise ValueError('Reference context IDs required')
    return cases


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--questions', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--expected-product-sha256', required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise FileExistsError('Dataset already exists')
    questions = json.loads(args.questions.read_text(encoding='utf-8'))
    cases = validate_questions(questions)
    before = artifact()
    if before != args.expected_product_sha256:
        raise ValueError('Product artifact drift')
    # Independent DB check detects catalog drift. References remain fixed, not
    # regenerated from the candidate answer or its returned evidence.
    sql = "BEGIN READ ONLY; SELECT product_code,price FROM products WHERE product_code IN ('AIRPODS-PRO','MACBOOK-AIR-M3'); COMMIT;"
    catalog = command('docker', 'exec', '-i', 'smart-postgres', 'psql', '-U', 'postgres',
                      '-d', 'a2a_system', '-Atq', input_data=sql.encode())
    prices = {row.split('|')[0]: Decimal(row.split('|')[1]) for row in catalog.splitlines() if '|' in row}
    if prices != {key: Decimal(value) for key, value in questions['catalog_prices'].items()}:
        raise ValueError('Catalog prices changed; independent gold review required')
    weight = command('docker', 'exec', '-i', 'smart-postgres', 'psql', '-U', 'postgres',
                     '-d', 'a2a_system', '-Atq', input_data=(
                         "BEGIN READ ONLY; SELECT COALESCE(to_jsonb(p)->>'weight_grams','MISSING') "
                         "FROM products p WHERE product_code='AIRPODS-PRO'; COMMIT;").encode())
    if weight != 'MISSING':
        raise ValueError('Missing-weight reference no longer matches catalog; gold review required')
    ip = command('docker', 'inspect', '--format', '{{(index .NetworkSettings.Networks "smart-network").IPAddress}}', 'smart-product')
    if not ipaddress.ip_address(ip).is_private:
        raise ValueError('Private product endpoint required')
    samples = []
    for case in cases:
        payload = {'protocolVersion': '1.0', 'executionId': 'qa-ragas-' + uuid.uuid4().hex,
                   'nodeId': 'readonly', 'userId': 'qa-ragas-readonly',
                   'operation': 'RESOLVE_READ_ONLY_PRODUCT', 'question': case['question'], 'input': {}}
        req = urllib.request.Request('http://' + ip + ':8084/internal/agents/product/execute',
                                     data=json.dumps(payload, ensure_ascii=False).encode(),
                                     headers={'Content-Type': 'application/json'}, method='POST')
        with OPENER.open(req, timeout=30) as response:
            result = json.load(response)
        samples.append(sample(case, result))
    if artifact() != before:
        raise ValueError('Product artifact changed during collection')
    dataset = {'schema_version': 1, 'synthetic_only': True, 'product_sha256': before,
               'question_set_sha256': digest(questions), 'context_kind': 'catalog_field_evidence',
               'end_to_end_router': False, 'reference_policy': questions['reference_policy'], 'cases': samples}
    validate_dataset(dataset)
    write_new(args.output, dataset)
    print(json.dumps({'collected_cases': len(samples), 'product_sha256': before,
                      'context_kind': dataset['context_kind'], 'orders_written': 0}), flush=True)


if __name__ == '__main__':
    main()
