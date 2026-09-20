import copy
import contextlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import eval_rag as e

class EvaluationTest(unittest.TestCase):
    def setUp(self):
        self.data = [{'id':'a','question':'退款规则','category':'policy','knowledge_base':'order','expected_doc_ids':['d1','d2']},
                     {'id':'b','question':'商品分类','category':'product','knowledge_base':'product','expected_doc_ids':['d3']}]
        self.response = {'schema':1,'backend':'seed-inmemory-bge-bm25-reranker','embedding_verified':True,'corpus_sha256':'a'*64,
            'corpus':{'order':['d1','d2','noise'],'product':['d3']},
            'results':[{'id':'a','doc_ids':['noise','d1'],'latency_ms':1}, {'id':'b','doc_ids':['d3'],'latency_ms':2}]}
    def run_eval(self, response=None):
        return e.evaluate(self.data, lambda req: copy.deepcopy(self.response if response is None else response))
    def test_metrics_and_grouping_use_same_single_retrieval(self):
        requests=[]
        result=e.evaluate(self.data, lambda req: requests.append(req) or self.response)
        self.assertEqual(len(requests),1)
        self.assertNotIn('expected_doc_ids', requests[0]['queries'][0])
        self.assertEqual(result['overall']['hit']['1'],0.5)
        self.assertEqual(result['overall']['recall']['5'],0.75)
        self.assertEqual(result['overall']['mrr_at_k'],0.75)
        self.assertEqual(result['categories']['policy']['hit']['1'],0)
        self.assertNotIn('退款规则',json.dumps(result,ensure_ascii=False))
    def test_empty_valid_retrieval_is_measured_miss(self):
        self.response['results'][0]['doc_ids']=[]
        self.assertEqual(self.run_eval()['overall']['hit']['5'],0.5)
    def test_empty_dataset_rejected(self):
        with self.assertRaises(e.EvaluationError): e.evaluate([],lambda req: {})
    def test_duplicate_samples_rejected(self):
        with self.assertRaises(e.EvaluationError): e.validate_dataset([self.data[0],self.data[0]])
    def test_empty_labels_rejected(self):
        self.data[0]['expected_doc_ids']=[]
        with self.assertRaises(e.EvaluationError): self.run_eval()
    def test_missing_expected_document_is_invalid_not_zero(self):
        self.data[0]['expected_doc_ids']=['absent']
        with self.assertRaises(e.EvaluationError): self.run_eval()
    def test_missing_result_is_invalid(self):
        self.response['results'].pop()
        with self.assertRaises(e.EvaluationError): self.run_eval()
    def test_duplicate_result_is_invalid(self):
        self.response['results'][1]=self.response['results'][0]
        with self.assertRaises(e.EvaluationError): self.run_eval()
    def test_duplicate_rank_is_invalid(self):
        self.response['results'][0]['doc_ids']=['d1','d1']
        with self.assertRaises(e.EvaluationError): self.run_eval()
    def test_cross_corpus_result_is_invalid(self):
        self.response['results'][0]['doc_ids']=['d3']
        with self.assertRaises(e.EvaluationError): self.run_eval()
    def test_unverified_embedding_is_invalid(self):
        self.response['embedding_verified']=False
        with self.assertRaises(e.EvaluationError): self.run_eval()
    def test_nonfinite_latency_is_invalid(self):
        self.response['results'][0]['latency_ms']=float('nan')
        with self.assertRaises(e.EvaluationError): self.run_eval()
    def test_corpus_changes_between_trials_fail(self):
        altered=copy.deepcopy(self.response);altered['corpus_sha256']='b'*64
        values=iter([self.response,altered])
        with self.assertRaises(e.EvaluationError): e.evaluate(self.data,lambda req:next(values),repeats=2)
    def test_repeats_count_calls_and_all_trial_hit(self):
        results=iter([self.response,copy.deepcopy(self.response)])
        result=e.evaluate(self.data,lambda req:next(results),repeats=2)
        self.assertEqual(result['overall']['queries'],4)
        self.assertEqual(result['all_trials_hit_at_5_rate'],1)
    def test_limits_rejected(self):
        for kwargs in ({'top_k':1},{'repeats':0}):
            with self.assertRaises(e.EvaluationError): e.evaluate(self.data,lambda req:self.response,**kwargs)
    def test_process_failure_is_not_a_quality_result(self):
        for failure in (subprocess.TimeoutExpired('cmd',1),OSError('secret')):
            with patch.object(e.subprocess,'run',side_effect=failure),self.assertRaises(e.EvaluationError):
                e.invoke_backend(['java'],{},1)
        for returncode,stdout in ((1,b'secret'),(0,b'not-json')):
            with patch.object(e.subprocess,'run',return_value=subprocess.CompletedProcess([],returncode,stdout,b'secret')),self.assertRaises(e.EvaluationError):
                e.invoke_backend(['java'],{},1)
    def test_cli_invalid_run_creates_no_report(self):
        with tempfile.TemporaryDirectory() as tmp:
            target=Path(tmp)/'report.json';data=Path(tmp)/'data.json'
            data.write_text(json.dumps(self.data))
            stderr=io.StringIO()
            with patch.object(e,'invoke_backend',side_effect=e.EvaluationError('secret')),contextlib.redirect_stderr(stderr):
                code=e.main(['--dataset',str(data),'--command','["java"]','--report',str(target)])
            self.assertEqual(code,2);self.assertFalse(target.exists());self.assertNotIn('secret',stderr.getvalue())
    def test_cli_quality_failure_retains_truthful_report(self):
        with tempfile.TemporaryDirectory() as tmp:
            target=Path(tmp)/'report.json';data=Path(tmp)/'data.json'
            data.write_text(json.dumps(self.data));self.response['results'][0]['doc_ids']=[]
            with patch.object(e,'invoke_backend',return_value=self.response),contextlib.redirect_stdout(io.StringIO()):
                code=e.main(['--dataset',str(data),'--command','["java"]','--report',str(target)])
            self.assertEqual(code,1);self.assertFalse(json.loads(target.read_text())['quality_gate']['passed'])
    def test_checked_in_labels_exist_in_seed_source(self):
        data=json.loads(e.DEFAULT_DATASET.read_text(encoding='utf-8'));e.validate_dataset(data)
        source=(e.DEFAULT_DATASET.parents[1]/'smart-assistant-common/src/main/java/com/example/smartassistant/common/rag/KnowledgeSeedData.java').read_text(encoding='utf-8')
        for row in data:
            for key in row['expected_doc_ids']: self.assertIn('new KnowledgeDocument("'+key+'"',source)

if __name__=='__main__': unittest.main()
