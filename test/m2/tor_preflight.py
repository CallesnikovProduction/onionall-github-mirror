"""Real bridge/Tor/SOCKS connectivity gate; secret contents never enter evidence."""
import argparse
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import time
import uuid

IMAGE = 'onionall-brawler-tor:0.4.9.11-obfs4-deb12u1'
ROOT = Path(__file__).resolve().parents[2]


def progress(text):
    values = re.findall(r'Bootstrapped (\d+)%', text)
    return int(values[-1]) if values else None


def bridge_file():
    path = Path(os.environ.get('M2_BRIDGES_FILE', ROOT / '.secrets/m2/bridges.conf')).expanduser().resolve()
    if not path.is_file():
        raise ValueError('Private bridge file missing; configure M2_BRIDGES_FILE (see docs/m2-tor.md)')
    if path.stat().st_size > 65536:
        raise ValueError('Private bridge file exceeds the bounded configuration size')
    if os.name != 'nt' and path.stat().st_mode & 0o077:
        raise ValueError('Private bridge file permissions must be 0600 or stricter')
    return path


# The request socket is created only to local SOCKS; target hostname is sent as
# SOCKS ATYP=3 and HTTPS uses normal certificate/hostname validation.
HTTP_CHECK = r'''
import socket, ssl, struct, http.client, json
from pathlib import Path
host='check.torproject.org'
with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as control:
 control.settimeout(3);control.connect('/tmp/tor-preflight/control')
 stream=control.makefile('rwb',buffering=0)
 cookie=Path('/tmp/tor-preflight/cookie').read_bytes().hex().encode('ascii')
 stream.write(b'AUTHENTICATE '+cookie+b'\r\n')
 if stream.readline(4096)!=b'250 OK\r\n':raise OSError('Tor control authentication failed')
 stream.write(b'GETINFO status/bootstrap-phase\r\n')
 status=stream.readline(4096)
 if b'PROGRESS=100 ' not in status or stream.readline(4096)!=b'250 OK\r\n':raise OSError('Current Tor bootstrap incomplete')
def read(s,n):
 b=b''
 while len(b)<n:
  p=s.recv(n-len(b))
  if not p: raise OSError('SOCKS EOF')
  b+=p
 return b
with socket.create_connection(('127.0.0.1',9050),timeout=10) as s:
 s.settimeout(30);s.sendall(b'\x05\x01\x00')
 if read(s,2)!=b'\x05\x00':raise OSError('SOCKS negotiation failed')
 target=host.encode('ascii')
 s.sendall(b'\x05\x01\x00\x03'+bytes([len(target)])+target+struct.pack('!H',443))
 reply=read(s,4)
 if reply[:2]!=b'\x05\x00':raise OSError('SOCKS connection failed')
 size={1:4,4:16}.get(reply[3])
 if reply[3]==3:size=read(s,1)[0]
 if size is None:raise OSError('SOCKS reply invalid')
 read(s,size+2)
 with ssl.create_default_context().wrap_socket(s,server_hostname=host) as tls:
  tls.sendall(b'GET /api/ip HTTP/1.1\r\nHost: check.torproject.org\r\nConnection: close\r\n\r\n')
  response=http.client.HTTPResponse(tls);response.begin();body=response.read(65537)
  if response.status!=200 or len(body)>65536:raise OSError('HTTP check failed')
  is_tor=json.loads(body).get('IsTor') is True
running=False
for entry in Path('/proc').iterdir():
 if entry.name.isdigit():
  try:running |= (entry/'comm').read_text().strip() in {'obfs4proxy','lyrebird'}
  except OSError:pass
print(json.dumps({'socks_usable':True,'remote_hostname_sent':True,'socks_atyp':3,
 'http_status':200,'is_tor':is_tor,'transport_running':running,'current_bootstrap_complete':True}))
if not is_tor or not running:raise SystemExit(1)
'''


def run(path, seconds=180):
    if not 15 <= seconds <= 360:
        raise ValueError('Preflight deadline must be 15..360 seconds')
    path = Path(path)
    path.mkdir(parents=True, exist_ok=True)
    name = 'onionall-tor-preflight-' + uuid.uuid4().hex[:12]
    log = path / 'tor.log'
    result = {'ready': False, 'observed_progress_percent': None, 'image': IMAGE,
              'evidence': ['tor.log'], 'blocker': 'Tor bridge bootstrap not completed'}
    process = None
    created = False
    try:
        secret = bridge_file()
        uid = os.environ.get('M2_RUNTIME_USER', f'{os.getuid()}:{os.getgid()}' if os.name != 'nt' else '1000:1000')
        command = ['docker', 'run', '--rm', '--name', name, '--user', uid,
                   '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
                   '--read-only', '--tmpfs', '/tmp', '--mount',
                   f'type=bind,source={secret},target=/run/secrets/tor-bridges,readonly', IMAGE,
                   '--SocksPort', '127.0.0.1:9050', '--ControlPort', '0',
                   '--ControlSocket', '/tmp/tor-preflight/control', '--CookieAuthFile', '/tmp/tor-preflight/cookie',
                   '--DataDirectory', '/tmp/tor-preflight']
        with log.open('w') as output:
            process = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT)
            created = True
            deadline = time.monotonic() + seconds
            while time.monotonic() < deadline:
                result['observed_progress_percent'] = progress(log.read_text())
                if result['observed_progress_percent'] == 100:
                    checked = subprocess.run(['docker', 'exec', name, 'python3', '-c', HTTP_CHECK],
                                             capture_output=True, timeout=min(45,max(1,deadline-time.monotonic())))
                    if checked.returncode == 0:
                        check = json.loads(checked.stdout)
                        result.update(check)
                        result.update(ready=all(check.get(k) is True for k in
                                     ('socks_usable','remote_hostname_sent','is_tor','transport_running','current_bootstrap_complete')), blocker=None)
                    else:
                        result['blocker'] = 'Bootstrap completed but SOCKS/Tor HTTP/transport check failed'
                    break
                if process.poll() is not None:
                    result['process_exit_code'] = process.returncode
                    result['blocker'] = 'Tor container exited; inspect sanitized tor.log and image/configuration'
                    break
                time.sleep(.25)
    except (OSError, ValueError, subprocess.TimeoutExpired) as error:
        result['blocker'] = str(error) if isinstance(error, ValueError) else 'Tor preflight subsystem failed: ' + type(error).__name__
    finally:
        try:
            cleanup = subprocess.run(['docker', 'rm', '-f', name], capture_output=True, timeout=15) if created else None
            result['container_removed'] = cleanup is None or cleanup.returncode == 0 or b'No such container' in cleanup.stderr
        except (OSError, subprocess.TimeoutExpired):
            result['container_removed'] = False
        if process is not None:
            try: process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait(timeout=5)
        if not result['container_removed']:
            result.update(ready=False, blocker='Preflight cleanup not verified: ' + name)
        (path / 'result.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('evidence', type=Path)
    parser.add_argument('--seconds', type=int, default=180)
    args = parser.parse_args()
    def interrupted(signum, frame):
        raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM, interrupted)
    try:
        result = run(args.evidence, args.seconds)
    except KeyboardInterrupt:
        raise SystemExit(130)
    print(json.dumps(result, indent=2))
    raise SystemExit(0 if result['ready'] else 1)
