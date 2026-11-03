"""Bounded owned-service restart + gateway replacement + pending frontier test.

No full gate, random onion, host settings changes, volume deletion or retry loop.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from runners.tor_acceptance_runner import Runner
from recovery.recover_tor_gateway import recover


def host_state():
    routes = json.loads(subprocess.check_output(['ip', '-j', 'route', 'show', 'table', 'all']))
    return dict(vpn_default_routes=[r for r in routes if r.get('dev') == 'tun0' or r.get('dst') == 'default'],
        rules=json.loads(subprocess.check_output(['ip', '-j', 'rule', 'show'])),
        resolver_sha256=hashlib.sha256(Path('/etc/resolv.conf').read_bytes()).hexdigest())


def restore_transport(project, path, code, observation, recover_gateway=None):
    """Attribute transport recovery separately from pending-work verification.

    Unassisted reachability does not exercise the assisted branch. A successful
    exit alone is insufficient: require the same owned ATYP3 HTTP health proof.
    """
    if type(code) is not int or not isinstance(observation, dict):
        raise ValueError('Invalid controlled health evidence')
    health = observation.get('health', {})
    if not isinstance(health, dict):
        raise ValueError('Invalid controlled health object')
    if code == 0 and observation.get('passed') is True and (
            health.get('http_status') == 200 and health.get('health_body') == 'ready'
            and health.get('socks_atyp') == 3 and health.get('remote_hostname_sent') is True):
        return dict(transport_recovery='PASS', recovery_mode='unassisted',
                    assisted_recovery='NOT_EXERCISED', recovery_exercised=False)
    if code == 0 or observation.get('passed') is not False:
        raise ValueError('Contradictory or incomplete controlled health evidence')
    from recovery.recover_tor_gateway import connect_failure
    if not connect_failure(observation):
        raise ValueError('No qualifying SOCKS CONNECT failure; no replacement attempted')
    recovery = (recover_gateway or recover)(project, path, diagnostics=True)
    return dict(transport_recovery='PASS' if recovery.get('passed') is True else 'DEPENDENCY_UNAVAILABLE',
                recovery_mode='assisted', assisted_recovery='PASS' if recovery.get('passed') is True else 'FAILED',
                recovery_exercised=True, recovery=recovery)


def main():
    runner = Runner()
    path = runner.path
    result = {'passed': False, 'm2_verified': False, 'transport_recovery': 'NOT_EXECUTED',
              'assisted_recovery': 'NOT_EXERCISED', 'application_recovery': 'NOT_EXECUTED'}
    subject = runner.project + '-consumer'
    def dc(args, name, **kwargs):
        print(name, flush=True)
        kwargs.setdefault('timeout', 30)
        return runner.dc(args, name + '.log', **kwargs)
    def health(name, seconds=30):
        code = dc(['run', '--rm', '--no-deps', '--entrypoint', 'python3', 'crawler',
            '/workspace/test/m2/network_probe.py', '--single-health', '--health-seconds', str(seconds),
            '--readiness-output', name + '.json'], name, allow_failure=True, timeout=seconds+15).returncode
        return code, json.loads((path / (name + '.json')).read_text())
    def identity(name):
        code = "import json,hashlib;from pathlib import Path;p=Path('/var/lib/tor/hidden_service');print(json.dumps({'hostname':(p/'hostname').read_text().strip(),'hashes':{f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in p.glob('hs_ed25519_*')}}))"
        data = json.loads(dc(['exec', '-T', 'onion', 'python3', '-c', code], name, capture=True).stdout)
        runner.save(name + '.json', data)
        return data
    def check(condition, message):
        if not condition:
            raise RuntimeError(message)
    def interrupt(signum, frame):
        raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM, interrupt)
    signal.signal(signal.SIGINT, interrupt)
    before = host_state()
    runner.save('host-before.json', before)
    try:
        runner.validate_toolchain()
        runner.environment['M2_TOR_DIAGNOSTICS'] = '1'
        dc(['--profile', 'tools', 'config', '--quiet'], 'config')
        runner.started = True
        dc(['up', '-d', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '120',
            'fixture', 'tor', 'onion', 'frontier'], 'startup', timeout=145)
        first = identity('identity-before')
        (path / 'onion-hostname.txt').write_text(first['hostname'])
        code, baseline = health('baseline')
        result['baseline'] = baseline
        if code != 0 and baseline.get('passed') is False:
            result['transport_recovery'] = 'DEPENDENCY_UNAVAILABLE'
        check(code == 0 and baseline['passed'], 'Initial health failed; restart not attempted')
        # Build only the small frontier probe against the existing unchanged JAR.
        runner.command([sys.executable, '-B', 'test/m2/prepare.py', path], 'prepare.log')
        artifact = ROOT / 'target/onionall-brawler-0.1.0-SNAPSHOT.jar'
        with zipfile.ZipFile(artifact) as packaged:
            compiled_classes = list((ROOT / 'target/classes/dev/onionall/brawler/tor').glob('*.class'))
            check(bool(compiled_classes), 'Compiled Tor classes missing; run Maven verify')
            for compiled in compiled_classes:
                if packaged.read(compiled.relative_to(ROOT/'target/classes').as_posix()) != compiled.read_bytes():
                    raise RuntimeError('Stale runtime JAR; run Maven package/verify before targeted test')
        shutil.copy2(artifact, path / 'crawler-m2.jar')
        runner.save('artifact.json', {'sha256': hashlib.sha256(artifact.read_bytes()).hexdigest(),
            'source': 'packaged target artifact checked against compiled Tor classes'})
        repository = runner.environment.get('M2_MAVEN_REPOSITORY', str(ROOT / '.runtime/maven-linux-repository'))
        runner.command([runner.tool('mvn'), '-o', '-B', '-Dmaven.repo.local=' + repository,
            'org.apache.maven.plugins:maven-dependency-plugin:3.10.0:build-classpath',
            '-Dmdep.outputFile=' + str(path / 'classpath.txt')], 'dependencies.log', timeout=45)
        classpath = str(artifact) + os.pathsep + (path / 'classpath.txt').read_text().strip()
        runner.command([runner.tool('javac'), '--release', '25', '-cp', classpath, '-d', path / 'classes',
            ROOT / 'test/m2/FrontierProbe.java', ROOT / 'test/m2/RecoverySeriesProbe.java'], 'compile-probe.log')
        runner.command([runner.tool('jar'), '--update', '--file', path / 'crawler-m2.jar',
            '-C', path / 'classes', '.', '-C', path / 'resources', '.'], 'package-probe.log')
        dc(['run', '--rm', '--no-deps', 'probe', 'seed', 'pending'], 'seed')
        dc(['run', '--rm', '--no-deps', 'probe', 'snapshot', 'before.json'], 'frontier-before')
        # One idle local consumer proves the same container is quarantined/resumed.
        subject = runner.project + '-consumer'
        dc(['run', '-d', '--no-deps', '--name', subject, '--entrypoint', 'python3', 'crawler',
            '-c', 'import time;time.sleep(900)'], 'consumer-start')
        dc(['stop', '--timeout', '5', 'onion'], 'onion-stop', timeout=20)
        _, result['offline'] = health('offline', 10)
        dc(['up', '-d', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '90', 'onion'],
            'onion-return', timeout=110)
        result['identity_unchanged'] = first == identity('identity-after')
        check(result['identity_unchanged'], 'Onion identity changed')
        code, detected = health('recovery-detection')
        result['post_restart'] = detected
        result.update(restore_transport(runner.project, path, code, detected))
        check(result['transport_recovery'] == 'PASS', 'Bounded gateway replacement failed')
        state = runner.command(['docker', 'inspect', '--format', '{{json .State}}', subject], capture=True)
        consumer_state = json.loads(state.stdout)
        result['consumer_running'] = consumer_state.get('Running') is True and consumer_state.get('Paused') is False
        result['consumer_resumed'] = result['recovery_exercised'] and result['consumer_running']
        check(result['consumer_running'], 'Consumer is not running after transport recovery')
        dc(['run', '--rm', '--no-deps', 'probe', 'snapshot', 'after.json'], 'frontier-after')
        a, b = [json.loads((path / name).read_text())['entries'] for name in ('before.json', 'after.json')]
        check(a == b and len(a) == 1, 'Replacement changed pending frontier')
        runner.command(['docker', 'rm', '-f', subject], 'consumer-cleanup.log')
        # Outer orchestration budget includes the existing finite 160s series budget;
        # individual SOCKS/HTTP deadlines remain 20s and failed requests are not retried.
        result['application_recovery'] = 'INCONCLUSIVE'
        dc(['run', '--rm', '--no-deps', '-e', 'M2_CRAWL_TTL=45', '-e', 'M2_VERIFY_BEFORE_CRAWL=1',
            '-e', 'M2_TOR_DIAGNOSTICS=1', '-e', 'M2_SERIES_OUTPUT=resumed-series.json', 'crawler'], 'crawl-resumed', timeout=120 + 160)
        dc(['run', '--rm', '--no-deps', 'probe', 'snapshot', 'resumed.json'], 'frontier-resumed')
        same_container = json.loads((path / 'resumed-series.json').read_text())
        check(same_container.get('passed') is True and same_container.get('completed') == ['/robots.txt', '/health']*3,
            'Same-container readiness series missing or failed')
        result['same_container_series_passed'] = True
        end = json.loads((path / 'resumed.json').read_text())
        check('M2_PENDING_MARKER' in (path / 'crawl-resumed.log').read_text(), 'Pending content did not resume')
        check(len(end['entries']) == 1 and end['entries'][0]['refetch'] > end['captured_at_epoch'] + 3600,
            'Pending URL was not successfully rescheduled')
        dc(['run', '--rm', '--no-deps', 'probe', 'seed', 'root'], 'seed-root-series')
        dc(['run', '--rm', '--no-deps', '-e', 'M2_CRAWL_TTL=60', '-e', 'M2_VERIFY_BEFORE_CRAWL=1',
            '-e', 'M2_TOR_DIAGNOSTICS=1', '-e', 'M2_SERIES_OUTPUT=continued-series.json', 'crawler'], 'crawl-series', timeout=150 + 160)
        content=(path / 'crawl-series.log').read_text()
        check(all(marker in content for marker in ('M2_ROOT_MARKER','M2_A_MARKER','M2_B_MARKER')),
            'Sequential actual Storm fetch series incomplete')
        dc(['run', '--rm', '--no-deps', 'probe', 'snapshot', 'series.json'], 'series-frontier')
        check(len(json.loads((path/'series.json').read_text())['entries']) == 8, 'Unexpected series frontier scope')
        traffic=[json.loads(line) for line in (path/'requests.jsonl').read_text().splitlines()]
        check(not any(row['path']=='/blocked' for row in traffic), 'Robots denied path fetched')
        result['application_recovery'] = 'PASS'
        result['actual_storm_series_passed'] = True
        result['frontier_preserved_and_resumed'] = True
        result['passed'] = True
    except (OSError, ValueError, KeyError, TypeError, RuntimeError, KeyboardInterrupt, subprocess.SubprocessError) as error:
        result['blocker'] = str(error)
        result['failure_category'] = ('EVIDENCE_MISSING' if isinstance(error, FileNotFoundError) else
            'EVIDENCE_INVALID' if isinstance(error, (ValueError, KeyError, TypeError)) else
            'DEPENDENCY_UNAVAILABLE' if result['transport_recovery'] == 'DEPENDENCY_UNAVAILABLE' else 'INCONCLUSIVE')
    finally:
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        try:
            runner.command(['docker', 'rm', '-f', subject], 'consumer-final-cleanup.log',
                timeout=20, allow_failure=True)
            try:
                dc(['logs', '--no-color'], 'services', allow_failure=True)
            finally:
                dc(['down', '--timeout', '5'], 'cleanup', timeout=60, allow_failure=True)
            containers = runner.command(['docker', 'ps', '-aq', '--filter',
                'label=com.docker.compose.project=' + runner.project], capture=True, timeout=20, allow_failure=True)
            networks = runner.command(['docker', 'network', 'ls', '-q', '--filter',
                'label=com.docker.compose.project=' + runner.project], capture=True, timeout=20, allow_failure=True)
            result['containers_removed'] = containers.returncode == 0 and not containers.stdout.strip()
            result['networks_removed'] = networks.returncode == 0 and not networks.stdout.strip()
            volumes = runner.command(['docker', 'volume', 'inspect'] + [runner.project + '_' + key for key in
                ('frontier-data', 'tor-state', 'onion-state')], capture=True, timeout=20, allow_failure=True)
            inspection = json.loads(volumes.stdout or '[]')
            runner.save('retained-volumes.json', inspection)
            result['volumes_retained'] = volumes.returncode == 0 and len(inspection) == 3
            after = host_state()
            runner.save('host-after.json', after)
            result['vpn_routes_rules_dns_unchanged'] = before == after
            result['passed'] &= all(result[key] for key in ('containers_removed', 'networks_removed',
                'volumes_retained', 'vpn_routes_rules_dns_unchanged'))
        except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
            result['passed'] = False
            result['cleanup_blocker'] = str(error)
            result['failure_category'] = 'CLEANUP_FAILED'
        finally:
            runner.save('restart-recovery.json', result)
            print(str(path), flush=True)
            print(json.dumps(result, indent=2), flush=True)
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
