"""Opt-in CNI DNS policy. Never substitute public DNS or static service IPs."""
import ipaddress

def internal_resolver(network):
    if network.get('name') != 'smart-network' or network.get('driver') != 'bridge' or network.get('dns_enabled') is not True:
        raise ValueError('Expected the DNS-enabled smart-network bridge')
    subnets=network.get('subnets',[])
    if len(subnets)!=1:
        raise ValueError('Ambiguous network: inspect manually')
    subnet=ipaddress.ip_network(subnets[0]['subnet'])
    gateway=ipaddress.ip_address(subnets[0]['gateway'])
    private_ranges=[ipaddress.ip_network(x) for x in ('10.0.0.0/8','172.16.0.0/12','192.168.0.0/16')]
    if gateway.version!=4 or not any(gateway in r for r in private_ranges) or gateway not in subnet or gateway in (subnet.network_address,subnet.broadcast_address):
        raise ValueError('Invalid private IPv4 gateway')
    return str(gateway)

def verify_resolvers(resolvers, gateway):
    if not resolvers or set(resolvers)!={gateway}:
        raise ValueError('Container resolver escaped the internal DNS policy')

def create_dns_arguments(network):
    return ['--dns', internal_resolver(network)]

if __name__=='__main__':
    import json
    import sys
    data=json.load(sys.stdin)
    if not isinstance(data,list) or len(data)!=1: raise ValueError('Expected one inspected network')
    print(internal_resolver(data[0]))
