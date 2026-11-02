"""One fail-closed recovery cycle for a local M2 Compose gateway, not a watchdog.

Only the configured Tor service is replaced. Persistent state and endpoint stay
unchanged; all running local crawler consumers remain paused until real health.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import signal
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def connect_failure(document):
    """Only failures after SOCKS greeting qualify, never HTTP/policy errors."""
    if not isinstance(document, dict):
        return False
    attempts = document.get('attempts', [document])
    return (document.get('passed') is False and isinstance(attempts, list) and bool(attempts)
            and all(isinstance(row, dict) and row.get('phase') == 'socks_connect_reply'
                    and row.get('error_type') in {'TimeoutError', 'OSError'}
                    and row.get('socks_reply_code') != 0 for row in attempts))


def recover(project, path, detection='recovery-detection.json', diagnostics=None):
    if not re.fullmatch(r'onionall-brawler-m2-[a-f0-9]{12}', project):
        raise ValueError('Recovery requires an isolated M2 project name')
    path = Path(path).resolve()
    if path != ROOT / '.runtime/m2' / project:
        raise ValueError('Evidence directory must belong to this M2 project')
    if Path(detection).name != detection or not detection.endswith('.json'):
        raise ValueError('Detection must be a local JSON filename')
    # Exclusive creation also prevents concurrent controllers and restart loops.
    journal = path / 'gateway-recovery.json'
    result = {'passed': False, 'state': 'detecting', 'events': [],
              'replacement_limit': 1, 'automatic_direct_fallback': False}
    with journal.open('x', encoding='utf-8') as output:
        json.dump(result, output)
    environment = os.environ.copy()
    environment.update(M2_RUN_DIR=str(path), M2_RUN_REL=str(path.relative_to(ROOT)),
        M2_RUNTIME_USER=f'{os.getuid()}:{os.getgid()}' if os.name != 'nt' else '1000:1000')
    compose = ['docker', 'compose', '-p', project, '-f', 'compose.m2.yaml']
    consumers = []
    quarantined = False

    def save(state):
        result['state'] = state
        result['events'].append({'state': state, 'utc': datetime.now(timezone.utc).isoformat()})
        journal.write_text(json.dumps(result, indent=2), encoding='utf-8')

    def command(args, name, timeout=30, capture=False, required=True):
        with (path / ('recovery-' + name + '.log')).open('w', encoding='utf-8') as output:
            status = subprocess.run(args, cwd=ROOT, env=environment, timeout=timeout,
                text=True, stdout=subprocess.PIPE if capture else output, stderr=output)
            if capture:
                output.write(status.stdout)
        if required and status.returncode:
            raise RuntimeError(name + ' failed')
        return status

    def health(filename, name):
        status = command(compose + ['run', '--rm', '--no-deps', '--entrypoint', 'python3',
            'crawler', '/workspace/test/m2/network_probe.py', '--single-health',
            '--readiness-output', filename], name, 45, required=False)
        document = json.loads((path / filename).read_text())
        return status.returncode, document

    try:
        # An existing readiness failure is retained, never overwritten by success.
        if not (path / detection).exists():
            health(detection, 'detect')
        observation = json.loads((path / detection).read_text())
        if not connect_failure(observation):
            raise RuntimeError('No qualifying SOCKS CONNECT failure; no replacement attempted')
        gateway = command(compose + ['ps', '-q', 'tor'], 'gateway-identity-before', capture=True).stdout.strip()
        if not gateway:
            raise RuntimeError('Configured gateway container missing')
        save('quarantining')
        ids = command(['docker', 'ps', '-q', '--filter', 'label=com.docker.compose.project=' + project,
            '--filter', 'label=com.docker.compose.service=crawler'], 'consumers', capture=True).stdout.split()
        consumers = ids
        result['consumer_count'] = len(consumers)
        # Set before pausing so even a partial pause failure closes the gateway.
        quarantined = True
        for index, container in enumerate(consumers):
            command(['docker', 'pause', container], 'pause-' + str(index))
        save('quarantined')
        command(compose + ['stop', '--timeout', '5', 'tor'], 'gateway-stop', 20)
        save('replacing')
        # Restart the existing container: configured JVM clients retain its resolved IP.
        command(compose + ['restart', '--timeout', '5', 'tor'], 'gateway-restart', 20)
        command(compose + ['up', '-d', '--no-deps', '--no-recreate', '--no-build', '--pull', 'never',
            '--wait', '--wait-timeout', '90', 'tor'], 'gateway-ready', 110)
        current_gateway = command(compose + ['ps', '-q', 'tor'], 'gateway-identity-after', capture=True).stdout.strip()
        if current_gateway != gateway:
            raise RuntimeError('Gateway container changed; pinned client endpoint cannot be trusted')
        result['gateway_container_preserved'] = True
        save('verifying')
        code, health_result = health('recovery-health.json', 'health')
        if not isinstance(health_result, dict):
            raise RuntimeError('Malformed recovery health evidence')
        if health_result.get('passed') is False:
            # network_probe emits a flat phase record on transport failure; a
            # missing HTTP health object is expected before CONNECT succeeds.
            result['health_failure'] = {key: health_result[key] for key in
                ('phase', 'error_type', 'elapsed_seconds', 'socks_reply_code') if key in health_result}
            raise RuntimeError('Replacement controlled onion probe failed; inspect recovery-health.json')
        if not isinstance(health_result.get('health'), dict):
            raise RuntimeError('Malformed recovery health evidence')
        observed = health_result['health']
        if (code != 0 or health_result.get('passed') is not True
                or observed.get('http_status') != 200 or observed.get('health_body') != 'ready'
                or observed.get('socks_atyp') != 3 or observed.get('remote_hostname_sent') is not True):
            raise RuntimeError('Replacement did not restore controlled onion HTTP; quarantine retained')
        diagnostic_args = ['-e', 'M2_TOR_DIAGNOSTICS=1'] if (diagnostics is True or
            diagnostics is None and environment.get('M2_TOR_DIAGNOSTICS') == '1') else []
        command(compose + ['run', '--rm', '--no-deps'] + diagnostic_args + ['--entrypoint', 'java', 'probe',
            '-cp', '/apache-storm/lib/*:/apache-storm/lib-common/*:/evidence/crawler-m2.jar',
            'dev.onionall.brawler.m2.RecoverySeriesProbe'], 'series', 160)
        series = json.loads((path / 'recovery-series.json').read_text())
        if not isinstance(series, dict):
            raise RuntimeError('Malformed recovery series evidence')
        if series.get('passed') is not True or series.get('completed') != ['/robots.txt', '/health'] * 3:
            raise RuntimeError('Real protocol series failed; quarantine retained')
        save('resuming')
        for index, container in enumerate(consumers):
            command(['docker', 'unpause', container], 'resume-' + str(index))
        result['passed'] = True
        save('recovered')
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError, KeyboardInterrupt) as error:
        result['passed'] = False
        result['blocker'] = type(error).__name__ + ': ' + str(error)
        # If resuming partly failed, re-quarantine every surviving consumer.
        if quarantined:
            for index, container in enumerate(consumers):
                try:
                    command(['docker', 'pause', container], 'retain-pause-' + str(index), required=False)
                except (OSError, subprocess.SubprocessError):
                    pass
            try:
                result['gateway_stop_exit_code'] = command(compose + ['stop', '--timeout', '5', 'tor'],
                    'fail-closed-stop', 20, required=False).returncode
            except (OSError, subprocess.SubprocessError):
                result['gateway_stop_exit_code'] = None
        save('blocked')
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('project')
    parser.add_argument('run_dir', type=Path)
    args = parser.parse_args()
    def interrupted(signum, frame):
        signal.signal(signum, signal.SIG_IGN)
        raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM, interrupted)
    try:
        outcome = recover(args.project, args.run_dir)
    except (OSError, ValueError) as error:
        outcome = {'passed': False, 'blocker': str(error)}
    print(json.dumps(outcome, indent=2))
    raise SystemExit(0 if outcome['passed'] else 1)
