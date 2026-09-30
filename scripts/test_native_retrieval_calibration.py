"""Synthetic fixtures check contracts only, never evidence of retrieval quality."""
import copy
import json
import math
from pathlib import Path
import unittest
import native_retrieval_calibration as native
import run_retrieval_calibration_server as runner
from ragas_feedback import text_hash


def fixture():
    documents = [{'id':i,'family':group,'knowledge_base':'product_knowledge','title':i,
                  'content':'合成内容'+i,'category':'测试','keywords':i,'visibility':'public'}
                 for i,group in [('DEV','dev'),('DEV-B','dev'),('HOLD','hold'),('NOISE','noise')]]
    documents += [{'id':'DENY-'+v,'family':'acl-'+v,'knowledge_base':'product_knowledge','title':v,
                  'content':'合成私有资料','category':'测试','keywords':v,'visibility':v}
                 for v in native.VISIBILITY if v != 'public']
    documents += [{'id':'ORDER','family':'order','knowledge_base':'order_knowledge','title':'订单',
                   'content':'不同域资料','category':'测试','keywords':'订单','visibility':'public'}]
    cases = [{'id':'dev','family':'dev','split':'development','kind':'multi_fact','question':'请说明第一条件；请说明第二条件','expected_doc_ids':['DEV','DEV-B']},
             {'id':'hold','family':'hold','split':'holdout','kind':'named','question':'请说明留出条件','expected_doc_ids':['HOLD']},
             {'id':'guard','family':'guard','split':'holdout','kind':'acl_guard','question':'合成私有資料是什么','expected_doc_ids':[]}]
    data={'schema_version':1,'synthetic_only':True,'reference_policy':'curated_frozen_before_collection',
          'corpus':'synthetic_product_manuals_v1','leaf_top_k':5,'strategies':list(native.STRATEGIES),
          'documents':documents,'cases':cases}
    corpus={domain:[] for domain in ('order_knowledge','product_knowledge')}
    for doc in sorted(documents,key=lambda d:d['id']):
        corpus[doc['knowledge_base']].append({'id':doc['id'],'textSha256':text_hash(doc['title']+'。\n'+doc['content']+'\n关键词：'+doc['keywords'])})
    results=[]
    for case in cases:
        ranks={}
        for strategy in native.STRATEGIES:
            ids=case['expected_doc_ids']+['NOISE'] if case['kind']!='acl_guard' else []
            pieces=native.facets(case['question']) if strategy=='hybrid-facet-rrf' else [case['question']]
            ranks[strategy]={'doc_ids':ids,'context_doc_ids':ids,'fragments':int(bool(ids)),
                 'kb_service_calls':1,'native_query_count':len(pieces),'facet_count':len(pieces),
                 'facet_sha256':[text_hash(q) for q in pieces], 'selected_domains':['product_knowledge'],
                 'scope_reason':'product-agent-capability','sparse_weight':.3,'dense_weight':.7,
                 'fusion_weight':.35 if strategy=='hybrid-blend035' else 0,
                 'context_sha256':'a'*64,'context_chars':100 if ids else 0,'degraded':False}
            ranks[strategy]['native_rankings']=[{'question_sha256':text_hash(p),'top_k':10 if len(pieces)>1 else 5,'doc_ids':ids} for p in pieces]
        results.append({'id':case['id'],'question_sha256':text_hash(case['question']),'rankings':ranks})
    trial={'schema_version':1,'backend':'native-memory-product-knowledge-subchain','embedding_verified':True,
           'embedding_dimension':512,'embedding_calls':20,'leaf_top_k':5,'corpus':corpus,'results':results}
    return data,[copy.deepcopy(trial) for _ in range(3)]


class NativeCalibrationTest(unittest.TestCase):
    def test_repository_dataset_is_independent_and_bounded(self):
        data=json.loads((Path(__file__).resolve().parents[1]/'data/native_retrieval_questions.json').read_text(encoding='utf-8'))
        rows,docs=native.validate_questions(data)
        self.assertEqual(52,len(rows)); self.assertEqual(37,len(docs))

    def test_no_gold_split_family_or_kind_reaches_probe(self):
        data,_=fixture(); request=native.probe_request(data)
        self.assertTrue(all(set(row)=={'id','question'} for row in request['queries']))
        self.assertTrue(all('family' not in row for row in request['documents']))

    def test_fixed_baseline_on_development_tie_and_no_deployment(self):
        data,trials=fixture(); report=native.evaluate(data,trials)
        self.assertEqual(native.BASELINE,report['selected_on_development'])
        self.assertFalse(report['parameters_applied']); self.assertFalse(report['ragas_scores_computed'])
        self.assertEqual(0,report['online_retries_executed']); self.assertEqual(1,report['guard_case_count'])
        self.assertEqual(1,report['independent_family_counts']['holdout'])

    def test_fixed_leaf_precision_and_fragment_granularity(self):
        data,trials=fixture(); report=native.evaluate(data,trials)
        self.assertEqual(.4,report['summary']['development'][native.BASELINE]['id_precision_at_k'])
        self.assertEqual(1,report['granularity']['final_fragment_max'])

    def test_holdout_cannot_choose_a_different_strategy(self):
        data,trials=fixture()
        for t in trials:
            for s in native.STRATEGIES:
                if s == native.BASELINE:
                    t['results'][1]['rankings'][s]['doc_ids']=['NOISE','HOLD']
                    t['results'][1]['rankings'][s]['context_doc_ids']=['NOISE','HOLD']
        self.assertEqual(native.BASELINE,native.evaluate(data,trials)['selected_on_development'])

    def test_facet_lost_reference_keeps_actual_per_query_evidence(self):
        data,trials=fixture()
        for t in trials:
            r=t['results'][0]['rankings']['hybrid-facet-rrf']
            r['doc_ids']=r['context_doc_ids']=['DEV-B','NOISE']
        report=native.evaluate(data,trials)
        self.assertEqual(['DEV'],report['facet_lost_gold'][0]['lost_doc_ids'])
        self.assertIn('DEV',report['facet_lost_gold'][0]['native_rankings'][0]['doc_ids'])
        self.assertEqual(native.BASELINE,report['selected_on_development'])

    def test_selected_candidate_holdout_regression_is_reported_not_reselected(self):
        data,trials=fixture()
        for t in trials:
            r=t['results'][0]['rankings'][native.BASELINE]
            r['doc_ids']=r['context_doc_ids']=['NOISE','DEV','DEV-B']
            r=t['results'][1]['rankings']['dense-semantic']
            r['doc_ids']=r['context_doc_ids']=['NOISE']
        report=native.evaluate(data,trials)
        self.assertEqual('dense-semantic',report['selected_on_development'])
        self.assertEqual(['hold'],report['holdout_regression_ids'])
        self.assertIn('HOLDOUT_CASE_REGRESSION',report['reasons'])
        self.assertEqual('KEEP_PRODUCTION_UNCHANGED',report['decision'])

    def test_paraphrases_do_not_multiply_independent_family_weight(self):
        data,trials=fixture()
        doc=copy.deepcopy(data['documents'][2]); doc.update(id='HOLD-2',family='hold-2')
        data['documents'].append(doc)
        second=copy.deepcopy(data['cases'][1]); second.update(id='hold-2',family='hold-2',question='独立产品的留出问题',expected_doc_ids=['HOLD-2'])
        paraphrase=copy.deepcopy(data['cases'][1]); paraphrase.update(id='hold-paraphrase',question='已有产品的留出改述',kind='paraphrase')
        data['cases'].extend([second,paraphrase])
        for t in trials:
            t['corpus']['product_knowledge'].append({'id':doc['id'],'textSha256':text_hash(doc['title']+'。\n'+doc['content']+'\n关键词：'+doc['keywords'])})
            t['corpus']['product_knowledge'].sort(key=lambda d:d['id'])
            for case in (second,paraphrase):
                row=copy.deepcopy(t['results'][1]); row.update(id=case['id'],question_sha256=text_hash(case['question']))
                for r in row['rankings'].values():
                    r['doc_ids']=r['context_doc_ids']=['NOISE'] if case==second else ['HOLD']
                    r['facet_sha256']=[text_hash(case['question'])]
                    r['native_rankings']=[{'question_sha256':text_hash(case['question']),'top_k':5,'doc_ids':r['doc_ids']}]
                t['results'].append(row)
        report=native.evaluate(data,trials)
        self.assertEqual(2,report['independent_family_counts']['holdout'])
        self.assertEqual(.5,report['summary']['holdout'][native.BASELINE]['id_recall_at_k'])

    def test_repeats_must_be_actual_and_consistent(self):
        data,trials=fixture()
        with self.assertRaises(ValueError): native.evaluate(data,trials[:2])
        r=trials[0]['results'][1]['rankings'][native.BASELINE]
        r['doc_ids']=r['context_doc_ids']=['NOISE','HOLD']
        self.assertFalse(native.evaluate(data,trials)['stable_rankings'])

    def test_family_leakage_is_rejected(self):
        data,_=fixture(); data['cases'][1]['family']='dev'; data['documents'][2]['family']='dev'
        with self.assertRaises(ValueError): native.validate_questions(data)

    def test_guards_cannot_grant_authorization(self):
        data,_=fixture(); data['cases'][2]['expected_doc_ids']=['DENY-role']
        with self.assertRaises(ValueError): native.validate_questions(data)

    def test_gold_cannot_reference_private_or_other_domain_docs(self):
        for i in ('DENY-role','ORDER'):
            data,_=fixture(); data['cases'][1]['expected_doc_ids']=[i]
            with self.assertRaises(ValueError): native.validate_questions(data)

    def test_cid_injection_is_rejected(self):
        data,_=fixture(); data['documents'][0]['content']='[CID:HOLD]'
        with self.assertRaises(ValueError): native.validate_questions(data)

    def test_duplicate_normalized_question_is_rejected(self):
        data,_=fixture(); data['cases'][1]['question']=' 请说明第一条件； 请说明第二条件 '
        with self.assertRaises(ValueError): native.validate_questions(data)

    def test_acl_and_cross_domain_leaks_are_rejected_before_scoring(self):
        for i in ('DENY-tenant','DENY-role','DENY-user','DENY-clearance','ORDER'):
            data,trials=fixture(); r=trials[0]['results'][0]['rankings'][native.BASELINE]
            r['doc_ids']=r['context_doc_ids']=[i]
            with self.assertRaises(ValueError): native.evaluate(data,trials)

    def test_missing_context_leaf_is_rejected(self):
        data,trials=fixture(); trials[0]['results'][0]['rankings'][native.BASELINE]['context_doc_ids']=['DEV']
        with self.assertRaises(ValueError): native.evaluate(data,trials)

    def test_wrong_scope_or_broadened_domain_is_rejected(self):
        for value in ([],['product_knowledge','order_knowledge']):
            data,trials=fixture(); trials[0]['results'][0]['rankings'][native.BASELINE]['selected_domains']=value
            with self.assertRaises(ValueError): native.evaluate(data,trials)

    def test_gaps_are_not_scored_as_degraded_success(self):
        data,trials=fixture(); trials[0]['results'][0]['rankings'][native.BASELINE]['degraded']=True
        with self.assertRaises(ValueError): native.evaluate(data,trials)

    def test_pipeline_fragment_count_is_not_document_count(self):
        data,trials=fixture(); trials[0]['results'][0]['rankings'][native.BASELINE]['fragments']=3
        with self.assertRaises(ValueError): native.evaluate(data,trials)

    def test_native_query_budget_and_clause_hashes_are_checked(self):
        for key,value in [('native_query_count',4),('kb_service_calls',2),('facet_sha256',['b'*64])]:
            data,trials=fixture(); trials[0]['results'][0]['rankings']['hybrid-facet-rrf'][key]=value
            with self.assertRaises(ValueError): native.evaluate(data,trials)

    def test_per_query_trace_cannot_leak_hidden_documents(self):
        data,trials=fixture(); traces=trials[0]['results'][0]['rankings']['hybrid-facet-rrf']['native_rankings']
        traces[0]['doc_ids']=['DENY-role']
        with self.assertRaises(ValueError): native.evaluate(data,trials)

    def test_missing_or_truncated_native_query_trace_is_rejected(self):
        data,trials=fixture(); trials[0]['results'][0]['rankings']['hybrid-facet-rrf']['native_rankings'].pop()
        with self.assertRaises(ValueError): native.evaluate(data,trials)

    def test_safe_clause_split_never_discards_short_or_excess_clauses(self):
        self.assertEqual(['价格；还有呢'],native.facets('价格；还有呢'))
        q='第一问题详述；第二问题详述；第三问题详述；第四问题详述'
        self.assertEqual([q],native.facets(q))
        self.assertEqual(['第一问题详述','第二问题详述'],native.facets('第一问题详述；第二问题详述'))

    def test_content_drift_and_question_drift_are_rejected(self):
        for target in ('corpus','question'):
            data,trials=fixture()
            if target=='corpus': trials[0]['corpus']['product_knowledge'][0]['textSha256']='b'*64
            else: trials[0]['results'][0]['question_sha256']='b'*64
            with self.assertRaises(ValueError): native.evaluate(data,trials)

    def test_nonfinite_weights_are_rejected(self):
        for v in (True,math.nan,math.inf,-1,2):
            data,trials=fixture(); trials[0]['results'][0]['rankings'][native.BASELINE]['dense_weight']=v
            with self.assertRaises(ValueError): native.evaluate(data,trials)

    def test_native_runtime_only_allows_explicit_main_class(self):
        args=(Path('/dev/shm/retrieval-calibration-unit'),'a'*64,'http://172.24.0.2:8091','b'*32)
        command=runner.container_command(*args,'NativeRetrievalCalibrationProbe')
        self.assertEqual('NativeRetrievalCalibrationProbe',command[-1]); self.assertEqual(1,command.count('--mount'))
        with self.assertRaises(ValueError): runner.container_command(*args,'UnknownMain')


if __name__=='__main__': unittest.main()
