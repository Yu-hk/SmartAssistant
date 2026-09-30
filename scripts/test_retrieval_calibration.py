import copy
import json
import math
from pathlib import Path
import tempfile
import unittest

import retrieval_calibration as calibration
import run_retrieval_calibration_server as runner


def fixture():
    cases=[{'id':'dev','split':'development','family':'dev-family','knowledge_base':'product_knowledge',
            'question':'开发问题','expected_doc_ids':['DEV']},
           {'id':'hold','split':'holdout','family':'hold-family','knowledge_base':'product_knowledge',
            'question':'留出问题','expected_doc_ids':['HOLD']}]
    dataset={'schema_version':1,'synthetic_only':True,'reference_policy':'human_authored_frozen_before_collection',
             'corpus':'public_knowledge_seed','top_k':2,'cases':cases}
    corpus={'product_knowledge':[{'id':item,'textSha256':'a'*64} for item in ('DEV','HOLD','NOISE')],
            'order_knowledge':[{'id':'ORD','textSha256':'b'*64}]}
    results=[]
    for row in cases:
        rankings={}
        gold=row['expected_doc_ids'][0]
        for strategy in calibration.STRATEGIES:
            sparse=.5 if strategy.startswith('adaptive') else int(strategy.split('-')[1])/100
            rankings[strategy]={'doc_ids':['NOISE',gold],'candidate_ids':['NOISE',gold],
                               'sparse_weight':sparse,'dense_weight':1-sparse,
                               'fusion_weight':.35 if strategy.endswith('blend035') else 0}
        results.append({'id':row['id'],'question_sha256':calibration.text_hash(row['question']),
                        'knowledge_base':row['knowledge_base'],'rankings':rankings})
    trial={'schema_version':1,'backend':'public-seed-bm25-bge-product-rrf-rerank','embedding_verified':True,
           'embedding_dimension':3,'embedding_calls':5,'top_k':2,'corpus':corpus,'results':results}
    return dataset,[copy.deepcopy(trial) for _ in range(3)]


class CalibrationContractTest(unittest.TestCase):
    def test_frozen_questions_no_document_leakage(self):
        data=json.loads((Path(__file__).resolve().parents[1]/'data/retrieval_calibration_questions.json').read_text(encoding='utf-8'))
        self.assertEqual(20,len(calibration.validate_questions(data)))

    def test_retriever_never_receives_gold_or_split(self):
        data,_=fixture()
        request=calibration.probe_request(data)
        self.assertNotIn('expected_doc_ids',json.dumps(request))
        self.assertNotIn('split',json.dumps(request))

    def test_metric_penalizes_missing_gold_and_empty_slots(self):
        scores=calibration.id_metrics(['a'],['a','b'],3)
        self.assertEqual(.5,scores['id_recall_at_k'])
        self.assertEqual(1/3,scores['id_precision_at_k'])
        self.assertEqual(.5,scores['id_ap_at_k'])
        self.assertLess(scores['id_ndcg_at_k'],1)

    def test_perfect_two_fact_ranking(self):
        self.assertEqual(1,calibration.id_metrics(['a','b'],['a','b'],2)['id_ndcg_at_k'])

    def test_no_gain_keeps_baseline_and_never_applies(self):
        data,trials=fixture(); report=calibration.evaluate(data,trials)
        self.assertEqual(calibration.BASELINE,report['selected_on_development'])
        self.assertFalse(report['parameters_applied']); self.assertEqual(0,report['business_writes'])
        self.assertFalse(report['ragas_scores_computed'])
        self.assertIn('NO_CLEAR_HOLDOUT_GAIN',report['reasons'])

    def test_holdout_cannot_select_different_strategy(self):
        data,trials=fixture()
        for trial in trials:
            trial['results'][0]['rankings']['adaptive-blend035']['doc_ids']=['DEV','NOISE']
            trial['results'][1]['rankings']['sparse-080-blend035']['doc_ids']=['HOLD','NOISE']
        report=calibration.evaluate(data,trials)
        self.assertEqual('adaptive-blend035',report['selected_on_development'])
        self.assertFalse(report['selection_uses_holdout'])

    def test_holdout_regression_not_hidden_by_development_gain(self):
        data,trials=fixture()
        for trial in trials:
            trial['results'][0]['rankings']['adaptive-blend035']['doc_ids']=['DEV','NOISE']
            trial['results'][1]['rankings']['adaptive-blend035']['doc_ids']=['NOISE']
        report=calibration.evaluate(data,trials)
        self.assertEqual(['hold'],report['holdout_regression_ids'])
        self.assertIn('HOLDOUT_REGRESSION',report['reasons'])

    def test_unstable_retrieval_is_not_passing(self):
        data,trials=fixture(); trials[1]['results'][1]['rankings'][calibration.BASELINE]['doc_ids']=['HOLD','NOISE']
        self.assertIn('UNSTABLE_RETRIEVAL',calibration.evaluate(data,trials)['reasons'])

    def test_partial_trial_rejected(self):
        data,trials=fixture(); trials[0]['results'].pop()
        with self.assertRaises(ValueError): calibration.evaluate(data,trials)

    def test_duplicate_trial_case_rejected(self):
        data,trials=fixture(); trials[0]['results'][1]=copy.deepcopy(trials[0]['results'][0])
        with self.assertRaises(ValueError): calibration.evaluate(data,trials)

    def test_reference_document_leakage_rejected(self):
        data,_=fixture(); data['cases'][1]['expected_doc_ids']=['DEV']
        with self.assertRaises(ValueError): calibration.validate_questions(data)

    def test_family_leakage_rejected(self):
        data,_=fixture(); data['cases'][1]['family']='dev-family'
        with self.assertRaises(ValueError): calibration.validate_questions(data)

    def test_duplicate_normalized_questions_rejected(self):
        data,_=fixture(); data['cases'][1]['question']='开 发问题'
        with self.assertRaises(ValueError): calibration.validate_questions(data)

    def test_unknown_gold_rejected(self):
        data,trials=fixture(); data['cases'][0]['expected_doc_ids']=['NOT-IN-CORPUS']
        with self.assertRaises(ValueError): calibration.evaluate(data,trials)

    def test_wrong_domain_rejected(self):
        data,trials=fixture(); trials[0]['results'][0]['knowledge_base']='order_knowledge'
        with self.assertRaises(ValueError): calibration.evaluate(data,trials)

    def test_question_drift_with_same_id_rejected(self):
        data,trials=fixture(); data['cases'][0]['question']='改变后的问题'
        with self.assertRaises(ValueError): calibration.evaluate(data,trials)

    def test_semantic_only_can_erase_sparse_weight_changes(self):
        data,trials=fixture()
        self.assertTrue(calibration.evaluate(data,trials)['semantic_only_rankings_identical_across_sparse_weights'])

    def test_invalid_actual_weights_rejected(self):
        for value in (math.nan, math.inf, True, -1,2):
            data,trials=fixture(); trials[0]['results'][0]['rankings'][calibration.BASELINE]['sparse_weight']=value
            with self.assertRaises(ValueError): calibration.evaluate(data,trials)

    def test_scores_not_real_embeddings_rejected(self):
        data,trials=fixture(); trials[0]['embedding_verified']=False
        with self.assertRaises(ValueError): calibration.evaluate(data,trials)

    def test_missing_strategy_rejected(self):
        data,trials=fixture(); trials[0]['results'][0]['rankings'].pop('sparse-080-blend035')
        with self.assertRaises(ValueError): calibration.evaluate(data,trials)

    def test_text_drift_rejected(self):
        data,trials=fixture(); trials[0]['corpus']['product_knowledge'][0]['textSha256']='b'*64
        with self.assertRaises(ValueError): calibration.evaluate(data,trials)

    def test_ranking_cannot_escape_candidate_pool(self):
        data,trials=fixture(); trials[0]['results'][0]['rankings'][calibration.BASELINE]['doc_ids']=['HOLD']
        with self.assertRaises(ValueError): calibration.evaluate(data,trials)

    def test_fewer_than_three_actual_trials_rejected(self):
        data,trials=fixture()
        with self.assertRaises(ValueError): calibration.evaluate(data,trials[:2])

    def test_evidence_write_refuses_overwrite(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'report.json'; calibration.write_new(path,{'first':True})
            with self.assertRaises(FileExistsError): calibration.write_new(path,{'overwrite':True})

    def test_runner_only_mounts_readonly_classpath(self):
        command=runner.container_command(Path('/dev/shm/retrieval-calibration-unit'),'sha256:'+'a'*64,
                                         'http://172.24.0.2:8091','b'*32)
        self.assertIn('--read-only',command); self.assertIn('--cap-drop',command)
        self.assertIn('--pids-limit',command); self.assertIn('--memory',command)
        self.assertEqual(1,command.count('--mount'))
        self.assertNotIn('/var/run/docker.sock',' '.join(command))
        self.assertNotIn('PASSWORD',' '.join(command))
        self.assertTrue(command[command.index('--mount')+1].endswith(',readonly'))

    def test_unpinned_runtime_rejected(self):
        with self.assertRaises(ValueError): runner.container_command(Path('/dev/shm/task'),'latest','http://172.0.0.2','b'*32)

    def test_full_image_hash_without_prefix_is_still_pinned(self):
        command=runner.container_command(Path('/dev/shm/task'),'a'*64,'http://172.0.0.2','b'*32)
        self.assertIn('a'*64,command)


if __name__=='__main__': unittest.main()
