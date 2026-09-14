import unittest
from chat_telemetry import final_usage, parse_events


class FinalTokenUsageTest(unittest.TestCase):
    def test_node_snapshot_is_not_final_total(self):
        events = [dict(type='token_usage', totalTokens=100, tokenUsageComplete=False),
                  dict(type='response'), dict(type='token_usage', totalTokens=150, tokenUsageComplete=True),
                  dict(type='done')]
        self.assertEqual(final_usage(events), dict(totalTokens=150, tokenUsageComplete=True))

    def test_complete_counts_do_not_override_explicit_partial_status(self):
        usage = final_usage([dict(type='response'), dict(type='token_usage', promptTokens=10,
                completionTokens=20, totalTokens=30, tokenUsageComplete=False), dict(type='done')])
        self.assertFalse(usage['tokenUsageComplete'])

    def test_missing_final_or_unfinished_stream_does_not_reuse_node_usage(self):
        for events in ([dict(type='token_usage', totalTokens=10), dict(type='response'), dict(type='done')],
                       [dict(type='response'), dict(type='token_usage', totalTokens=10)]):
            with self.assertRaises(ValueError):
                final_usage(events)

    def test_nested_sse_payloads_and_missing_measurements(self):
        raw = ': heartbeat\r\n\r\ndata: {"type":"response","data":{"content":"完成"}}\r\n\r\n'
        raw += 'data: {"type":"token_usage","data":{"tokenUsageComplete":false}}\n\n'
        raw += 'data: {"type":"done"}\n\n'
        self.assertEqual(final_usage(parse_events(raw)), dict(tokenUsageComplete=False))

    def test_true_zero_is_supported(self):
        self.assertEqual(final_usage([dict(type='response'), dict(type='token_usage', totalTokens=0,
            tokenUsageComplete=True), dict(type='done')]), dict(totalTokens=0, tokenUsageComplete=True))


if __name__ == '__main__':
    unittest.main()
