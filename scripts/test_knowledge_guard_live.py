import unittest
from pathlib import Path
from unittest.mock import patch
import verify_knowledge_guard_live as live
import release_knowledge_guard_20261001 as rollout


class KnowledgeGuardLiveBoundaryTest(unittest.TestCase):
    def test_safe_uncertainty_not_generic_failure(self):
        self.assertIsNone(live.GENERIC_FAILURE.search('现有知识资料不足，暂时无法确定库存公式。'))

    def test_real_generic_failure_detected(self):
        for text in ('检测到 Agent 报告被阻塞，无法继续。',
                     '检测到基础设施故障，已暂停以避免持续重试。',
                     '循环守卫暂停', '处理失败：服务不可用'):
            self.assertIsNotNone(live.GENERIC_FAILURE.search(text))

    def test_service_scope_rejected_before_inspection(self):
        with patch.object(live, 'command') as command:
            with self.assertRaises(ValueError):
                live.verify('smart-router', '0' * 64, Path('unused'))
            command.assert_not_called()

    def test_clone_preserves_source_environment_and_cmd(self):
        source = {'Config': {'Env': ['MODEL_KEY=fixture', 'PRODUCT_RAG_AGENTIC_AUTOMATICRETRYENABLED=true'],
                             'Cmd': ['java', '--model=production'],
                             'Labels': {'smartassistant.release': 'previous', 'other': 'kept'}}}
        with patch.object(rollout, 'original_clone', return_value='candidate') as clone:
            self.assertEqual('candidate', rollout.clone('product', source, 'test-canary'))
        copied = clone.call_args.args[1]
        self.assertEqual(source['Config']['Env'], copied['Config']['Env'])
        self.assertEqual(source['Config']['Cmd'], copied['Config']['Cmd'])
        self.assertEqual({'other': 'kept'}, copied['Config']['Labels'])
        self.assertEqual('previous', source['Config']['Labels']['smartassistant.release'])


if __name__ == '__main__':
    unittest.main()
