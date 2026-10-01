"""No-model, synthetic-only checks independent of Ragas scores and live manifests.

This is a bounded catalog-renderer grammar, NOT general semantic evaluation.
Unknown syntax is NOT_ASSESSED, never a pass. Frozen assertions do not reach the
business service. No network, model dependencies, credential reads or retries.
"""
import argparse
import copy
import json
import re
from decimal import Decimal
from pathlib import Path

from ragas_feedback import digest, replay_diagnostics, validate_dataset, write_new

AMOUNT = r'(?:0|[1-9][0-9]{0,8})(?:\.[0-9]{1,2})?'
PRICE = re.compile(r'目录售价\s+(' + AMOUNT + r')\s+元')
UNKNOWN_WEIGHT = '重量资料尚未核实'
UNKNOWN_CONTEXT = 'WEIGHT：known=false，资料未核实，无具体数值证据'
WEIGHT_VALUE = re.compile(r'重量\s*(' + AMOUNT + r')\s*克')
TOTAL = re.compile(r'按您明确给出的数量（(.+)）计算，目录价格合计\s+(' + AMOUNT +
                   r')\s+元（未包含未核实的运费或优惠）。')


def validate_assertions(spec):
    if (not isinstance(spec, dict) or set(spec) != {'schema_version', 'synthetic_only', 'policy', 'grammar', 'products', 'cases'}
            or type(spec['schema_version']) is not int or spec['schema_version'] != 1
            or spec['synthetic_only'] is not True or spec['policy'] != 'independent_frozen_catalog_assertions'
            or spec['grammar'] != 'catalog-renderer-v1'):
        raise ValueError('Versioned independent synthetic assertions required')
    products = spec['products']
    if not isinstance(products, dict) or not 1 <= len(products) <= 3:
        raise ValueError('Bounded frozen product set required')
    names = set()
    for code, product in products.items():
        if (not isinstance(code, str) or not re.fullmatch(r'[A-Z0-9-]{1,64}', code)
                or not isinstance(product, dict) or set(product) != {'name', 'price', 'weight_known'}
                or not isinstance(product['name'], str) or not 1 <= len(product['name']) <= 80
                or any(c in product['name'] for c in '\n\r：；、') or product['name'] in names
                or not isinstance(product['price'], str) or not re.fullmatch(AMOUNT, product['price'])
                or Decimal(product['price']) <= 0 or product['weight_known'] is not False):
            raise ValueError('Unsupported product fact assertion')
        names.add(product['name'])
    cases = spec['cases']
    if not isinstance(cases, list) or not 1 <= len(cases) <= 20:
        raise ValueError('Bounded assertion cases required')
    identifiers = set()
    for case in cases:
        if (not isinstance(case, dict) or set(case) != {'id', 'question', 'total', 'slots'}
                or not isinstance(case['id'], str) or not re.fullmatch(r'[a-z0-9-]{1,64}', case['id'])
                or case['id'] in identifiers or not isinstance(case['question'], str)
                or not 1 <= len(case['question']) <= 8000 or type(case['total']) is not bool
                or not isinstance(case['slots'], list) or not 1 <= len(case['slots']) <= 6):
            raise ValueError('Invalid assertion case')
        identifiers.add(case['id'])
        keys, quantities = set(), {}
        for slot in case['slots']:
            if (not isinstance(slot, dict) or set(slot) != {'product_code', 'field', 'quantity'}
                    or slot['product_code'] not in products or slot['field'] not in ('PRICE', 'WEIGHT')
                    or type(slot['quantity']) is not int or not 1 <= slot['quantity'] <= 100):
                raise ValueError('Unsupported slot assertion')
            key = (slot['product_code'], slot['field'])
            if key in keys or quantities.get(key[0], slot['quantity']) != slot['quantity']:
                raise ValueError('Duplicate/conflicting assertion slot')
            keys.add(key); quantities[key[0]] = slot['quantity']
        if case['total'] and any(slot['field'] != 'PRICE' for slot in case['slots']):
            raise ValueError('Totals require only verified price slots')
    return {case['id']: case for case in cases}


def evidence_id(slot):
    return slot['product_code'] + ':' + slot['field'] + (':UNVERIFIED' if slot['field'] == 'WEIGHT' else '')


def fact(body):
    price = PRICE.fullmatch(body)
    if price: return 'PRICE', Decimal(price[1])
    if body == UNKNOWN_WEIGHT: return 'WEIGHT', None
    weight = WEIGHT_VALUE.fullmatch(body)
    if weight: return 'WEIGHT', Decimal(weight[1])
    return None


def audit_case(row, case, spec):
    """Check entity-bound literals, all consumed lines, quantities and Decimal totals."""
    products = spec['products']
    expected_ids = [evidence_id(slot) for slot in case['slots']]
    if row['question'] != case['question'] or row['reference_context_ids'] != expected_ids:
        raise ValueError('Question/reference scope drift; independent gold review required')
    names = {product['name']: code for code, product in products.items()}
    expected = {(slot['product_code'], slot['field']): slot for slot in case['slots']}
    seen, failures, unsupported, totals = set(), set(), 0, []
    for line in row['response'].splitlines():
        total = TOTAL.fullmatch(line)
        if total:
            totals.append(total)
            continue
        if '：' not in line or not line.endswith('。'):
            unsupported += 1; continue
        name, body = line[:-1].split('：', 1)
        parsed = fact(body)
        if name not in names or parsed is None:
            unsupported += 1; continue
        code, (field, amount) = names[name], parsed
        key = (code, field)
        if key not in expected: failures.add('UNREQUESTED_PRODUCT_OR_FIELD')
        if key in seen: failures.add('DUPLICATE_OR_CONTRADICTORY_CLAIM')
        seen.add(key)
        gold = Decimal(products[code]['price']) if field == 'PRICE' else None
        if amount != gold:
            failures.add('WRONG_PRICE' if field == 'PRICE' else 'INVENTED_UNKNOWN_VALUE')
    missing = set(expected) - seen
    # An unparsed paraphrase may contain the missing fact. Do not label it a
    # semantic omission merely because this deliberately narrow grammar missed it.
    if missing and not unsupported: failures.add('MISSING_ANSWER_SLOT')
    expected_total = sum((Decimal(products[s['product_code']]['price']) * s['quantity']
                          for s in case['slots']), Decimal(0)) if case['total'] else None
    if len(totals) > int(case['total']) or (len(totals) < int(case['total']) and not unsupported):
        failures.add('MISSING_OR_UNREQUESTED_TOTAL')
    for total in totals:
        parts = total[1].split('、')
        wanted = [products[s['product_code']]['name'] + ' ' + str(s['quantity']) + ' 件' for s in case['slots']]
        if parts != wanted: failures.add('QUANTITY_OR_ENTITY_BINDING_ERROR')
        if not case['total'] or Decimal(total[2]) != expected_total: failures.add('WRONG_TOTAL')

    # Check actual materialized evidence independently. A response can be correct
    # yet ungrounded after a chunk is removed; a complete live manifest cannot hide it.
    evidence_seen = set()
    for identifier, text in zip(row['retrieved_context_ids'], row['retrieved_contexts']):
        slot = next((s for s in case['slots'] if evidence_id(s) == identifier), None)
        if slot is None:
            failures.add('UNREQUESTED_EVIDENCE'); continue
        product = products[slot['product_code']]
        prefix = product['name'] + '，本问题数量 ' + str(slot['quantity']) + ' 件；'
        if not text.startswith(prefix):
            failures.add('EVIDENCE_ENTITY_OR_QUANTITY_ERROR'); continue
        body = text[len(prefix):]
        if slot['field'] == 'WEIGHT':
            supported = body == UNKNOWN_CONTEXT
        else:
            match = PRICE.fullmatch(body)
            supported = match is not None and Decimal(match[1]) == Decimal(product['price'])
        if supported: evidence_seen.add(identifier)
        else: failures.add('EVIDENCE_FACT_MISMATCH')
    if set(expected_ids) - evidence_seen: failures.add('MISSING_OR_INVALID_EVIDENCE')
    status = 'FAIL' if failures else 'NOT_ASSESSED' if unsupported else 'PASS'
    return {'status': status, 'grammar': spec['grammar'], 'reason_codes': sorted(failures),
            'unparsed_lines': unsupported, 'requested_slots': len(expected),
            'answer_slots_present': len(set(expected) & seen), 'supported_evidence_slots': len(evidence_seen),
            'total_expected': str(expected_total) if expected_total is not None else None,
            'whole_answer_semantics_verified': False, 'execute_retry': False}


def negative_controls(rows):
    """Labeled renderer mutations; never retain/rebind a live evidence manifest."""
    by_id = {row['id']: row for row in rows}
    required = {'price-single', 'price-multiple', 'quantity-total', 'weight-single'}
    if not required <= set(by_id): raise ValueError('Frozen control source cases required')
    recipes = [
        ('wrong-price', 'price-single', lambda r: r.update(response=r['response'].replace('1999', '999'))),
        ('wrong-total', 'quantity-total', lambda r: r.update(response=r['response'].replace('12997', '12998'))),
        ('invented-weight', 'weight-single', lambda r: r.update(response='AirPods Pro（第二代）：重量 5 克。')),
        ('missing-product', 'price-multiple', lambda r: r.update(response=r['response'].splitlines()[0])),
        ('swapped-prices', 'price-multiple', lambda r: r.update(response=r['response'].replace('1999', 'SWAP').replace('8999', '1999').replace('SWAP', '8999'))),
        ('irrelevant-product', 'price-single', lambda r: r.update(response='MacBook Air M3：目录售价 8999 元。')),
        ('missing-evidence', 'price-multiple', lambda r: r.update(retrieved_contexts=r['retrieved_contexts'][1:], retrieved_context_ids=r['retrieved_context_ids'][1:])),
        ('contradictory-price', 'price-single', lambda r: r.update(response=r['response'] + '\nAirPods Pro（第二代）：目录售价 999 元。')),
    ]
    controls = []
    for name, parent, mutate in recipes:
        row = copy.deepcopy(by_id[parent]); mutate(row)
        if row == by_id[parent]: raise ValueError('Control source no longer matches frozen mutation')
        trace = row.pop('evidence_trace', None)
        if trace is not None: row['source_trace_sha256'] = digest(trace)
        row.update(id='audit-control-' + name, source='negative_control')
        controls.append((row, parent))
    return controls


def run_audits(dataset, spec, prior=None, include_controls=False):
    rows = validate_dataset(dataset); cases = validate_assertions(spec)
    if set(cases) != {row['id'] for row in rows}:
        raise ValueError('Exact frozen case set required')
    replay = replay_diagnostics(dataset, prior) if prior is not None else None
    # A diagnostic replay may reclassify the historical action. This audit only
    # embeds validated numerical observations, not a falsely named original action.
    scored = {row['id']: {key: row[key] for key in ('runs', 'summary', 'id_metrics')}
              for row in replay['cases']} if replay else {}
    result = []
    pairs = [(row, row['id']) for row in rows]
    if include_controls: pairs += negative_controls(rows)
    for row, parent in pairs:
        validate_dataset({'schema_version': 1, 'synthetic_only': True, 'cases': [row]})
        entry = {'id': row['id'], 'source': row['source'], 'assertion_case_id': parent,
                 'input_sha256': digest(row), 'audit': audit_case(row, cases[parent], spec)}
        if row['id'] in scored:
            # Original scores, errors and timings remain unchanged. No control
            # inherits its parent's Ragas scores or full-coverage manifest.
            entry['ragas_original'] = scored[row['id']]
        result.append(entry)
    return {'schema_version': 1, 'mode': 'INDEPENDENT_RENDERER_AUDIT', 'synthetic_only': True,
            'dataset_sha256': digest(dataset), 'assertions_sha256': digest(spec),
            'source_score_report_sha256': digest(prior) if prior is not None else None,
            'new_model_calls': 0, 'retries_executed': 0, 'ragas_scores_adjusted': False,
            'thresholds_calibrated': False, 'business_artifacts_modified': False,
            'live_user_conversations_evaluated': False, 'cases': result,
            'summary': {origin: {status: sum(r['source'] == origin and r['audit']['status'] == status for r in result)
                                 for status in ('PASS', 'FAIL', 'NOT_ASSESSED')}
                        for origin in sorted({r['source'] for r in result})}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dataset', required=True, type=Path)
    parser.add_argument('--assertions', required=True, type=Path)
    parser.add_argument('--score-report', type=Path)
    parser.add_argument('--include-controls', action='store_true')
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    def load(path):
        if path.stat().st_size > 4194304: raise ValueError('Bounded JSON input required')
        return json.loads(path.read_text(encoding='utf-8'))
    report = run_audits(load(args.dataset), load(args.assertions),
                        load(args.score_report) if args.score_report else None, args.include_controls)
    write_new(args.output, report)
    print(json.dumps({'summary': report['summary'], 'new_model_calls': 0, 'retries_executed': 0}))


if __name__ == '__main__': main()
