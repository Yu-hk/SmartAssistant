import json
import unittest
import threading
import urllib.error
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch
import verify_embedding_service_live as probe


class EmbeddingLiveContractTest(unittest.TestCase):
    def test_redirect_cannot_escape_internal_probe(self):
        destinations = []
        class Target(BaseHTTPRequestHandler):
            def do_GET(self):
                destinations.append(self.path)
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b'{}')
            def log_message(self, *args):
                pass
        target = ThreadingHTTPServer(('127.0.0.1', 0), Target)
        class Redirect(BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(302)
                self.send_header('Location', 'http://127.0.0.1:' + str(target.server_port) + '/out-of-scope')
                self.end_headers()
            def log_message(self, *args):
                pass
        source = ThreadingHTTPServer(('127.0.0.1', 0), Redirect)
        threads = [threading.Thread(target=server.serve_forever, daemon=True) for server in (target, source)]
        for thread in threads:
            thread.start()
        try:
            with self.assertRaises(urllib.error.HTTPError) as error:
                probe.request('http://127.0.0.1:' + str(source.server_port), '/api/embedding/health')
            self.assertEqual(302, error.exception.code)
            self.assertEqual([], destinations)
        finally:
            for server in (source, target):
                server.shutdown()
                server.server_close()
            for thread in threads:
                thread.join(timeout=5)

    def test_link_local_metadata_address_is_rejected(self):
        output = b'true {"smart-network":{"IPAddress":"169.254.169.254"}}'
        with patch.object(probe.subprocess, 'check_output', return_value=output), self.assertRaises(ValueError):
            probe.address('smart-embedding-service')

    def test_rejects_other_services_before_inspection(self):
        with patch.object(probe.subprocess, 'check_output') as inspect:
            with self.assertRaises(ValueError):
                probe.address('smart-product')
            inspect.assert_not_called()

    def test_internal_running_service_only(self):
        for running, host, networks in (('false', '10.89.1.32', ['smart-network']),
                                      ('true', '127.0.0.1', ['smart-network']),
                                      ('true', '8.8.8.8', ['smart-network']),
                                      ('true', '10.89.1.32', ['other-network'])):
            raw = (running + ' ' + json.dumps({name: {'IPAddress': host} for name in networks})).encode()
            with self.subTest(host=host, running=running, networks=networks), patch.object(
                    probe.subprocess, 'check_output', return_value=raw):
                with self.assertRaises(ValueError):
                    probe.address('smart-embedding-service')

    def test_valid_internal_address(self):
        with patch.object(probe.subprocess, 'check_output', return_value=(
                b'true {"smart-network":{"IPAddress":"10.89.1.32"}}')):
            self.assertEqual('http://10.89.1.32:8091', probe.address('smart-embedding-service'))

    def test_vectors_are_real_finite_and_correct_length(self):
        for value in (None, [], [0, 0], [1, float('nan')], [1, float('inf')], [True, 1], [1, '0.1']):
            with self.subTest(value=value), self.assertRaises(ValueError):
                probe.vector(value, 2)
        probe.vector([0.1, -0.2], 2)

    def replies(self, base, route, payload=None):
        if route == '/actuator/health':
            return {'status': 'UP'}
        if route == '/api/embedding/health':
            return {'status': 'UP', 'available': True, 'dimensions': 2}
        if route == '/api/embedding/dimensions':
            return {'dimensions': 2}
        if route == '/api/embedding':
            return ({'embedding': [0.1, 0.2], 'dimensions': 2} if payload.get('text', '').strip()
                    else {'error': 'text 不能为空'})
        return ({'count': 2, 'dimensions': 2, 'embeddings': [[0.1, 0.2], [0.3, 0.4]]}
                if payload['texts'] else {'error': 'texts 不能为空'})

    def test_complete_success_and_no_business_scope(self):
        with patch.object(probe, 'address', return_value='http://10.89.1.32:8091'), patch.object(
                probe, 'request', side_effect=self.replies) as requests:
            result = probe.verify('smart-embedding-service')
        self.assertEqual('passed', result['status'])
        self.assertEqual(0, result['business_writes'])
        self.assertEqual(0, result['restarts'])
        self.assertEqual(8, requests.call_count)

    def test_unavailable_model_cannot_pass_actuator_only_health(self):
        def unavailable(base, route, payload=None):
            return {'status': 'UP'} if route == '/actuator/health' else {'status': 'DOWN', 'available': False}
        with patch.object(probe, 'address', return_value='internal'), patch.object(
                probe, 'request', side_effect=unavailable), self.assertRaises(ValueError):
            probe.verify('smart-embedding-service')

    def test_batch_reordering_is_rejected(self):
        def reversed_batch(base, route, payload=None):
            result = self.replies(base, route, payload)
            if route == '/api/embedding/batch' and payload['texts']:
                result['embeddings'].reverse()
            return result
        with patch.object(probe, 'address', return_value='internal'), patch.object(
                probe, 'request', side_effect=reversed_batch), self.assertRaises(ValueError):
            probe.verify('smart-embedding-service')


if __name__ == '__main__':
    unittest.main()
