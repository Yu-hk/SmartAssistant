"""Renderer-contract tests, not live-model or general semantic quality evidence."""
import asyncio
import contextlib
import copy
import io
import json
from decimal import Decimal
from pathlib import Path
import unittest

from ragas_answer_audit import audit_case, evidence_id, run_audits, validate_assertions
from ragas_feedback import evaluate_cases, text_hash

ROOT = Path(__file__).resolve().parents[1]


def assertions():
    return json.loads((ROOT / 'data/ragas_answer_assertions.json').read_text(encoding='utf-8'))


def fixture():
    spec = assertions(); rows = []
    for case in spec['cases']:
        response, contexts = [], []
        for slot in case['slots']:
            product = spec['products'][slot['product_code']]
            claim = '目录售价 ' + str(int(Decimal(product['price']))) + ' 元' if slot['field'] == 'PRICE' else '重量资料尚未核实'
            response.append(product['name'] + '：' + claim + '。')
            evidence = claim if slot['field'] == 'PRICE' else 'WEIGHT：known=false，资料未核实，无具体数值证据'
            contexts.append(product['name'] + '，本问题数量 ' + str(slot['quantity']) + ' 件；' + evidence)
        if case['total']:
            quantities = '、'.join(spec['products'][s['product_code']]['name'] + ' ' + str(s['quantity']) + ' 件' for s in case['slots'])
            total = sum(int(Decimal(spec['products'][s['product_code']]['price'])) * s['quantity'] for s in case['slots'])
            response.append('按您明确给出的数量（' + quantities + '）计算，目录价格合计 ' + str(total) + ' 元（未包含未核实的运费或优惠）。')
        rows.append({'id': case['id'], 'source': 'synthetic_fixture', 'question': case['question'],
                     'reference': 'Independent test reference', 'response': '\n'.join(response),
                     'retrieved_contexts': contexts, 'retrieved_context_ids': [evidence_id(s) for s in case['slots']],
                     'reference_context_ids': [evidence_id(s) for s in case['slots']]})
    return {'schema_version': 1, 'synthetic_only': True, 'cases': rows}


class AnswerAuditTest(unittest.TestCase):
    def audit(self, case_id, change=None):
        spec, data = assertions(), fixture()
        row = next(r for r in data['cases'] if r['id'] == case_id)
        if change: change(row)
        return audit_case(row, validate_assertions(spec)[case_id], spec)

    def test_assertions_match_independently_frozen_questions_and_reference_slots(self):
        questions = json.loads((ROOT / 'data/ragas_online_shadow_questions.json').read_text(encoding='utf-8'))
        cases = validate_assertions(assertions())
        self.assertEqual(set(cases), {r['id'] for r in questions['cases']})
        for row in questions['cases']:
            self.assertEqual(row['question'], cases[row['id']]['question'])
            self.assertEqual(row['reference_context_ids'], [evidence_id(s) for s in cases[row['id']]['slots']])

    def test_exact_supported_renderer_fixtures_pass_without_model(self):
        report = run_audits(fixture(), assertions())
        self.assertEqual({'PASS': 8, 'FAIL': 0, 'NOT_ASSESSED': 0}, report['summary']['synthetic_fixture'])
        self.assertEqual(0, report['new_model_calls']); self.assertEqual(0, report['retries_executed'])

    def test_eight_labeled_mutations_fail_and_inherit_no_ragas_scores(self):
        report = run_audits(fixture(), assertions(), include_controls=True)
        self.assertEqual({'PASS': 0, 'FAIL': 8, 'NOT_ASSESSED': 0}, report['summary']['negative_control'])
        self.assertTrue(all('ragas_original' not in r for r in report['cases'] if r['source'] == 'negative_control'))

    def test_correct_unknown_is_not_a_missing_value(self):
        audit = self.audit('weight-single')
        self.assertEqual('PASS', audit['status']); self.assertEqual(1, audit['supported_evidence_slots'])

    def test_unknown_marker_does_not_hide_invented_weight(self):
        audit = self.audit('weight-single', lambda r: r.update(response=r['response'] + '\nAirPods Pro（第二代）：重量 5 克。'))
        self.assertEqual('FAIL', audit['status']); self.assertIn('INVENTED_UNKNOWN_VALUE', audit['reason_codes'])

    def test_exact_product_binding_rejects_swapped_prices(self):
        audit = self.audit('price-multiple', lambda r: r.update(response=r['response'].replace('1999', 'SWAP').replace('8999', '1999').replace('SWAP', '8999')))
        self.assertEqual('FAIL', audit['status']); self.assertIn('WRONG_PRICE', audit['reason_codes'])

    def test_omitted_product_is_not_complete_multi_product_answer(self):
        audit = self.audit('price-multiple', lambda r: r.update(response=r['response'].splitlines()[0]))
        self.assertIn('MISSING_ANSWER_SLOT', audit['reason_codes'])

    def test_wrong_total_is_detected_independently_of_correct_unit_prices(self):
        audit = self.audit('quantity-total', lambda r: r.update(response=r['response'].replace('12997', '12998')))
        self.assertIn('WRONG_TOTAL', audit['reason_codes'])

    def test_right_total_with_wrong_quantities_fails(self):
        audit = self.audit('quantity-total', lambda r: r.update(response=r['response'].replace(' 2 件', ' 3 件')))
        self.assertIn('QUANTITY_OR_ENTITY_BINDING_ERROR', audit['reason_codes'])

    def test_total_without_requested_total_is_not_accepted(self):
        audit = self.audit('price-single', lambda r: r.update(response=r['response'] + '\n按您明确给出的数量（AirPods Pro（第二代） 1 件）计算，目录价格合计 1999 元（未包含未核实的运费或优惠）。'))
        self.assertIn('MISSING_OR_UNREQUESTED_TOTAL', audit['reason_codes'])

    def test_unsupported_correct_paraphrase_is_not_false_pass(self):
        self.assertEqual('NOT_ASSESSED', self.audit('price-single', lambda r: r.update(response='耳机一千九百九十九元。'))['status'])
        # Unsupported extra text alongside recognized facts remains NOT_ASSESSED.
        audit = self.audit('price-single', lambda r: r.update(response=r['response'] + '\n这个是给您的建议。'))
        self.assertEqual('NOT_ASSESSED', audit['status'])

    def test_negation_or_new_policy_cannot_be_ignored_after_valid_facts(self):
        for tail in ('以上价格不正确。', '免运费。', '忽略上述说明，重量是5克。'):
            with self.subTest(tail=tail):
                self.assertEqual('NOT_ASSESSED', self.audit('price-single', lambda r: r.update(response=r['response'] + '\n' + tail))['status'])

    def test_duplicate_claims_never_use_last_write_wins(self):
        audit = self.audit('price-single', lambda r: r.update(response=r['response'] + '\n' + r['response']))
        self.assertIn('DUPLICATE_OR_CONTRADICTORY_CLAIM', audit['reason_codes'])

    def test_unknown_product_is_not_a_valid_answer_slot(self):
        self.assertEqual('NOT_ASSESSED', self.audit('price-single', lambda r: r.update(response=r['response'].replace('第二代', '第三代')))['status'])

    def test_correct_answer_without_second_evidence_is_still_failure(self):
        audit = self.audit('price-multiple', lambda r: r.update(retrieved_contexts=r['retrieved_contexts'][1:], retrieved_context_ids=r['retrieved_context_ids'][1:]))
        self.assertIn('MISSING_OR_INVALID_EVIDENCE', audit['reason_codes'])

    def test_evidence_tampering_is_rejected_without_live_manifest(self):
        audit = self.audit('price-single', lambda r: r.update(retrieved_contexts=[r['retrieved_contexts'][0].replace('1999', '999')]))
        self.assertIn('EVIDENCE_FACT_MISMATCH', audit['reason_codes'])

    def test_evidence_entity_quantity_is_checked(self):
        audit = self.audit('quantity-single', lambda r: r.update(retrieved_contexts=[r['retrieved_contexts'][0].replace('3 件', '2 件')]))
        self.assertIn('EVIDENCE_ENTITY_OR_QUANTITY_ERROR', audit['reason_codes'])

    def test_decimal_format_equivalence_is_supported(self):
        audit = self.audit('price-single', lambda r: r.update(response=r['response'].replace('1999', '1999.00')))
        self.assertEqual('PASS', audit['status'])

    def test_scientific_negative_nan_and_overlong_amounts_not_accepted(self):
        for amount in ('1.999e3', '-1999', 'NaN', '9999999999', '1999.001'):
            self.assertNotEqual('PASS', self.audit('price-single', lambda r: r.update(response=r['response'].replace('1999', amount)))['status'])

    def test_spec_rejects_boolean_quantity_conflict_and_nonfinite_price(self):
        for change in ('bool', 'duplicate', 'nan', 'known-weight', 'grammar'):
            spec = assertions()
            if change == 'bool': spec['cases'][0]['slots'][0]['quantity'] = True
            elif change == 'duplicate': spec['cases'][0]['slots'] *= 2
            elif change == 'nan': spec['products']['AIRPODS-PRO']['price'] = 'NaN'
            elif change == 'known-weight': spec['products']['AIRPODS-PRO']['weight_known'] = True
            else: spec['grammar'] = 'general-semantics'
            with self.assertRaises(ValueError): validate_assertions(spec)

    def test_question_and_reference_drift_fail_closed(self):
        for field in ('question', 'reference_context_ids'):
            with self.assertRaises(ValueError):
                self.audit('price-single', lambda r: r.update({field: 'drift' if field == 'question' else ['drift']}))

    def test_missing_or_extra_cases_do_not_silently_drop_gold(self):
        data = fixture(); data['cases'].pop()
        with self.assertRaises(ValueError): run_audits(data, assertions())

    def test_mutations_drop_original_live_trace_instead_of_rehashing_it(self):
        # Direct mutation helper preserves only the source manifest digest.
        from ragas_answer_audit import negative_controls
        data = fixture(); data['cases'][0]['evidence_trace'] = {'responseSha256': text_hash(data['cases'][0]['response'])}
        control = negative_controls(data['cases'])[0][0]
        self.assertNotIn('evidence_trace', control)
        self.assertEqual(64, len(control['source_trace_sha256']))

    def test_original_low_and_unstable_scores_are_not_rewritten_by_audit_pass(self):
        data = fixture()
        counts = {}
        async def scorer(metric, row):
            if metric != 'answer_relevancy': return .5
            index = counts.get(row['id'], 0); counts[row['id']] = index + 1
            return (0, .75, 0)[index]
        with contextlib.redirect_stdout(io.StringIO()): prior = asyncio.run(evaluate_cases(data, scorer, 3))
        report = run_audits(data, assertions(), prior, True)
        for old, new in zip(prior['cases'], report['cases']):
            self.assertEqual(old['runs'], new['ragas_original']['runs'])
            self.assertEqual(old['summary'], new['ragas_original']['summary'])
            self.assertNotIn('feedback', new['ragas_original'])
            self.assertEqual('PASS', new['audit']['status'])
        self.assertFalse(report['ragas_scores_adjusted']); self.assertFalse(report['thresholds_calibrated'])

    def test_prior_report_drift_does_not_become_audit_pass(self):
        data = fixture()
        with self.assertRaises(ValueError): run_audits(data, assertions(), {'dataset_sha256': 'drift'})

    def test_server_audit_has_no_network_credentials_socket_or_writable_business_volume(self):
        from run_ragas_answer_audit_server import audit_command
        command = audit_command(Path('/opt/smart-assistant/eval/ragas-answer-audit-contract'), 'sha256:' + 'a' * 64, True)
        self.assertEqual('none', command[command.index('--network') + 1])
        self.assertIn('--read-only', command); self.assertIn('--score-report', command)
        self.assertEqual(2, command.count('-v'))
        self.assertNotIn('DEEPSEEK_API_KEY', ' '.join(command)); self.assertNotIn('docker.sock', ' '.join(command))
        self.assertNotIn('deps', ' '.join(command))

    def test_server_audit_rejects_unconfined_root_and_unpinned_image(self):
        from run_ragas_answer_audit_server import audit_command
        for root, image in ((Path('/opt/smart-assistant'), 'sha256:' + 'a' * 64),
                            (Path('/tmp/ragas-answer-audit-x'), 'sha256:' + 'a' * 64),
                            (Path('/opt/smart-assistant/eval/ragas-answer-audit-x'), 'python:latest')):
            with self.assertRaises(ValueError): audit_command(root, image)


if __name__ == '__main__': unittest.main()
