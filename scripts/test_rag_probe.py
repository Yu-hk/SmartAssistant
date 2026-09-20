"""JVM/HTTP protocol contracts with fake vectors, not retrieval-quality evidence.
Requires RAG_PROBE_JAVA_ARGS containing a Java @argfile path, prepared by the build.
"""
import json
import os
import subprocess
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

class ProbeContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.args = os.environ['RAG_PROBE_JAVA_ARGS']  # Missing fixture is a failure, never a skip.
        cls.java = os.environ.get('RAG_PROBE_JAVA', 'java')
    def probe(self, mode):
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*args): pass
            def do_POST(self):
                if self.headers.get('Transfer-Encoding') == 'chunked':
                    while True:
                        size = int(self.rfile.readline().split(b';')[0],16)
                        if not size:
                            self.rfile.readline();break
                        self.rfile.read(size);self.rfile.read(2)
                else:
                    self.rfile.read(int(self.headers.get('Content-Length','0')))
                self.send_response(503 if mode=='http-error' else 200)
                self.send_header('Content-Type','application/json');self.end_headers()
                value={'embedding': [1.0,0.5,0.0]} if mode=='valid' else {'embedding': [0.0,0.0,0.0]}
                if mode=='empty': value={'error':'fixture failure'}
                self.wfile.write(json.dumps(value).encode())
        server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
        thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        try:
            env=dict(os.environ,RAG_EVAL_EMBEDDING_URL='http://127.0.0.1:'+str(server.server_port))
            request={'schema':1,'top_k':5,'queries':[{'id':'q','question':'退款','knowledge_base':'order_knowledge'}]}
            result = subprocess.run([self.java,'-Xms64m','-Xmx640m','-XX:ActiveProcessorCount=1','@'+self.args],input=json.dumps(request).encode(),
                                    stdout=subprocess.PIPE,stderr=subprocess.PIPE,env=env,timeout=45)
            self.assertIn(b'RAG_PROBE_STARTED',result.stderr,'JVM did not load the probe')
            return result
        finally:
            server.shutdown();server.server_close();thread.join()
    def test_real_jvm_and_http_contract(self):
        p=self.probe('valid')
        self.assertEqual(p.returncode,0,p.stderr.decode(errors='replace')[-1000:])
        r=json.loads(p.stdout)
        self.assertTrue(r['embedding_verified']);self.assertEqual(r['embedding_dimensions'],3)
        self.assertEqual(len(r['corpus']['order_knowledge']),10)
        self.assertEqual(len(r['corpus']['product_knowledge']),7)
        self.assertEqual(r['results'][0]['id'],'q')
        self.assertEqual(len(r['results'][0]['doc_ids']),5)
    def test_http_failure_cannot_be_certified(self):
        p=self.probe('http-error');self.assertNotEqual(p.returncode,0);self.assertFalse(p.stdout.strip())
    def test_empty_embedding_cannot_downgrade_to_fake_success(self):
        p=self.probe('empty');self.assertNotEqual(p.returncode,0);self.assertFalse(p.stdout.strip())
    def test_zero_embedding_cannot_be_certified(self):
        p=self.probe('zero');self.assertNotEqual(p.returncode,0);self.assertFalse(p.stdout.strip())

if __name__=='__main__': unittest.main()
