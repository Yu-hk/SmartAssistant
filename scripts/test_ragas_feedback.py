import asyncio
import contextlib
import copy
import io
import json
import tempfile
import unittest
from pathlib import Path

from ragas_feedback import METRICS, diagnose, digest, embedding_values, error_metadata, evaluate_cases, id_metrics, judge_options, select_case, summarize, validate_dataset, write_new
from collect_ragas_product import sample, validate_questions
from make_ragas_controls import controls
from run_ragas_feedback_server import container_command


def dataset():
    return {'schema_version': 1, 'synthetic_only': True, 'cases': [{
        'id': 'case', 'source': 'synthetic_fixture', 'question': '两款商品分别多少钱？',
        'response': '甲10元，乙20元。', 'reference': '甲10元，乙20元。',
        'retrieved_contexts': ['甲10元', '无关材料', '乙20元'],
        'retrieved_context_ids': ['a', 'x', 'b'], 'reference_context_ids': ['a', 'b']}]}


def summary(value=1, count=3, spread=0):
    return {name: {'median': value, 'range': spread, 'complete': True, 'valid_runs': count} for name in METRICS}


class FeedbackContract(unittest.TestCase):
    def test_valid_dataset(self):
        self.assertEqual(len(validate_dataset(dataset())), 1)

    def test_explicit_judge_mode_avoids_provider_default_truncation(self):
        self.assertEqual(judge_options(), {'max_tokens': 4096, 'extra_body': {'thinking': {'type': 'disabled'}}})
        self.assertEqual(judge_options(1024, 'enabled')['max_tokens'], 1024)
        with self.assertRaises(ValueError): judge_options(99999)
        with self.assertRaises(ValueError): judge_options(4096, 'unbounded')

    def test_focused_selection_retains_original_provenance(self):
        data = dataset()
        selected = select_case(data, 'case')
        self.assertEqual(selected['selection']['parent_dataset_sha256'], digest(data))
        self.assertEqual(selected['cases'], data['cases'])
        self.assertNotIn('selection', data)
        with self.assertRaises(ValueError): select_case(data, 'absent')

    def test_embedding_valid(self):
        self.assertEqual(embedding_values({'embedding': [0, 1]}, 2), [0, 1])

    def test_embedding_invalid(self):
        for vector in ([], [0, 0], [float('nan')], [float('inf')], ['1'], [True], None):
            with self.assertRaises(ValueError): embedding_values({'embedding': vector})

    def test_embedding_dimension_drift(self):
        with self.assertRaises(ValueError): embedding_values({'embedding': [1, 2]}, 3)

    def test_server_container_is_isolated(self):
        command = container_command(Path('/opt/smart-assistant/eval/ragas-feedback-test'),
                                    'sha256:' + 'a' * 64, 'new.json', 'dataset.json', '10.0.0.2', 3)
        self.assertIn('--read-only', command)
        self.assertIn('no-new-privileges', command)
        self.assertIn('--rm', command)
        self.assertIn('2g', command)
        mounts = [command[index + 1] for index, item in enumerate(command) if item == '-v']
        self.assertEqual(len(mounts), 2)
        self.assertEqual(sum(mount.endswith(':rw') for mount in mounts), 1)
        self.assertTrue(next(mount for mount in mounts if mount.endswith(':rw')).endswith('outputs:/outputs:rw'))
        self.assertNotIn('docker.sock', ' '.join(command))
        self.assertNotIn('DEEPSEEK_API_KEY=', ' '.join(command))

    def test_server_rejects_public_embedding(self):
        with self.assertRaises(ValueError):
            container_command(Path('/tmp/eval'), 'sha256:' + 'a' * 64, 'new.json', 'dataset.json', '8.8.8.8', 3)

    def test_server_rejects_unpinned_image(self):
        with self.assertRaises(ValueError):
            container_command(Path('/tmp/eval'), 'python:latest', 'new.json', 'dataset.json', '10.0.0.2', 3)

    def test_server_output_confined(self):
        with self.assertRaises(ValueError):
            container_command(Path('/tmp/eval'), 'sha256:' + 'a' * 64, '../new.json', 'dataset.json', '10.0.0.2', 3)

    def test_server_input_confined(self):
        with self.assertRaises(ValueError):
            container_command(Path('/tmp/eval'), 'sha256:' + 'a' * 64, 'new.json', '/etc/passwd', '10.0.0.2', 3)

    def test_judge_budget_and_case_argument_are_bounded(self):
        args = (Path('/tmp/eval'), 'sha256:' + 'a' * 64, 'new.json', 'dataset.json', '10.0.0.2', 3)
        self.assertIn('4096', container_command(*args, 4096, 'quantity-total'))
        with self.assertRaises(ValueError): container_command(*args, 99999)
        with self.assertRaises(ValueError): container_command(*args, 4096, '../private')
        with self.assertRaises(ValueError): container_command(*args, 4096, 'case', 'unbounded')

    def test_id_metrics(self):
        metrics = id_metrics(dataset()['cases'][0])
        self.assertEqual(metrics['id_recall'], 1)
        self.assertAlmostEqual(metrics['id_precision'], 2 / 3)
        self.assertAlmostEqual(metrics['id_average_precision'], 5 / 6)

    def test_missing_context_rejected(self):
        data = dataset()
        data['cases'][0]['retrieved_context_ids'].append('missing')
        with self.assertRaises(ValueError): validate_dataset(data)

    def test_duplicate_context_rejected(self):
        data = dataset()
        data['cases'][0]['retrieved_context_ids'][2] = 'a'
        with self.assertRaises(ValueError): validate_dataset(data)

    def test_real_conversations_rejected(self):
        data = dataset(); data['synthetic_only'] = False
        with self.assertRaises(ValueError): validate_dataset(data)

    def test_no_reference_rejected(self):
        data = dataset(); data['cases'][0]['reference'] = ''
        with self.assertRaises(ValueError): validate_dataset(data)

    def test_unknown_origin_rejected(self):
        data = dataset(); data['cases'][0]['source'] = 'user_conversation'
        with self.assertRaises(ValueError): validate_dataset(data)

    def test_duplicate_case_rejected(self):
        data = dataset(); data['cases'].append(copy.deepcopy(data['cases'][0]))
        with self.assertRaises(ValueError): validate_dataset(data)

    def test_report_refuses_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'report.json'
            write_new(path, {'original': 1})
            with self.assertRaises(FileExistsError): write_new(path, {'replacement': 1})
            self.assertEqual(json.loads(path.read_text()), {'original': 1})

    def test_hash_tracks_order(self):
        row = dataset()['cases'][0]; other = copy.deepcopy(row)
        other['retrieved_contexts'].reverse()
        self.assertNotEqual(digest(row), digest(other))

    def test_no_change_is_not_execution(self):
        result = diagnose(summary())
        self.assertEqual(result['action'], 'NO_CHANGE_SUGGESTED')
        self.assertFalse(result['execute_retry'])

    def test_fewer_repeats_need_more_data(self):
        self.assertEqual(diagnose(summary(count=1))['action'], 'MORE_REPEATS_REQUIRED')

    def test_judge_variance_blocks_weight_change(self):
        self.assertEqual(diagnose(summary(spread=.4))['action'], 'JUDGE_CALIBRATION_REQUIRED')

    def test_low_recall(self):
        scores = summary(); scores['context_recall']['median'] = .5
        self.assertEqual(diagnose(scores)['action'], 'RETRIEVAL_COVERAGE_REVIEW')

    def test_low_precision(self):
        scores = summary(); scores['context_precision']['median'] = .5
        self.assertEqual(diagnose(scores)['action'], 'RETRIEVAL_RANKING_REVIEW')

    def test_low_faithfulness_not_retrieval(self):
        scores = summary(); scores['faithfulness']['median'] = .5
        self.assertEqual(diagnose(scores)['action'], 'GENERATION_GROUNDING_REVIEW')

    def test_high_relevancy_not_enough(self):
        scores = summary(); scores['context_recall']['median'] = 0
        self.assertNotEqual(diagnose(scores)['action'], 'NO_CHANGE_SUGGESTED')

    def test_low_relevancy(self):
        scores = summary(); scores['answer_relevancy']['median'] = .5
        self.assertEqual(diagnose(scores)['action'], 'ANSWER_SCOPE_REVIEW')

    def test_incomplete_runs_not_quality_zero(self):
        runs = [{'scores': {name: 1 for name in METRICS}, 'errors': {}}]
        scores = summarize(runs, 3)
        self.assertEqual(scores['context_recall']['median'], 1)
        self.assertFalse(scores['context_recall']['complete'])
        self.assertEqual(diagnose(scores)['action'], 'EVALUATION_UNAVAILABLE')

    def test_scoring_repeats_identical_input(self):
        seen = []
        async def scorer(name, row):
            seen.append(digest(row)); return 1
        with contextlib.redirect_stdout(io.StringIO()):
            result = asyncio.run(evaluate_cases(dataset(), scorer, 3))
        self.assertEqual(len(seen), 12)
        self.assertEqual(len(set(seen)), 1)
        self.assertEqual(result['retries_executed'], 0)

    def test_error_messages_not_persisted(self):
        async def scorer(name, row):
            raise RuntimeError('secret-token-in-response')
        with contextlib.redirect_stdout(io.StringIO()):
            result = asyncio.run(evaluate_cases(dataset(), scorer, 3))
        self.assertNotIn('secret-token', json.dumps(result))
        self.assertEqual(result['cases'][0]['feedback']['action'], 'EVALUATION_UNAVAILABLE')

    def test_retry_diagnostics_never_copy_completions_or_messages(self):
        from types import SimpleNamespace
        error = RuntimeError('secret-token-in-response')
        error.failed_attempts = [SimpleNamespace(exception=ValueError('secret-message'), completion='secret-body')]
        result = error_metadata(error)
        self.assertEqual(result, {'type': 'RuntimeError', 'attempt_error_types': ['ValueError']})
        self.assertNotIn('secret', json.dumps(result))

    def test_invalid_metric_values(self):
        for value in (float('nan'), float('inf'), -2, 2, True, '1'):
            async def scorer(name, row): return value
            with contextlib.redirect_stdout(io.StringIO()):
                report = asyncio.run(evaluate_cases(dataset(), scorer, 1))
            self.assertEqual(report['cases'][0]['runs'][0]['scores'], {})

    def test_negative_relevancy_is_valid_cosine_not_error(self):
        async def scorer(name, row): return -.2 if name == 'answer_relevancy' else 1
        with contextlib.redirect_stdout(io.StringIO()):
            report = asyncio.run(evaluate_cases(dataset(), scorer, 3))
        self.assertEqual(report['cases'][0]['summary']['answer_relevancy']['median'], -.2)
        self.assertEqual(report['cases'][0]['feedback']['action'], 'ANSWER_SCOPE_REVIEW')

    def test_repeat_bound(self):
        async def scorer(name, row): return 1
        for repeats in (0, 11):
            with self.assertRaises(ValueError): asyncio.run(evaluate_cases(dataset(), scorer, repeats))

    def test_actual_evidence_and_actual_answer(self):
        case = dataset()['cases'][0]
        result = {'status': 'SUCCEEDED', 'answer': '实际答案', 'data': {
            'deterministic': True, 'productEntityResolutionVersion': 1,
            'queryPlan': {'originalQuestion': case['question']},
            'productEvidence': [{'status': 'RESOLVED', 'productCode': 'a', 'productName': '甲',
                                 'quantity': 2, 'fields': {'PRICE': {'known': True, 'evidence': '10元'}}}]}}
        row = sample(case, result)
        self.assertEqual(row['response'], '实际答案')
        self.assertEqual(row['retrieved_context_ids'], ['a:PRICE'])
        self.assertIn('2 件', row['retrieved_contexts'][0])
        self.assertEqual(row['reference'], case['reference'])
        result['data']['productEvidence'][0]['fields'] = {'WEIGHT': {'known': False, 'evidence': ''}}
        row = sample(case, result)
        self.assertEqual(row['retrieved_context_ids'], ['a:WEIGHT:UNVERIFIED'])
        self.assertIn('known=false', row['retrieved_contexts'][0])
        self.assertNotIn('10元', row['retrieved_contexts'][0])
        result['data']['queryPlan']['originalQuestion'] = 'different'
        with self.assertRaises(ValueError): sample(case, result)

    def test_unsupported_product_contract(self):
        with self.assertRaises(ValueError): sample(dataset()['cases'][0], {'status': 'FAILED'})

    def test_frozen_question_file(self):
        path = Path(__file__).resolve().parents[1] / 'data/ragas_product_questions.json'
        data = json.loads(path.read_text(encoding='utf-8'))
        self.assertEqual(len(validate_questions(data)), 4)
        data['reference_policy'] = 'copied_candidate_answer'
        with self.assertRaises(ValueError): validate_questions(data)

    def test_negative_controls_preserve_frozen_reference(self):
        data = dataset()
        single = copy.deepcopy(data['cases'][0])
        single.update(id='price-single', response='AirPods Pro售价1999元。',
                      reference='AirPods Pro售价1999元。', retrieved_contexts=['AirPods Pro1999元'],
                      retrieved_context_ids=['a'], reference_context_ids=['a'])
        multiple = copy.deepcopy(data['cases'][0]); multiple['id'] = 'price-multiple'
        data['cases'] = [single, multiple]
        result = controls(data)
        self.assertEqual(result['cases'][0]['reference'], single['reference'])
        self.assertIn('999', result['cases'][0]['response'])
        self.assertEqual(result['cases'][0]['retrieved_contexts'], single['retrieved_contexts'])
        self.assertEqual(result['cases'][2]['reference_context_ids'], multiple['reference_context_ids'])
        self.assertLess(id_metrics(result['cases'][2])['id_recall'], 1)
        self.assertEqual(data['cases'][0]['response'], 'AirPods Pro售价1999元。')


if __name__ == '__main__': unittest.main()
