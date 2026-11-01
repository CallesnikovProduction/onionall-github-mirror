"""Bounded local M2 runtime owner: one crawler batch and at most one recovery.

Infrastructure/config/JAR must already exist. Frontier scheduling is authoritative;
no URLs are reseeded or metadata rewritten. This is not distributed Storm recovery.
"""
import argparse
import json
import os
from pathlib import Path
import re
import signal
import subprocess
from m2_recovery import ROOT, recover


def supervise(project, path):
    path = Path(path).resolve()
    if not re.fullmatch(r'onionall-brawler-m2-[a-f0-9]{12}', project) or path != ROOT / '.runtime/m2' / project:
        raise ValueError('Expected owned M2 project/evidence directory')
    journal = path / 'runtime-supervision.json'
    with journal.open('x') as output:
        json.dump({'passed': False, 'state': 'running'}, output)
    environment = os.environ.copy()
    environment.update(M2_RUN_DIR=str(path), M2_RUN_REL=str(path.relative_to(ROOT)),
        M2_RUNTIME_USER=f'{os.getuid()}:{os.getgid()}' if os.name != 'nt' else '1000:1000')
    compose = ['docker', 'compose', '-p', project, '-f', 'compose.m2.yaml']
    result = {'passed': False, 'batches': [], 'state': 'running'}
    container = project + '-runtime-crawler'
    try:
        existing = path / 'gateway-recovery.json'
        if existing.exists():
            state = json.loads(existing.read_text())
            if not isinstance(state, dict) or state.get('passed') is not True or state.get('state') != 'recovered':
                raise RuntimeError('Existing quarantine prevents crawl')
        for attempt in range(2):
            log = path / f'runtime-crawl-{attempt}.log'
            with log.open('w') as output:
                code = subprocess.run(compose + ['run', '--rm', '--no-deps', '--name', container, 'crawler'],
                    cwd=ROOT, env=environment, stdout=output, stderr=subprocess.STDOUT, timeout=210).returncode
            text = log.read_text(errors='replace')
            # StdOutIndexer content may contain category words; match exception
            # records instead of arbitrary document text. Logs are still a
            # diagnostic interface, not an authenticated production event bus.
            failed = code != 0 or re.search(r'(?m)^(?:Caused by: )?java\.io\.IOException: '
                r'(?:ROBOTS_UNAVAILABLE|SOCKS_FAILURE|REQUEST_TIMEOUT|ONION_UNREACHABLE)(?:$|[ :])', text) is not None
            result['batches'].append({'exit_code': code, 'transport_failed': failed, 'log': log.name})
            if not failed:
                result.update(passed=True, state='completed')
                break
            if attempt != 0 or existing.exists():
                raise RuntimeError('Runtime recovery budget exhausted; no further crawl')
            # Failed process is finished before recovery; no stale JVM endpoint/cache.
            recovered = recover(project, path)
            if recovered.get('passed') is not True:
                raise RuntimeError('Recovery failed; crawl remains quarantined')
        return result
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError, KeyboardInterrupt) as error:
        result.update(passed=False, state='blocked', blocker=str(error))
        # Scope is an exact owned name. Never delete persistent state.
        try:
            result['crawler_stop_exit_code'] = subprocess.run(['docker', 'stop', '--time', '30', container],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=45).returncode
        except (OSError, subprocess.SubprocessError) as cleanup_error:
            result['cleanup_blocker'] = type(cleanup_error).__name__
        return result
    finally:
        journal.write_text(json.dumps(result, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('project')
    parser.add_argument('run_dir', type=Path)
    args = parser.parse_args()
    def interrupted(signum, frame):
        signal.signal(signum, signal.SIG_IGN)
        raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM, interrupted)
    outcome = supervise(args.project, args.run_dir)
    print(json.dumps(outcome, indent=2))
    raise SystemExit(0 if outcome['passed'] else 1)
