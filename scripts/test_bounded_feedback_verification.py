"""Deterministic verification/collection contracts; no network or configured credentials."""
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
from collect_bounded_feedback_ragas import contexts, collect
from verify_bounded_feedback_live import read_trace
from release_bounded_feedback_20261001 import config_plan, environment
from run_bounded_feedback_server import promotion_gate


class VerificationTests(unittest.TestCase):
    def test_gate_requires_all_frozen_cases_and_controls(self):
        good={'stable':True,'novel_non_gold_ids':[],'baseline_recall':0.8,'candidate_recall':1,'status':'ACCEPTED'}
        rows=[dict(good,group='holdout',id=str(i)) for i in range(64)]+[dict(good,group='controls',id=str(i)) for i in range(2)]
        self.assertTrue(promotion_gate(rows))
        self.assertFalse(promotion_gate([]))
        self.assertFalse(promotion_gate(rows[:-1]))
        for key,value in [('stable',False),('novel_non_gold_ids',['unrelated']),('candidate_recall',0.7)]:
            changed=[dict(row) for row in rows]; changed[0][key]=value
            self.assertFalse(promotion_gate(changed))

    def test_auth_uses_service_configuration_and_checks_identity(self):
        info={'Config':{'Env':['REDIS_PASSWORD=synthetic-password']}}
        result=SimpleNamespace(returncode=0,stderr=b'',stdout=b'{"requestId":"qa-feedback-test"}')
        with patch('verify_bounded_feedback_live.subprocess.run',return_value=result) as run:
            self.assertEqual('qa-feedback-test',read_trace(info,'qa-feedback-test')['requestId'])
            self.assertEqual(b'synthetic-password\n',run.call_args.kwargs['input'])
            self.assertNotIn('synthetic-password',str(run.call_args.args))
            with self.assertRaisesRegex(ValueError,'correlation'):
                read_trace(info,'qa-feedback-other')

    def test_redis_errors_and_missing_trace_are_not_success(self):
        info={'Config':{'Env':['REDIS_PASSWORD=synthetic-password']}}
        for raw in (b'NOAUTH Authentication required.',b'WRONGPASS',b'ERR wrong type',b''):
            with self.subTest(raw=raw),patch('verify_bounded_feedback_live.subprocess.run',
                    return_value=SimpleNamespace(returncode=0,stderr=b'',stdout=raw)):
                with self.assertRaises(ValueError): read_trace(info,'qa-feedback-test')
        with self.assertRaises(ValueError): read_trace({'Config':{'Env':[]}},'qa-feedback-test')

    def test_formatted_chunks_deduplicate_and_preserve_body(self):
        text='1. 【QA仓库库存】（相关度: 95%）[CID:qa-1]\n库存=10\n2. 【QA仓库锁定】（相关度: 94%）[CID:qa-2]\n锁定=2'
        self.assertEqual(['qa-1','qa-2'],[i for i,_ in contexts(text)])
        self.assertIn('库存=10',contexts(text)[0][1])
        with self.assertRaises(ValueError): contexts(text.replace('qa-2','qa-1'))
        with self.assertRaises(ValueError): contexts('unparseable')

    def test_generation_never_receives_frozen_reference(self):
        controls=json.loads((Path(__file__).parents[1]/'data/bounded_feedback_positive_controls.json').read_text(encoding='utf-8'))
        q=controls['queries'][0]
        text='1. 【QA仓库库存】（相关度: 95%）[CID:qa-1]\n库存=10'
        ranking={'context':text,'context_doc_ids':['qa-1']}
        probe={'results':[{'id':q['id'],'rankings':{'hybrid-semantic':ranking,'hybrid-bounded-feedback':ranking}}]}
        seen=[]
        def generate(question,chunks):
            seen.append((question,chunks)); return '库存=10'
        dataset=collect(probe,controls,generate)
        self.assertEqual(2,len(seen))
        self.assertTrue(all(controls['reference'] not in str(call) for call in seen))
        self.assertEqual(controls['reference'],dataset['cases'][0]['reference'])

    def test_config_precheck_is_nonmutating_and_preserves_other_values(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory).resolve(); compose=root/'docker-compose.yml'; env=root/'.env'
            original="services:\n  product:\n    environment: {\n      PRODUCT_CHAT_MODEL: '${PRODUCT_CHAT_MODEL:-deepseek-v4-flash}',\n    }\n"
            compose.write_text(original); env.write_text('UNRELATED=preserve\nPRODUCT_RAG_AGENTIC_RETRY_TIMEOUT_MS=900\n')
            plan=config_plan(compose,[env])
            self.assertEqual(original,compose.read_text())
            self.assertIn(b'UNRELATED=preserve',plan[env][1])
            self.assertIn(b'PRODUCT_RAG_AGENTIC_AUTOMATICRETRYENABLED:',plan[compose][1])
            self.assertIn(b'PRODUCT_RAG_AGENTIC_RETRY_TIMEOUT_MS=1500',plan[env][1])
            compose.write_text('unknown YAML')
            with self.assertRaises(ValueError): config_plan(compose,[env])

    def test_release_clone_does_not_inherit_old_ownership(self):
        source={'Config':{'Env':['UNCHANGED=value'],'Labels':{'smartassistant.release':'old','other':'preserve'}}}
        clone=environment(source)
        self.assertNotIn('smartassistant.release',clone['Config']['Labels'])
        self.assertEqual('old',source['Config']['Labels']['smartassistant.release'])
        self.assertIn('UNCHANGED=value',clone['Config']['Env'])


if __name__=='__main__': unittest.main()
