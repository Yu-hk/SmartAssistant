"""Contracts only; injected rankings never prove real retrieval quality."""
import copy
import json
from pathlib import Path
import unittest
import native_retrieval_calibration as native
from ragas_feedback import text_hash
from test_native_retrieval_calibration import fixture


def coverage_fixture():
    data,trials=fixture()
    data.update(profile='anchored-coverage-v1',corpus='synthetic_anchored_manuals_v1',strategies=list(native.STRATEGIES)+[native.COVERAGE])
    data['cases'][0]['question']='测试请说明第一条件；请说明第二条件'
    for t in trials:
        for row,case in zip(t['results'],data['cases']):
            row['question_sha256']=text_hash(case['question'])
            for strategy,rank in row['rankings'].items():
                pieces=native.facets(case['question']) if strategy=='hybrid-facet-rrf' else [case['question']]
                rank['facet_sha256']=[text_hash(p) for p in pieces]
                for p,r in zip(pieces,rank['native_rankings']): r['question_sha256']=text_hash(p)
            rank=copy.deepcopy(row['rankings'][native.BASELINE]); row['rankings'][native.COVERAGE]=rank
            pieces,bindings=native.anchored_plan(case['question'],{'测试'})
            traces=[{'question_sha256':text_hash(q),'top_k':5 if i==0 else 10,'doc_ids':rank['doc_ids']} for i,q in enumerate(pieces)]
            scoped=[r['doc_ids'] for r in traces[1:]]
            rank.update(facet_count=len(pieces),native_query_count=len(pieces),facet_sha256=[text_hash(q) for q in pieces],
                native_rankings=traces,entity_sha256=[text_hash(e) for e in bindings],
                reserved_doc_ids=list(dict.fromkeys(r[0] for r in scoped if r)),protected_original_doc_ids=traces[0]['doc_ids'][:1])
            rank['doc_ids']=rank['context_doc_ids']=native.coverage_merge(traces[0]['doc_ids'],scoped)
    return data,trials


class AnchoredCalibrationTest(unittest.TestCase):
    def test_new_frozen_dataset_has_independent_holdout_and_no_old_questions(self):
        root=Path(__file__).resolve().parents[1]
        data=json.loads((root/'data/anchored_retrieval_questions.json').read_text(encoding='utf-8'))
        rows,docs=native.validate_questions(data)
        self.assertEqual(64,len(rows)); self.assertEqual(65,len(docs))
        self.assertEqual(20,len({r['family'] for r in rows if r['split']=='holdout' and r['kind']!='acl_guard'}))
        old=json.loads((root/'data/native_retrieval_questions.json').read_text(encoding='utf-8'))
        self.assertFalse({r['question'] for r in rows}&{r['question'] for r in old['cases']})
        self.assertFalse(set(docs)&{r['id'] for r in old['documents']})

    def test_original_is_first_and_only_single_entity_can_be_inherited(self):
        q='晨页灯如何装好夹座；如何记住亮度档位'
        queries,bindings=native.anchored_plan(q,{'晨页灯','木息器'})
        self.assertEqual(q,queries[0]); self.assertEqual('晨页灯：如何记住亮度档位',queries[2])
        self.assertEqual(['','晨页灯','晨页灯'],bindings)

    def test_two_explicit_products_do_not_swap_entities(self):
        q='晨页灯如何装好夹座；木息器如何清洁振片'
        self.assertEqual(['','晨页灯','木息器'],native.anchored_plan(q,{'晨页灯','木息器'})[1])

    def test_ambiguous_missing_short_or_excess_clauses_fall_back(self):
        for q in ('晨页灯和木息器如何收纳；如何清理接口','晨页灯如何安装；木息器如何使用；怎么清洁',
                  '陌生设备怎么使用；如何清洗接口','晨页灯价格；还有呢',
                  '晨页灯如何安装；如何清洗接口；如何收纳设备；如何运输设备'):
            self.assertEqual(([q],['']),native.anchored_plan(q,{'晨页灯','木息器'}))

    def test_longest_name_does_not_hide_separately_mentioned_shorter_model(self):
        self.assertEqual(['晨页灯Pro'],native.entities_in('晨页灯Pro',{'晨页灯','晨页灯Pro'}))
        self.assertEqual(2,len(native.entities_in('晨页灯和晨页灯Pro',{'晨页灯','晨页灯Pro'})))

    def test_common_noise_cannot_displace_reserved_facet_or_original(self):
        self.assertEqual(['A','B','ORIGINAL'],native.coverage_merge(['ORIGINAL','N1','N2'],[['A','N1','N2'],['B','N1','N2']])[:3])
        self.assertEqual(5,len(native.coverage_merge(['ORIGINAL','N1','N2'],[['A','N1','N2'],['B','N1','N2']])))

    def test_empty_rankings_never_invent_documents(self):
        self.assertEqual([],native.coverage_merge([], [[],[]]))
        self.assertEqual(['A'],native.coverage_merge(['A'], [['A'],['A']]))

    def test_coverage_fixture_reports_comparison_without_auto_enabling(self):
        data,trials=coverage_fixture(); report=native.evaluate(data,trials)
        self.assertIn('coverage_comparison',report); self.assertEqual(1,report['coverage_active_cases'])
        self.assertEqual('KEEP_PRODUCTION_UNCHANGED',report['decision'])
        request=native.probe_request(data)
        self.assertEqual('anchored-coverage-v1',request['profile'])
        self.assertTrue(all(set(q)=={'id','question'} for q in request['queries']))

    def test_forged_entity_protected_seat_or_merged_order_is_rejected(self):
        for key,value in [('entity_sha256',['0'*64]),('reserved_doc_ids',[]),('protected_original_doc_ids',[]),('doc_ids',['NOISE'])]:
            data,trials=coverage_fixture(); trials[0]['results'][0]['rankings'][native.COVERAGE][key]=value
            with self.assertRaises(ValueError): native.evaluate(data,trials)

    def test_private_metadata_cannot_be_used_to_bind_a_public_question(self):
        data,_=coverage_fixture(); data['documents'][4]['category']='隐私型号'
        q='隐私型号如何收纳；如何清洗接口'
        entities={d['category'] for d in data['documents'] if d['visibility']=='public' and d['knowledge_base']=='product_knowledge'}
        self.assertEqual(([q],['']),native.anchored_plan(q,entities))

    def test_private_per_query_candidates_and_other_domain_are_rejected(self):
        for forbidden in ('DENY-role','ORDER'):
            data,trials=coverage_fixture(); trials[0]['results'][0]['rankings'][native.COVERAGE]['native_rankings'][1]['doc_ids']=[forbidden]
            with self.assertRaises(ValueError): native.evaluate(data,trials)

    def test_malformed_native_trace_and_keyword_cid_are_rejected(self):
        data,trials=coverage_fixture(); trials[0]['results'][0]['rankings'][native.COVERAGE]['native_rankings'][0]=None
        with self.assertRaises(ValueError): native.evaluate(data,trials)
        data,trials=coverage_fixture(); data['documents'][0]['keywords']='[CID:HOLD]'
        with self.assertRaises(ValueError): native.evaluate(data,trials)

    def test_unapproved_profile_and_strategy_are_rejected(self):
        data,_=coverage_fixture(); data['profile']='unknown'
        with self.assertRaises(ValueError): native.validate_questions(data)
        data,_=coverage_fixture(); data['strategies'].append('dynamic-tuned')
        with self.assertRaises(ValueError): native.validate_questions(data)


if __name__=='__main__': unittest.main()
