"""Actual socket probes from the crawler's isolated network namespace."""
import argparse
import json
from pathlib import Path
import socket
import struct
import time
import threading
import queue
from datetime import datetime, timezone


def connect(host, port):
    started = time.monotonic()
    try:
        with socket.create_connection((host, port), timeout=4):
            return {'connected': True, 'elapsed_seconds': time.monotonic() - started}
    except OSError as error:
        return {'connected': False, 'error_type': type(error).__name__, 'elapsed_seconds': time.monotonic() - started}


def remaining(deadline, maximum):
    if deadline is None:
        return maximum
    budget = deadline - time.monotonic()
    if budget <= 0:
        raise TimeoutError('Controlled onion readiness deadline exceeded')
    return min(maximum, budget)


def receive(sock, size, deadline=None):
    result = b''
    while len(result) < size:
        sock.settimeout(remaining(deadline, 45))
        part = sock.recv(size - len(result))
        if not part:
            raise OSError('SOCKS EOF')
        result += part
    return result


def remote_fetch(onion, deadline=None, observation=None, proxy_host='tor'):
    # Diagnostics may compare another isolated Tor gateway; acceptance uses 'tor'.
    observation = observation if observation is not None else {}
    started = time.monotonic()
    def phase(name):
        observation['phase'] = name
        observation.setdefault('phase_started_seconds', {})[name] = time.monotonic() - started
    # ATYP=3 sends the hostname directly to Tor; no getaddrinfo(onion) occurs.
    phase('proxy_tcp_connect')
    with socket.create_connection((proxy_host, 9050), timeout=remaining(deadline, 5)) as sock:
        sock.settimeout(remaining(deadline, 45))
        phase('socks_greeting')
        sock.sendall(b'\x05\x01\x00')
        if receive(sock, 2, deadline) != b'\x05\x00':
            raise OSError('SOCKS authentication negotiation failed')
        host = onion.encode('ascii')
        phase('socks_connect_reply')
        sock.sendall(b'\x05\x01\x00\x03' + bytes([len(host)]) + host + struct.pack('!H', 80))
        response = receive(sock, 4, deadline)
        observation['socks_reply_code'] = response[1]
        if response[:2] != b'\x05\x00':
            raise OSError('SOCKS connect reply ' + str(response[1]))
        length = {1: 4, 4: 16}.get(response[3])
        if response[3] == 3:
            length = receive(sock, 1, deadline)[0]
        if length is None:
            raise OSError('Invalid SOCKS reply address type')
        receive(sock, length + 2, deadline)
        phase('http_response')
        sock.sendall(f'GET /health HTTP/1.1\r\nHost: {onion}\r\nConnection: close\r\n\r\n'.encode('ascii'))
        body = b''
        while len(body) <= 65536:
            sock.settimeout(remaining(deadline, 45))
            part = sock.recv(4096)
            if not part:
                break
            body += part
        if len(body) > 65536 or not body.startswith(b'HTTP/1.0 200') or not body.endswith(b'ready'):
            raise OSError('Unexpected onion health response')
        phase('complete')
        return {'remote_hostname_sent': True, 'socks_atyp': 3, 'http_status': 200, 'health_body': 'ready'}


def onion_ready(onion, seconds=90):
    """Tor bootstrap and controlled onion availability are separate observations."""
    if not 1 <= seconds <= 90:
        raise ValueError('Controlled onion readiness deadline must be 1..90 seconds')
    started = time.monotonic()
    deadline = started + seconds
    started_at_utc = datetime.now(timezone.utc).isoformat()
    attempts = []
    ready = False
    while time.monotonic() < deadline:
        attempt = {'attempt': len(attempts) + 1, 'started_at_utc': datetime.now(timezone.utc).isoformat()}
        try:
            health = remote_fetch(onion, deadline=deadline, observation=attempt)
            ready = health.get('http_status') == 200 and health.get('health_body') == 'ready'
            attempt['health'] = health
        except OSError as error:
            attempt['error_type'] = type(error).__name__
        attempt['elapsed_seconds'] = time.monotonic() - started
        attempts.append(attempt)
        if ready:
            break
        if time.monotonic() < deadline:
            time.sleep(min(2, deadline-time.monotonic()))
    return {'passed': ready, 'ready': ready, 'attempts': attempts,
            'started_at_utc': started_at_utc,
            'deadline_seconds': seconds, 'elapsed_seconds': time.monotonic()-started,
            'policy': 'Actual controlled onion /health via SOCKS ATYP=3; not bootstrap inference'}


def single_health(onion, seconds=30):
    """One bounded observation for the recovery controller; never retries."""
    if not 1 <= seconds <= 30:
        raise ValueError('Single health deadline must be 1..30 seconds')
    started = time.monotonic()
    result = {'passed': False, 'started_at_utc': datetime.now(timezone.utc).isoformat(),
              'deadline_seconds': seconds}
    try:
        result['health'] = remote_fetch(onion, deadline=started + seconds, observation=result)
        result['passed'] = True
    except OSError as error:
        result['error_type'] = type(error).__name__
    result['elapsed_seconds'] = time.monotonic() - started
    return result


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
        readiness = onion_ready(onion)
        checks['onion_readiness'] = readiness
        if readiness['ready']:
            checks['onion_health'] = readiness['attempts'][-1]['health']
        else:
            checks['onion_health'] = {'error_type': 'OnionReadinessDeadlineExceeded'}
    expected = (checks['local_onion_dns']['completed'] and not checks['local_onion_dns']['resolved'] and not checks['direct_internet']['connected'] and not checks['direct_fixture']['connected']
                and not checks['public_dns_tcp']['connected'] and not checks['public_dns_udp']['response_received'])
    expected = expected and (not checks['tor_socks']['connected'] if outage else
                             checks['tor_socks']['connected'] and checks.get('onion_health', {}).get('http_status') == 200)
    checks['passed'] = bool(expected)
    return checks


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('fixture_ip', nargs='?')
    parser.add_argument('--onion-ready', action='store_true')
    parser.add_argument('--single-health', action='store_true')
    parser.add_argument('--health-seconds', type=int, default=30)
    parser.add_argument('--readiness-output', default='onion-readiness.json')
    parser.add_argument('--outage', action='store_true')
    args = parser.parse_args()
    evidence = Path('/evidence')
    host = (evidence / 'onion-hostname.txt').read_text().strip()
    if args.onion_ready or args.single_health:
        if Path(args.readiness_output).name != args.readiness_output or not args.readiness_output.endswith('.json'):
            parser.error('Readiness output must be a JSON filename')
        result = single_health(host, args.health_seconds) if args.single_health else onion_ready(host)
        destination = args.readiness_output
    else:
        if not args.fixture_ip:
            parser.error('fixture_ip is required for network isolation checks')
        result = run(args.fixture_ip, host, args.outage)
        destination = 'network-outage.json' if args.outage else 'network.json'
    (evidence / destination).write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=2))
    raise SystemExit(0 if result['passed'] else 1)
