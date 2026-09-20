"""Real JVM local-loopback contracts; no Internet-dependent assertions."""
import json
import os
import subprocess
import unittest

class DnsProbeTest(unittest.TestCase):
    def probe(self,*args):
        return subprocess.run([os.environ.get('DNS_PROBE_JAVA','java'),'-cp',os.environ['DNS_PROBE_CLASSES'],
            'ContainerDnsProbe',*args],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=15)
    def test_loopback_success(self):
        p=self.probe('2','localhost','127.0.0.1')
        self.assertEqual(p.returncode,0)
        r=json.loads(p.stdout);self.assertEqual(r['attempts'],2);self.assertEqual(r['failures'],0)
    def test_wrong_address_is_failure(self):
        p=self.probe('2','localhost','192.0.2.1')
        self.assertEqual(p.returncode,1);self.assertEqual(json.loads(p.stdout)['failures'],2)
    def test_unexpected_positive_is_failure(self):
        p=self.probe('2','localhost','MISSING')
        self.assertEqual(p.returncode,1);self.assertEqual(json.loads(p.stdout)['failures'],2)
    def test_limit_is_enforced(self):
        p=self.probe('201','localhost','ANY')
        self.assertNotEqual(p.returncode,0);self.assertFalse(p.stdout)
    def test_invalid_host_is_rejected_before_network(self):
        p=self.probe('1','bad/host','ANY')
        self.assertNotEqual(p.returncode,0);self.assertFalse(p.stdout)

if __name__=='__main__': unittest.main()
