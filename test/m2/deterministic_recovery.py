"""Actual Storm/TorProtocol/Frontier recovery on internal SOCKS fixture, never M2 PASS.

Run after Maven package, JDK25 and explicit M2_STORM_IMAGE=...cleanup2. No Tor,
bridges, external network or database migration. Retains evidence and DB volume.
"""
import base64
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from tor_acceptance_runner import Runner
from restart_recovery import host_state


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def cleanup_project(runner, worker, output, result):
    """Attempt every owned teardown even if worker stop or log collection fails."""
    errors = []
    def attempt(action):
        try:
            return action()
        except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
            errors.append(type(error).__name__ + ': ' + str(error))
            return None
    if worker is not None and worker.poll() is None:
        attempt(lambda: runner.command(['docker', 'stop', '--time', '5', runner.project + '-worker'],
            'worker-cleanup.log', allow_failure=True, timeout=20))
        attempt(lambda: worker.wait(timeout=30))
    if output is not None:
        attempt(output.close)
    if runner.started:
        attempt(lambda: runner.dc(['logs', '--no-color'], 'services.log', allow_failure=True, timeout=40))
        attempt(lambda: runner.dc(['down', '--timeout', '10'], 'cleanup.log', allow_failure=True, timeout=60))
        for key, command in (
                ('containers_removed', ['docker', 'ps', '-aq', '--filter', 'label=com.docker.compose.project=' + runner.project]),
                ('networks_removed', ['docker', 'network', 'ls', '-q', '--filter', 'label=com.docker.compose.project=' + runner.project])):
            response = attempt(lambda command=command: runner.command(command, capture=True, timeout=15))
            result[key] = response is not None and not response.stdout.strip()
        response = attempt(lambda: runner.command(['docker', 'volume', 'inspect', runner.project + '_frontier-data'], capture=True, timeout=15))
        result['volume_retained'] = attempt(lambda: response is not None and len(json.loads(response.stdout)) == 1) is True
        if not all(result.get(k) is True for k in ('containers_removed', 'networks_removed', 'volume_retained')):
            errors.append('Cleanup/retention check failed')
    if errors:
        result.update(passed=False, cleanup_blockers=errors)


def main():
    r = Runner()
    p = r.path
    result = dict(passed=False, m2_verified=False, transport='internal controlled SOCKS, production TorProtocol')
    baseline = host_state()
    r.save('host-before.json', baseline)
    worker = None
    output = None
    def dc(args, name, timeout=60, **kw):
        print(name, flush=True)
        return r.dc(args, name + '.log', timeout=timeout, **kw)
    def snapshot(name):
        dc(['run', '--rm', '--no-deps', 'probe', 'snapshot', name + '.json'], name)
        return json.loads((p / (name + '.json')).read_text())
    def events():
        file = p / 'socks-events.jsonl'
        return [json.loads(line) for line in file.read_text().split('\n')[:-1]] if file.exists() else []
    def mode(value):
        temporary = p / 'socks-mode.next'
        temporary.write_text(value)
        temporary.replace(p / 'socks-mode')
    def terminal(operation):
        dc(['run', '--rm', '--no-deps', '--entrypoint', 'java', 'probe', '-cp',
            '/apache-storm/lib/*:/apache-storm/lib-common/*:/evidence/crawler-m2.jar',
            'dev.onionall.brawler.m2.TerminalMetadataProbe', operation], 'terminal-' + operation)
    def interrupted(signum, frame):
        raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    try:
        r.validate_toolchain()
        key = bytes([1]) * 32
        payload = key + hashlib.sha3_256(b'.onion checksum' + key + b'\3').digest()[:2] + b'\3'
        host = base64.b32encode(payload).decode().lower() + '.onion'
        (p / 'onion-hostname.txt').write_text(host)
        r.command([sys.executable, '-B', 'test/m2/prepare.py', p], 'prepare.log', timeout=20)
        config = (p / 'crawler-conf.yaml').read_text().replace('http.timeout: 20000', 'http.timeout: 2000')
        config = config.replace('tor.socks.host: "tor"', 'tor.socks.host: "socks-fixture"')
        config = config.replace('fetchInterval.fetch.error: 120', 'fetchInterval.fetch.error: 1')
        (p / 'crawler-conf.yaml').write_text(config)
        artifact = ROOT / 'target/onionall-brawler-0.1.0-SNAPSHOT.jar'
        shutil.copy2(artifact, p / 'crawler-m2.jar')
        r.save('artifact.json', dict(sha256=hashlib.sha256(artifact.read_bytes()).hexdigest()))
        cp = str(artifact) + os.pathsep + (ROOT / 'target/cli-classpath.txt').read_text().strip()
        r.command([r.tool('javac'), '--release', '25', '-cp', cp, '-d', p / 'classes',
            ROOT / 'test/m2/FrontierProbe.java', ROOT / 'test/m2/TerminalMetadataProbe.java'], 'compile.log', timeout=45)
        r.command([r.tool('jar'), '--update', '--file', p / 'crawler-m2.jar', '-C', p / 'classes', '.',
            '-C', p / 'resources', '.'], 'package.log', timeout=20)
        overlay = {'services': {'socks-fixture': {
            'image': 'python:3.12-slim', 'user': r.environment['M2_RUNTIME_USER'],
            'entrypoint': ['python3', '-B', '/fixture.py'],
            'volumes': [str(ROOT / 'test/m2/recovery_socks_fixture.py') + ':/fixture.py:ro', str(p) + ':/evidence'],
            'networks': ['crawler'], 'read_only': True, 'cap_drop': ['ALL'],
            'security_opt': ['no-new-privileges:true'], 'tmpfs': ['/tmp']}}}
        override = p / 'deterministic-compose.json'
        override.write_text(json.dumps(overlay))
        r.compose += ['-f', str(override)]
        config_json = json.loads(dc(['--profile', 'tools', 'config', '--format', 'json'], 'config', capture=True).stdout)
        r.save('compose.json', config_json)
        for service in ('socks-fixture', 'crawler', 'frontier'):
            require(set(config_json['services'][service]['networks']) == {'crawler'}, 'Internal-only services required')
        require(config_json['networks']['crawler']['internal'], 'Internal network required')
        mode('armed')
        r.started = True
        dc(['up', '-d', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '40', 'frontier', 'socks-fixture'], 'startup')
        dc(['run', '--rm', '--no-deps', 'probe', 'ready'], 'ready')
        dc(['run', '--rm', '--no-deps', 'probe', 'seed', 'pending'], 'seed-before-outage')
        before = snapshot('before-outage')
        require(len(before['entries']) == 1, 'Pending must preexist outage')
        output = (p / 'crawl-failed.log').open('w')
        args = r.compose + ['run', '--rm', '--no-deps', '--name', r.project + '-worker', '-e', 'M2_CRAWL_TTL=15', 'crawler']
        worker = subprocess.Popen(args, cwd=ROOT, env=r.environment, stdout=output, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 60
        while not any(row['event'] == 'connect_received' for row in events()):
            require(worker.poll() is None and time.monotonic() < deadline, 'Worker never reached CONNECT')
            time.sleep(.05)
        # Worker reached proxy only after taking the preexisting Frontier URL.
        mode('stall')
        r.save('outage-trigger.json', dict(work_preexisted=True, connect_observed=True, mode='stall'))
        require(worker.wait(timeout=90) == 0, 'Failed worker lifecycle did not exit cleanly')
        output.close(); output = None
        failed = snapshot('failed')
        row = failed['entries'][0]
        require(len(failed['entries']) == 1 and row['metadata'].get('fetch.error.count') == ['1']
            and row['metadata'].get('fetch.exception') and row['refetch'] > 0, 'One persisted failure/retry required')
        require(not any(e['event'] == 'http' for e in events()), 'HTTP must not precede CONNECT recovery')
        require(any(e['event'] == 'client_closed_during_connect' for e in events()), 'Call did not cancel stalled SOCKS')
        mode('ready')
        require(row['refetch'] <= time.time() + 90, 'Unexpected test retry horizon')
        while time.time() < row['refetch']:
            time.sleep(min(1, row['refetch'] - time.time()))
        dc(['run', '--rm', '--no-deps', '-e', 'M2_CRAWL_TTL=15', 'crawler'], 'crawl-recovered', timeout=120)
        recovered = snapshot('recovered')
        final = recovered['entries'][0]
        require(len(recovered['entries']) == 1 and final['url'] == row['url']
            and not final['metadata'].get('fetch.error.count')
            and final['refetch'] > recovered['captured_at_epoch'] + 3600, 'Retry did not produce durable success')
        require('M2_PENDING_MARKER' in (p / 'crawl-recovered.log').read_text(), 'Actual parser marker missing')
        http = [e['path'] for e in events() if e['event'] == 'http']
        require(http == ['/robots.txt', '/pending'], 'Unexpected HTTP count/scope/order')
        terminal('write')
        dc(['stop', '--timeout', '10', 'frontier'], 'terminal-frontier-stop')
        dc(['rm', '-f', 'frontier'], 'terminal-frontier-remove')
        dc(['up', '-d', '--no-deps', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '40', 'frontier'], 'terminal-frontier-recreate')
        terminal('verify')
        result.update(passed=True, failed_fetch_persisted=True, scheduled_retry_recovered=True,
            work_preexisted_outage=True, terminal_metadata_characterized=True)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError, KeyboardInterrupt) as error:
        result['blocker'] = type(error).__name__ + ': ' + str(error)
    finally:
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        cleanup_project(r, worker, output, result)
        try:
            after = host_state()
            r.save('host-after.json', after)
            result['host_unchanged'] = baseline == after
            result['passed'] &= result['host_unchanged']
        except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
            result.update(passed=False, host_check_blocker=str(error))
        r.save('deterministic-recovery.json', result)
        print(p, flush=True)
        print(json.dumps(result, indent=2), flush=True)
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
