import unittest
from container_dns_policy import internal_resolver, verify_resolvers, create_dns_arguments

class DnsPolicyTest(unittest.TestCase):
    def setUp(self):
        self.network={'name':'smart-network','driver':'bridge','dns_enabled':True,
                      'subnets':[{'subnet':'10.89.1.0/24','gateway':'10.89.1.1'}]}
    def test_explicit_internal_resolver(self):
        self.assertEqual(create_dns_arguments(self.network),['--dns','10.89.1.1'])
    def test_no_hardcoded_network_gateway(self):
        self.network['subnets']=[{'subnet':'172.22.0.0/16','gateway':'172.22.0.1'}]
        self.assertEqual(internal_resolver(self.network),'172.22.0.1')
    def test_dns_disabled_rejected(self):
        self.network['dns_enabled']=False
        with self.assertRaises(ValueError): internal_resolver(self.network)
    def test_wrong_network_rejected(self):
        self.network['name']='unrelated-network'
        with self.assertRaises(ValueError): internal_resolver(self.network)
    def test_multiple_subnets_rejected(self):
        self.network['subnets']*=2
        with self.assertRaises(ValueError): internal_resolver(self.network)
    def test_unusable_gateway_rejected(self):
        for value in ['8.8.8.8','10.89.2.1','10.89.1.0','10.89.1.255','::1']:
            with self.subTest(value=value):
                self.network['subnets'][0]['gateway']=value
                with self.assertRaises(ValueError): internal_resolver(self.network)
    def test_public_or_missing_resolver_rejected(self):
        for values in [[],['100.100.2.136'],['10.89.1.1','100.100.2.136']]:
            with self.assertRaises(ValueError): verify_resolvers(values,'10.89.1.1')
    def test_only_internal_dns_accepted(self):
        verify_resolvers(['10.89.1.1'],'10.89.1.1')
    def test_loopback_network_rejected(self):
        self.network['subnets']=[{'subnet':'127.0.0.0/8','gateway':'127.0.0.1'}]
        with self.assertRaises(ValueError): internal_resolver(self.network)

if __name__=='__main__': unittest.main()
