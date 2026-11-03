"""Bounded frontier lease/process-crash/recreation diagnostic; never M2 acceptance.

Uses only the existing frontier/probe on the internal Compose network, no Tor or
HTTP service. Run after Maven package with JAVA_HOME and M2_STORM_IMAGE selected.
The unique RocksDB volume and all evidence are retained, including on failure.
"""
import base64
import hashlib
import json
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


def main():
    runner = Runner()
    path = runner.path
    result = dict(passed=False, m2_verified=False, transport='internal gRPC only; no Tor/HTTP')
    before = host_state()
    runner.save('host-before.json', before)

    def check(condition, message):
        if not condition:
            raise RuntimeError(message)

    def dc(args, name, **kwargs):
        print(name, flush=True)
        return runner.dc(args, name + '.log', timeout=60, **kwargs)

    def lease(mode, name):
        dc(['run', '--rm', '--no-deps', '--entrypoint', 'java', 'probe', '-cp',
            '/apache-storm/lib/*:/apache-storm/lib-common/*:/evidence/crawler-m2.jar',
            'dev.onionall.brawler.m2.FrontierLeaseProbe', mode, name + '.json'], name)
        return json.loads((path / (name + '.json')).read_text())

    def snapshot(name):
        dc(['run', '--rm', '--no-deps', 'probe', 'snapshot', name + '.json'], name)
        return json.loads((path / (name + '.json')).read_text())['entries']

    def recreate(name, crash=False):
        old = dc(['ps', '-q', 'frontier'], name + '-old', capture=True).stdout.strip()
        dc(['kill', '--signal', 'SIGKILL', 'frontier'] if crash else
            ['stop', '--timeout', '10', 'frontier'], name + '-stop')
        dc(['rm', '-f', 'frontier'], name + '-remove')
        dc(['up', '-d', '--no-deps', '--no-build', '--pull', 'never',
            '--wait', '--wait-timeout', '40', 'frontier'], name + '-start')
        new = dc(['ps', '-q', 'frontier'], name + '-new', capture=True).stdout.strip()
        check(bool(old) and bool(new) and old != new, 'Frontier container not recreated')

    def interrupted(signum, frame):
        raise KeyboardInterrupt()

    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    try:
        runner.validate_toolchain()
        config = json.loads(dc(['--profile', 'tools', 'config', '--format', 'json'], 'config', capture=True).stdout)
        check(config['networks']['crawler'].get('internal') is True, 'Expected internal-only network')
        for service in ('frontier', 'probe'):
            check(set(config['services'][service]['networks']) == {'crawler'}
                and not config['services'][service].get('ports'), 'Unexpected network exposure')
        artifact = ROOT / 'target/onionall-brawler-0.1.0-SNAPSHOT.jar'
        shutil.copy2(artifact, path / 'crawler-m2.jar')
        runner.save('artifact.json', dict(sha256=hashlib.sha256(artifact.read_bytes()).hexdigest(),
            frontier_image=config['services']['frontier']['image']))
        runner.command([runner.tool('javac'), '--release', '25', '-cp', artifact, '-d', path / 'classes',
            ROOT / 'test/m2/FrontierProbe.java', ROOT / 'test/m2/FrontierLeaseProbe.java'], 'compile.log', timeout=30)
        runner.command([runner.tool('jar'), '--update', '--file', path / 'crawler-m2.jar',
            '-C', path / 'classes', '.'], 'package.log', timeout=30)
        key = bytes([1]) * 32
        payload = key + hashlib.sha3_256(b'.onion checksum' + key + b'\3').digest()[:2] + b'\3'
        host = base64.b32encode(payload).decode().lower() + '.onion'
        (path / 'onion-hostname.txt').write_text(host)
        expected = ['http://' + host + '/pending']
        runner.started = True
        dc(['up', '-d', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '40', 'frontier'], 'startup')
        dc(['run', '--rm', '--no-deps', 'probe', 'ready'], 'ready')
        dc(['run', '--rm', '--no-deps', 'probe', 'seed', 'pending'], 'seed')
        initial = snapshot('before')
        check(len(initial) == 1, 'Expected exactly one persisted URL')
        started = time.monotonic()
        check(lease('lease-long', 'leased')['urls'] == expected, 'Pending URL not leased')
        check(lease('lease-long', 'held')['urls'] == [], 'Unexpired lease delivered twice')
        check(snapshot('while-held') == initial, 'Leasing mutated/lost persisted work')
        # Consumer JVM already exited without a Known update. Crash the server
        # while its 120s transient hold is live; recovery must rebuild from RocksDB.
        recreate('crash', crash=True)
        check(snapshot('after-crash') == initial, 'Unacknowledged pending row lost on crash/recreation')
        check(lease('lease-short', 'redelivered')['urls'] == expected, 'Lost unacknowledged work after restart')
        check(time.monotonic() - started < 110, 'Experiment too slow to distinguish hold expiry from restart')
        check(lease('lease-long', 'short-held')['urls'] == [], 'Active short hold ignored')
        time.sleep(11)  # Fixed local lease window, not network/recovery retries.
        check(lease('lease-long', 'expired')['urls'] == expected, 'Expired lease did not become eligible')
        completed = lease('complete', 'complete')
        final = snapshot('completed')
        check(len(final) == 1 and final[0]['refetch'] == completed['refetch']
            and final[0]['metadata'].get('lifecycle.result') == ['committed'], 'Acknowledged result missing')
        check(lease('lease-long', 'future')['urls'] == [], 'Future refetch dequeued too early')
        recreate('result-reopen')
        check(snapshot('restored') == final, 'Acknowledged result changed after recreation')
        check(lease('lease-long', 'restored-future')['urls'] == [], 'Restored future result dequeued too early')
        result.update(passed=True, pending_survives_unacknowledged_consumer=True,
            pending_survives_server_sigkill=True, lease_expires=True,
            acknowledged_result_survives_recreation=True, frontier_records=1)
    except (OSError, ValueError, RuntimeError, KeyboardInterrupt, subprocess.SubprocessError) as error:
        result['blocker'] = type(error).__name__ + ': ' + str(error)
    finally:
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        try:
            if runner.started:
                try:
                    dc(['logs', '--no-color'], 'services', allow_failure=True)
                finally:
                    dc(['down', '--timeout', '10'], 'cleanup', allow_failure=True)
                result['containers_removed'] = not runner.command(['docker', 'ps', '-aq', '--filter',
                    'label=com.docker.compose.project=' + runner.project], capture=True, timeout=15).stdout.strip()
                result['networks_removed'] = not runner.command(['docker', 'network', 'ls', '-q', '--filter',
                    'label=com.docker.compose.project=' + runner.project], capture=True, timeout=15).stdout.strip()
                volume = runner.command(['docker', 'volume', 'inspect', runner.project + '_frontier-data'],
                    capture=True, allow_failure=True, timeout=15)
                result['volume_retained'] = volume.returncode == 0 and len(json.loads(volume.stdout or '[]')) == 1
                check(all(result[k] for k in ('containers_removed', 'networks_removed', 'volume_retained')),
                    'Cleanup/retention check failed')
            after = host_state()
            runner.save('host-after.json', after)
            result['host_unchanged'] = before == after
            result['passed'] &= result['host_unchanged']
        except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
            result.update(passed=False, cleanup_blocker=str(error))
        runner.save('frontier-lifecycle.json', result)
        print(path, flush=True)
        print(json.dumps(result, indent=2), flush=True)
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
