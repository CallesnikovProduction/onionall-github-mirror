"""Actual socket probes from the crawler's isolated network namespace."""
import argparse
import json
from pathlib import Path
import socket
import struct
import time
import threading
import queue


def connect(host, port):
    started = time.monotonic()
    try:
        with socket.create_connection((host, port), timeout=4):
            return {'connected': True, 'elapsed_seconds': time.monotonic() - started}
    except OSError as error:
        return {'connected': False, 'error_type': type(error).__name__, 'elapsed_seconds': time.monotonic() - started}


def receive(sock, size):
    result = b''
    while len(result) < size:
        part = sock.recv(size - len(result))
        if not part:
            raise OSError('SOCKS EOF')
        result += part
    return result


def remote_fetch(onion):
    # ATYP=3 sends the hostname directly to Tor; no getaddrinfo(onion) occurs.
    with socket.create_connection(('tor', 9050), timeout=5) as sock:
        sock.settimeout(45)
        sock.sendall(b'\x05\x01\x00')
        if receive(sock, 2) != b'\x05\x00':
            raise OSError('SOCKS authentication negotiation failed')
        host = onion.encode('ascii')
        sock.sendall(b'\x05\x01\x00\x03' + bytes([len(host)]) + host + struct.pack('!H', 80))
        response = receive(sock, 4)
        if response[:2] != b'\x05\x00':
            raise OSError('SOCKS connect reply ' + str(response[1]))
        length = {1: 4, 4: 16}.get(response[3])
        if response[3] == 3:
            length = receive(sock, 1)[0]
        if length is None:
            raise OSError('Invalid SOCKS reply address type')
        receive(sock, length + 2)
        sock.sendall(f'GET /health HTTP/1.1\r\nHost: {onion}\r\nConnection: close\r\n\r\n'.encode('ascii'))
        body = b''
        while len(body) <= 65536:
            part = sock.recv(4096)
            if not part:
                break
            body += part
        if len(body) > 65536 or not body.startswith(b'HTTP/1.0 200') or not body.endswith(b'ready'):
            raise OSError('Unexpected onion health response')
        return {'remote_hostname_sent': True, 'socks_atyp': 3, 'http_status': 200, 'health_body': 'ready'}


def run(fixture_ip, onion, outage=False):
    resolution=queue.Queue()
    def resolve():
        try:
            resolution.put({'resolved': bool(socket.getaddrinfo(onion, 80)), 'completed': True})
        except OSError as error:
            resolution.put({'resolved': False, 'completed': True, 'error_type': type(error).__name__})
    threading.Thread(target=resolve,daemon=True).start()
    try:
        onion_dns=resolution.get(timeout=5)
    except queue.Empty:
        onion_dns={'resolved': False, 'completed': False, 'error_type': 'BoundedResolutionTimeout'}
    checks = {'local_onion_dns': onion_dns, 'direct_internet': connect('1.1.1.1', 443),
              'direct_fixture': connect(fixture_ip, 8080),
              'tor_socks': connect('tor', 9050),
              'public_dns_tcp': connect('1.1.1.1', 53)}
    # UDP queries require a response to pass. A send-only result proves nothing.
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as dns:
        dns.settimeout(3)
        try:
            dns.connect(('1.1.1.1', 53))
            dns.send(b'\x00\x01\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00' + b'\x07example\x03com\x00\x00\x01\x00\x01')
            checks['public_dns_udp'] = {'response_received': bool(dns.recv(1024))}
        except OSError as error:
            checks['public_dns_udp'] = {'response_received': False, 'error_type': type(error).__name__}
    checks['outage'] = outage
    if not outage:
        try:
            checks['onion_health'] = remote_fetch(onion)
        except OSError as error:
            checks['onion_health'] = {'error_type': type(error).__name__, 'message': str(error)}
    expected = (checks['local_onion_dns']['completed'] and not checks['local_onion_dns']['resolved'] and not checks['direct_internet']['connected'] and not checks['direct_fixture']['connected']
                and not checks['public_dns_tcp']['connected'] and not checks['public_dns_udp']['response_received'])
    expected = expected and (not checks['tor_socks']['connected'] if outage else
                             checks['tor_socks']['connected'] and checks.get('onion_health', {}).get('http_status') == 200)
    checks['passed'] = bool(expected)
    return checks


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('fixture_ip')
    parser.add_argument('--outage', action='store_true')
    args = parser.parse_args()
    evidence = Path('/evidence')
    host = (evidence / 'onion-hostname.txt').read_text().strip()
    result = run(args.fixture_ip, host, args.outage)
    (evidence / ('network-outage.json' if args.outage else 'network.json')).write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=2))
    raise SystemExit(0 if result['passed'] else 1)
