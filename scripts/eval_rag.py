#!/usr/bin/env python3
"""Real retrieval through a JSON stdin/stdout subprocess, never a shell or mock default.
Exit 0=quality passed, 1=quality failed, 2=invalid run (no certified scores).
Backend sees questions without relevance labels. Reports omit question/document text.
"""
import argparse
from collections import defaultdict
import hashlib
import json
import math
import os
import re
from pathlib import Path
import subprocess
import sys

DEFAULT_DATASET = Path(__file__).resolve().parents[1] / 'data/rag_eval_dataset.json'
class EvaluationError(ValueError):
    pass
def require(condition, message):
    if not condition:
        raise EvaluationError(message)
def strings(value):
    return isinstance(value, list) and all(isinstance(s, str) and s.strip() for s in value)
def validate_dataset(data):
    require(isinstance(data, list) and 0 < len(data) <= 1000, 'Dataset must contain 1..1000 samples')
    ids = set()
    for row in data:
        require(isinstance(row, dict), 'Invalid sample')
        for field in ('id', 'category', 'question', 'knowledge_base'):
            require(isinstance(row.get(field), str) and 0 < len(row[field].strip()) <= 2000, 'Invalid sample field')
        require(row['id'] not in ids, 'Duplicate sample ID')
        ids.add(row['id'])
        expected = row.get('expected_doc_ids')
        require(strings(expected) and 0 < len(expected) == len(set(expected)), 'Relevance labels must be nonempty and unique')
    return data
def hit_at_k(retrieved, expected, k):
    return int(bool(set(retrieved[:k]) & set(expected)))
def recall_at_k(retrieved, expected, k):
    require(bool(expected), 'Recall requires relevance labels')
    return len(set(retrieved[:k]) & set(expected)) / len(set(expected))
def mrr(retrieved, expected):
    return next((1 / i for i, doc in enumerate(retrieved, 1) if doc in expected), 0.0)
def validate_backend(response, data, top_k):
    require(isinstance(response, dict) and response.get('schema') == 1, 'Invalid backend schema')
    require(response.get('backend') == 'seed-inmemory-bge-bm25-reranker', 'Unrecognized provenance')
    require(response.get('embedding_verified') is True, 'Real embedding not verified')
    require(isinstance(response.get('corpus_sha256'), str) and re.fullmatch('[a-f0-9]{64}', response['corpus_sha256']), 'Missing corpus content fingerprint')
    corpus = response.get('corpus')
    require(isinstance(corpus, dict), 'Missing corpus catalog')
    for row in data:
        catalog = corpus.get(row['knowledge_base'])
        require(strings(catalog) and len(catalog) == len(set(catalog)), 'Invalid corpus catalog')
        require(set(row['expected_doc_ids']) <= set(catalog), 'Expected document absent from corpus')
    results = response.get('results')
    require(isinstance(results, list) and len(results) == len(data), 'Incomplete results')
    indexed = {}
    samples = {r['id']: r for r in data}
    for result in results:
        require(isinstance(result, dict) and isinstance(result.get('id'), str), 'Invalid result')
        key = result['id']
        require(key in samples and key not in indexed, 'Unexpected or duplicate result ID')
        found = result.get('doc_ids')
        require(strings(found) and len(found) <= top_k and len(found) == len(set(found)), 'Invalid ranked IDs')
        require(set(found) <= set(corpus[samples[key]['knowledge_base']]), 'Result outside selected corpus')
        latency = result.get('latency_ms')
        require(type(latency) in (int, float) and math.isfinite(latency) and latency >= 0, 'Invalid latency')
        indexed[key] = result
    return indexed
def invoke_backend(command, request, timeout):
    require(strings(command) and command, 'Backend command must be a nonempty JSON argv array')
    try:
        result = subprocess.run(command, input=json.dumps(request, ensure_ascii=False).encode('utf-8'),
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired):
        raise EvaluationError('Backend could not complete') from None
    require(result.returncode == 0, 'Backend failed; raw stderr omitted')
    require(len(result.stdout) <= 8 * 1024 * 1024, 'Backend response too large')
    try:
        return json.loads(result.stdout)
    except (ValueError, UnicodeError):
        raise EvaluationError('Invalid backend JSON; raw output omitted') from None
def evaluate(data, backend, top_k=5, repeats=1):
    validate_dataset(data)
    require(type(top_k) is int and 5 <= top_k <= 50, 'top-k must be 5..50')
    require(type(repeats) is int and 1 <= repeats <= 10, 'repeats must be 1..10')
    request = {'schema': 1, 'top_k': top_k, 'queries': [
        {k: row[k] for k in ('id', 'question', 'knowledge_base')} for row in data]}
    rows = []
    catalog_digest = None
    for trial in range(repeats):
        response = backend(request)
        indexed = validate_backend(response, data, top_k)
        digest = hashlib.sha256(json.dumps([response['corpus'], response['corpus_sha256']], sort_keys=True).encode()).hexdigest()
        require(catalog_digest is None or digest == catalog_digest, 'Corpus changed between trials')
        catalog_digest = digest
        for sample in data:
            result = indexed[sample['id']]
            expected, found = sample['expected_doc_ids'], result['doc_ids']
            rows.append({'id': sample['id'], 'trial': trial + 1, 'category': sample['category'],
                         'doc_ids': found, 'latency_ms': result['latency_ms'], 'mrr_at_k': mrr(found, expected),
                         'hit': {str(k): hit_at_k(found, expected, k) for k in (1, 3, 5)},
                         'recall': {str(k): recall_at_k(found, expected, k) for k in (1, 3, 5)}})
    def aggregate(items):
        return {'queries': len(items), 'mrr_at_k': sum(r['mrr_at_k'] for r in items) / len(items),
                'hit': {str(k): sum(r['hit'][str(k)] for r in items) / len(items) for k in (1, 3, 5)},
                'recall': {str(k): sum(r['recall'][str(k)] for r in items) / len(items) for k in (1, 3, 5)}}
    grouped = defaultdict(list)
    for row in rows:
        grouped[row['category']].append(row)
    return {'schema': 1, 'scope': 'public-seed-retrieval-not-production-corpus-or-answer-quality',
            'backend': 'seed-inmemory-bge-bm25-reranker', 'corpus_catalog_sha256': catalog_digest,
            'dataset_sha256': hashlib.sha256(json.dumps(data, sort_keys=True, ensure_ascii=False).encode()).hexdigest(),
            'sample_count': len(data), 'repeats': repeats, 'top_k': top_k,
            'overall': aggregate(rows), 'categories': {c: aggregate(r) for c, r in grouped.items()},
            'all_trials_hit_at_5_rate': sum(all(r['hit']['5'] for r in rows if r['id'] == s['id']) for s in data) / len(data),
            'results': rows}
def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dataset', type=Path, default=DEFAULT_DATASET)
    parser.add_argument('--command', required=True, help='Backend JSON argv array; no shell expansion')
    parser.add_argument('--timeout', type=int, default=180)
    parser.add_argument('--repeats', type=int, default=1)
    parser.add_argument('--top-k', type=int, default=5)
    parser.add_argument('--minimum-hit5', type=float, default=0.8)
    parser.add_argument('--report', type=Path, required=True, help='New file; never overwrite')
    args = parser.parse_args(argv)
    try:
        require(1 <= args.timeout <= 600, 'timeout must be 1..600')
        require(math.isfinite(args.minimum_hit5) and 0 <= args.minimum_hit5 <= 1, 'Invalid quality threshold')
        require(not args.report.exists(), 'Report exists')
        data = json.loads(args.dataset.read_text(encoding='utf-8'))
        command = json.loads(args.command)
        report = evaluate(data, lambda request: invoke_backend(command, request, args.timeout), args.top_k, args.repeats)
        passed = report['overall']['hit']['5'] >= args.minimum_hit5
        report['quality_gate'] = {'minimum_hit5': args.minimum_hit5, 'passed': passed}
        fd = os.open(str(args.report), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w', encoding='utf-8') as stream:
            json.dump(report, stream, ensure_ascii=False, indent=2)
        print(json.dumps({'status': 'PASSED' if passed else 'QUALITY_FAILED', 'overall': report['overall']}))
        return 0 if passed else 1
    except (EvaluationError, ValueError, OSError):
        print('EVALUATION_INVALID: check dataset, backend availability and output path; no scores certified', file=sys.stderr)
        return 2
if __name__ == '__main__':
    sys.exit(main())
