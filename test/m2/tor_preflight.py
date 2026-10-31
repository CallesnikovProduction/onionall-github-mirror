"""Bounded real Tor bootstrap probe. No SOCKS ports, mounts, keys or full M2 stack."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import uuid

IMAGE = 'onionall-brawler-tor:0.4.9.11-deb12u1'


def progress(text):
    values = re.findall(r'Bootstrapped (\d+)%', text)
    return int(values[-1]) if values else None


def run(path, seconds=90):
    if not 15 <= seconds <= 180:
        raise ValueError('Preflight deadline must be 15..180 seconds')
    path.mkdir(parents=True, exist_ok=True)
    name = 'onionall-tor-preflight-' + uuid.uuid4().hex[:12]
    log = path / 'tor.log'
    result = {'ready': False, 'observed_progress_percent': None, 'image': IMAGE,
              'evidence': ['tor.log'], 'blocker': 'Tor bootstrap not completed'}
    command = ['docker', 'run', '--rm', '--name', name, '--cap-drop', 'ALL',
               '--security-opt', 'no-new-privileges', '--read-only', '--tmpfs', '/tmp',
               IMAGE, '--SocksPort', '0', '--ControlPort', '0', '--ControlSocket', '0',
               '--DataDirectory', '/tmp/tor-preflight', '--ClientOnly', '1', '--Log', 'notice stdout']
    process = None
    try:
        with log.open('w') as output:
            process = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + seconds
            while time.monotonic() < deadline:
                result['observed_progress_percent'] = progress(log.read_text())
                if result['observed_progress_percent'] == 100:
                    result.update(ready=True, blocker=None)
                    break
                if process.poll() is not None:
                    result['process_exit_code'] = process.returncode
                    break
                time.sleep(.25)
    except OSError as error:
        result['blocker'] = 'Tor preflight could not start: ' + type(error).__name__
    finally:
        try:
            cleanup = subprocess.run(['docker', 'rm', '-f', name], capture_output=True, timeout=15)
            result['container_removed'] = cleanup.returncode == 0 or b'No such container' in cleanup.stderr
        except (OSError, subprocess.TimeoutExpired):
            result['container_removed'] = False
        if process is not None:
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        if not result['container_removed']:
            result.update(ready=False, blocker='Preflight cleanup not verified: ' + name)
        (path / 'result.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('evidence', type=Path)
    parser.add_argument('--seconds', type=int, default=90)
    args = parser.parse_args()
    result = run(args.evidence, args.seconds)
    print(json.dumps(result, indent=2))
    raise SystemExit(0 if result['ready'] else 1)
