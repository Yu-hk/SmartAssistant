"""No-model diagnostic contracts; fixture scores are not quality evidence."""
import asyncio
import contextlib
import io
import json
from pathlib import Path
import unittest

from ragas_feedback import evaluate_cases, replay_diagnostics, stage_diagnosis, text_hash
from collect_ragas_product import validate_questions
from test_ragas_feedback import dataset, traced_dataset, summary


def scored(data, error=False):
    async def scorer(name, row):
        if error: raise RuntimeError('private model text must not be persisted')
        return -.2 if name == 'answer_relevancy' else 1
    with contextlib.redirect_stdout(io.StringIO()):
        return asyncio.run(evaluate_cases(data, scorer, 3))


def known_multi():
    row = traced_dataset()['cases'][0]
    row.update(retrieved_contexts=['甲10元', '乙20元'], retrieved_context_ids=['a:PRICE', 'b:PRICE'])
    trace = row['evidence_trace']
    trace['coverage'].update(known=2, unknown=0)
    trace['coverage']['slots'][1]['state'] = 'KNOWN'
    trace['finalEvidence'] = [{'id': i, 'sha256': text_hash(t), 'rank': n}
                             for n, (i, t) in enumerate(zip(row['retrieved_context_ids'], row['retrieved_contexts']), 1)]
    return row


class ShadowReplayTest(unittest.TestCase):
    def test_frozen_online_questions_are_eight_readonly_cases(self):
        root = Path(__file__).resolve().parents[1]
        questions = json.loads((root / 'data/ragas_online_shadow_questions.json').read_text(encoding='utf-8'))
        rows = validate_questions(questions)
        self.assertEqual(8, len(rows))
        self.assertEqual(2, sum(any(i.endswith(':UNVERIFIED') for i in r['reference_context_ids']) for r in rows))
        for row in rows:
            self.assertTrue(all(i.startswith(('AIRPODS-PRO:', 'MACBOOK-AIR-M3:')) for i in row['reference_context_ids']))

    def test_complete_multi_product_low_relevance_is_not_a_retrieval_trigger(self):
        scores = summary(); scores['answer_relevancy']['median'] = .77
        result = stage_diagnosis(scores, known_multi())
        self.assertEqual('MULTI_PRODUCT_RELEVANCY_REVIEW', result['action'])
        self.assertEqual('ANSWER_SCOPE_REVIEW', result['score_trigger_action'])
        self.assertFalse(result['execute_retry']); self.assertFalse(result['ragas_scores_adjusted'])

    def test_unknown_observation_survives_unstable_scoring(self):
        result = stage_diagnosis(summary(spread=.75), traced_dataset()['cases'][0])
        self.assertEqual('JUDGE_CALIBRATION_REQUIRED', result['action'])
        self.assertIn('EXPLICIT_UNKNOWN_EVIDENCE', result['review_flags'])

    def test_unknown_metadata_does_not_hide_grounding_failure(self):
        scores = summary(); scores['faithfulness']['median'] = .2
        self.assertEqual('GENERATION_GROUNDING_REVIEW', stage_diagnosis(scores, traced_dataset()['cases'][0])['action'])

    def test_arithmetic_observation_survives_unavailable_metric(self):
        row = known_multi(); row['evidence_trace']['arithmetic'] = {'relation': 'TOTAL'}
        scores = summary(); scores['faithfulness']['complete'] = False
        result = stage_diagnosis(scores, row)
        self.assertEqual('EVALUATION_UNAVAILABLE', result['action'])
        self.assertIn('DETERMINISTIC_TOTAL_CONTEXT_REVIEW', result['review_flags'])

    def test_replay_preserves_scores_durations_errors_and_provenance(self):
        data = dataset(); original = scored(data)
        replay = replay_diagnostics(data, original)
        self.assertEqual(original['cases'][0]['runs'], replay['cases'][0]['runs'])
        self.assertEqual(original['cases'][0]['summary'], replay['cases'][0]['summary'])
        self.assertEqual(0, replay['new_model_calls']); self.assertEqual(0, replay['retries_executed'])
        self.assertFalse(replay['ragas_scores_adjusted'])
        self.assertEqual(64, len(replay['source_report_sha256']))

    def test_failed_original_runs_remain_failed(self):
        data = dataset(); original = scored(data, error=True)
        replay = replay_diagnostics(data, original)
        self.assertEqual('EVALUATION_UNAVAILABLE', replay['cases'][0]['feedback']['action'])
        self.assertNotIn('private model text', json.dumps(replay))

    def test_dataset_and_case_digest_drift_are_rejected(self):
        for field in ('dataset_sha256', 'input_sha256', 'id'):
            data = dataset(); original = scored(data)
            if field == 'dataset_sha256': original[field] = '0' * 64
            else: original['cases'][0][field] = 'changed'
            with self.assertRaises(ValueError): replay_diagnostics(data, original)

    def test_summary_and_reference_metrics_drift_are_rejected(self):
        for field in ('summary', 'id_metrics'):
            data = dataset(); original = scored(data); original['cases'][0][field] = {}
            with self.assertRaises(ValueError): replay_diagnostics(data, original)

    def test_missing_metric_and_invalid_score_are_rejected(self):
        for change in ('missing', 'nan', 'boolean', 'extra'):
            data = dataset(); original = scored(data); scores = original['cases'][0]['runs'][0]['scores']
            if change == 'missing': scores.pop('faithfulness')
            elif change == 'extra': scores['unapproved'] = 1
            else: scores['faithfulness'] = float('nan') if change == 'nan' else True
            with self.assertRaises(ValueError): replay_diagnostics(data, original)

    def test_duplicate_runs_and_unbounded_duration_are_rejected(self):
        for change in ('repeat', 'duration'):
            data = dataset(); original = scored(data); run = original['cases'][0]['runs'][1]
            if change == 'repeat': run['repeat'] = 1
            else: run['duration_ms']['faithfulness'] = -1
            with self.assertRaises(ValueError): replay_diagnostics(data, original)

    def test_raw_error_message_is_rejected(self):
        data = dataset(); original = scored(data, error=True)
        original['cases'][0]['runs'][0]['errors']['faithfulness']['message'] = 'secret body'
        with self.assertRaises(ValueError): replay_diagnostics(data, original)

    def test_legacy_fixture_keeps_existing_diagnosis(self):
        scores = summary(); scores['answer_relevancy']['median'] = .5
        result = stage_diagnosis(scores, dataset()['cases'][0])
        self.assertEqual('ANSWER_SCOPE_REVIEW', result['action'])
        self.assertNotIn('review_flags', result)


if __name__ == '__main__': unittest.main()
