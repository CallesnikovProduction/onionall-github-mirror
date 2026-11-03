"""Real CLI → durable admission → Storm/TorProtocol → archive, internal fixture only.

Run after application package and frontier-server/build.sh, using existing cached
M2_STORM_IMAGE=...cleanup2. Never starts Tor, publishes ports or migrates an old DB.
Fresh uniquely named Frontier volume is retained. No acceptance flag is inferred
from worker exit0: quota/readback, traffic, archive and restart are checked below.
"""
import base64
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from m2 import Runner
from restart_recovery import host_state
from deterministic_recovery import cleanup_project


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def onion(number):
    key = bytes([number]) * 32
    payload = key + hashlib.sha3_256(b'.onion checksum' + key + b'\3').digest()[:2] + b'\3'
    return base64.b32encode(payload).decode().lower() + '.onion'


def runtime(seconds):
    return dict(frontier_host='frontier', frontier_port=7071,
                socks_host='socks-fixture', socks_port=9050,
                storm_executable='/apache-storm/bin/storm', crawler_jar='/evidence/crawler-m2.jar',
                work_directory='/evidence/execution', seconds=seconds,
                http_timeout_ms=2000, retry_minutes=1)


def session_count(document):
    session = document['authoritative_admission']
    require(session.get('outcome') == 'SESSION_OPEN', 'Missing authoritative session readback')
    count = session.get('reserved_count')
    require(type(count) is int and 0 <= count <= 4, 'Invalid or excessive authoritative quota')
    return count


def verify_archive(root, host, foreign, job, pages, search, cli):
    rows = pages['pages']
    expected = {'http://' + host + p for p in ('/a', '/b', '/c')}
    require(len(rows) == 3 and {row['canonical_url'] for row in rows} == expected,
            'Archive must contain the three eligible pages only')
    require(len(search['results']) == 1 and search['results'][0]['canonical_url'] == 'http://' + host + '/c',
            'Actual local extracted-text search failed')
    indexed = {row['canonical_url']: row for row in rows}
    require([indexed['http://' + host + p]['depth'] for p in ('/a', '/b', '/c')] == [0, 1, 2],
            'Archive discovery depths mismatch')
    edges = []
    for row in rows:
        page = cli(['page', 'show', row['page_id']], 'page-' + row['page_id'])
        snapshot = Path(page['storage_location'])
        require(snapshot.is_relative_to(root / 'archive') and snapshot.is_file(), 'Invalid snapshot location')
        require(hashlib.sha256(snapshot.read_bytes()).hexdigest() == page['content_hash']
                and snapshot.stat().st_size == page['content_size'], 'Snapshot object is inconsistent')
        require(page['http_status'] == 200 and page['job_id'] == job and page['source_kind'] == 'CRAWLER',
                'Archive must identify actual successful crawler observation')
        require(len(page['history']) == 1, 'Retry/replay created duplicate snapshot versions')
        require('ARCHIVE_' in page['text'], 'Actual parsed text absent')
        for edge in page['links']:
            require(edge['job_id'] == job and edge['source_page_id'] == row['page_id']
                    and edge['source_url'] == row['canonical_url'] and edge['source_host'] == host,
                    'Graph source/job provenance mismatch')
            require(edge['discovered_at'] and edge['discovery_time_basis'] in
                    {'DISCOVERY_REPORTED', 'ARCHIVE_OBSERVED'}, 'Graph discovery clock absent')
            edges.append(edge)
    require(any(e['source_url'].endswith('/a') and e['target_url'].endswith('/b')
                and e['target_page_id'] == indexed['http://' + host + '/b']['page_id'] for e in edges),
            'A→B edge cannot resolve its archived target')
    require(any(e['target_url'] == 'http://' + foreign + '/escape' and e['target_host'] == foreign
                and e['disposition'] == 'SCOPE_REJECTED' and e['target_page_id'] is None for e in edges),
            'Rejected foreign edge identity was lost')
    require(any(e['source_url'].endswith('/c') and e['target_url'].endswith('/deep')
                and e['disposition'] == 'DEPTH_REJECTED' for e in edges), 'Depth rejection absent')
    require(not list((root / 'archive').rglob('*.tmp')), 'Incomplete archive object remained')
    return edges


def main():
    r = Runner()
    p = r.path
    result = dict(passed=False, durable_admission_verified=False, m2_verified=False, m3_verified=False,
                  transport='internal controlled SOCKS, production TorProtocol',
                  scheduling_owner='URLFrontier', admission_owner='server-owned RocksDB transaction')
    before = host_state()
    r.save('host-before.json', before)
    subject = r.project + '-admission-cli'
    def dc(args, name, timeout=60, **kwargs):
        print(name, flush=True)
        return r.dc(args, name + '.log', timeout=timeout, **kwargs)
    def cli(args, name, inside=False, expect_success=True, timeout=30):
        if inside:
            command = ['docker', 'exec', subject, '/cli/bin/onionall', '--json', '--archive-dir', '/evidence/archive', *args]
        else:
            command = [str(ROOT / 'target/onionall/bin/onionall'), '--json', '--archive-dir', str(p / 'archive'), *args]
        process = subprocess.run(command, cwd=ROOT, env=r.environment, capture_output=True, text=True, timeout=timeout)
        (p / (name + '.log')).write_text(process.stdout + process.stderr)
        require((process.returncode == 0) == expect_success, name + ' CLI exit mismatch; inspect log')
        value = json.loads(process.stdout)
        r.save(name + '.json', value)
        return value
    def connected_status(job, name):
        return cli(['crawl', 'status', job, '--runtime', '/evidence/runtime.json',
                    '--admission-token-file', '/evidence/admission-token'], name, inside=True)
    def start(job, name):
        value = cli(['crawl', 'start', '--plan', job, '--runtime', '/evidence/runtime.json',
                     '--admission-token-file', '/evidence/admission-token'], name, inside=True, timeout=230)
        require(value.get('admission') == 'COMMITTED' and value.get('worker_exit_code') == 0,
                'Start did not verify committed admission and actual worker exit')
        require(value.get('crawl_completed_verified') is False, 'Worker exit must not manufacture crawl completion')
        return value
    def readback(action, job, url, name):
        r.command(['docker', 'exec', subject, 'java', '-cp',
                   '/apache-storm/lib/*:/apache-storm/lib-common/*:/evidence/crawler-m2.jar',
                   'dev.onionall.brawler.admission.AdmissionProbe', action, job, url, name + '.json'],
                  name + '.log', timeout=20)
        return json.loads((p / (name + '.json')).read_text())
    def snapshot(name):
        dc(['run', '--rm', '--no-deps', 'probe', 'snapshot', name + '.json'], name)
        return json.loads((p / (name + '.json')).read_text())
    def interrupted(signum, frame):
        raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    try:
        r.validate_toolchain()
        host, foreign = onion(1), onion(2)
        seed = 'http://' + host + '/a'
        (p / 'onion-hostname.txt').write_text(host)
        (p / 'foreign-hostname.txt').write_text(foreign)
        token = p / 'admission-token'
        with open(token, 'x', opener=lambda path, flags: os.open(path, flags, 0o600)) as output:
            output.write(secrets.token_hex(32))
        plan = cli(['crawl', 'plan', '--seed', seed, '--scope', 'same-host', '--depth', '2',
                    '--max-pages', '4', '--provider', 'controlled-admission-fixture'], 'plan')
        job = plan['plan_id']
        (p / 'runtime.json').write_text(json.dumps(runtime(20)))
        artifact = ROOT / 'target/onionall-brawler-0.1.0-SNAPSHOT.jar'
        candidate = ROOT / 'frontier-server/target/urlfrontier-admission.jar'
        require(artifact.is_file() and candidate.is_file(), 'Build application and candidate server first')
        shutil.copy2(artifact, p / 'crawler-m2.jar')
        shutil.copy2(candidate, p / 'frontier-admission.jar')
        r.save('artifact.json', dict(application_sha256=hashlib.sha256(artifact.read_bytes()).hexdigest(),
                                     server_sha256=hashlib.sha256(candidate.read_bytes()).hexdigest(), plan_id=job))
        # Read-only stock protocol probe; admission is performed only by the real CLI.
        probe = (ROOT / 'test/m2/FrontierProbe.java').read_text().replace(
            'private static final String CRAWL = "m2";', 'private static final String CRAWL = ' + json.dumps(job) + ';')
        (p / 'FrontierProbe.java').write_text(probe)
        r.command([r.tool('javac'), '--release', '25', '-cp', artifact, '-d', p / 'classes', p / 'FrontierProbe.java', ROOT / 'test/admission/AdmissionProbe.java'],
                  'compile-probe.log', timeout=30)
        r.command([r.tool('jar'), '--update', '--file', p / 'crawler-m2.jar', '-C', p / 'classes', '.'],
                  'package-probe.log', timeout=20)
        # Read the capability inside the container. Resolved Compose evidence never contains it.
        launcher = 'export ONIONALL_ADMISSION_TOKEN="$(cat /evidence/admission-token)"; exec java -cp /evidence/frontier-admission.jar crawlercommons.urlfrontier.service.URLFrontierServer "$@"'
        overlay = {'services': {'crawler': {'volumes': [str(ROOT / 'target/onionall') + ':/cli:ro']},
            'frontier': {'image': r.environment.get('M2_STORM_IMAGE', 'storm:3.1.0-jre25'),
                         'user': r.environment['M2_RUNTIME_USER'],
                         'entrypoint': ['/bin/sh', '-ec', launcher, 'frontier-entrypoint'],
                         'command': ['rocksdb.path=/crawldir/rocksdb'],
                         'volumes': [str(p) + ':/evidence'], 'read_only': True,
                         'tmpfs': ['/tmp:rw,exec,nosuid,nodev,mode=1777']},
            'socks-fixture': {'image': 'python:3.12-slim', 'user': r.environment['M2_RUNTIME_USER'],
                              'entrypoint': ['python3', '-B', '/fixture.py'],
                              'volumes': [str(ROOT / 'test/archive/socks_fixture.py') + ':/fixture.py:ro', str(p) + ':/evidence'],
                              'networks': ['crawler'], 'read_only': True, 'cap_drop': ['ALL'],
                              'security_opt': ['no-new-privileges:true'], 'tmpfs': ['/tmp']}}}
        override = p / 'admission-compose.json'
        override.write_text(json.dumps(overlay))
        r.compose += ['-f', str(override)]
        composed = json.loads(dc(['--profile', 'tools', 'config', '--format', 'json'], 'config', capture=True).stdout)
        r.save('compose.json', composed)
        require(composed['networks']['crawler']['internal'], 'Internal-only network required')
        for name in ('crawler', 'frontier', 'socks-fixture'):
            require(set(composed['services'][name]['networks']) == {'crawler'}
                    and not composed['services'][name].get('ports'), 'Unexpected egress or exposed ports')
        r.started = True
        dc(['up', '-d', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '40', 'frontier', 'socks-fixture'], 'startup')
        dc(['run', '--rm', '--no-deps', 'probe', 'ready'], 'frontier-ready')
        dc(['run', '-d', '--no-deps', '--name', subject, '--entrypoint', 'python3', 'crawler',
            '-c', 'import time;time.sleep(900)'], 'cli-container')
        refused = cli(['crawl', 'start', '--plan', job, '--runtime', '/evidence/runtime.json',
                       '--admission-token-file', '/evidence/admission-token'], 'unapproved-start', inside=True, expect_success=False)
        require('approval' in json.dumps(refused).lower(), 'Unapproved plan rejected for an unrelated reason')
        require(snapshot('before-approval')['entries'] == [], 'Unapproved start scheduled work')
        require(readback('session', job, seed, 'unapproved-readback').get('outcome') == 'SESSION_NOT_FOUND',
                'Unapproved start created authoritative admission state')
        require(cli(['crawl', 'approve', job], 'approve')['approved'] is True, 'Approval not persisted')
        start(job, 'first-start')
        seed_reservation = readback('reservation', job, seed, 'seed-reservation')
        require(seed_reservation.get('reservation_id') and seed_reservation.get('ordinal') == 1
                and seed_reservation.get('depth') == 0, 'Seed reservation identity/quota missing')
        first_status = connected_status(job, 'first-status')
        require(session_count(first_status) == 3, 'Expected seed+B+robots target reservations before retry')
        pending = snapshot('before-restart')
        by_url = {row['url']: row for row in pending['entries']}
        require(by_url['http://' + host + '/b']['metadata'].get('fetch.error.count') == ['1'],
                'Transient failure/backoff missing before worker restart')
        # New server process reopens only this fresh owned volume, then same plan resumes.
        dc(['stop', '--timeout', '10', 'frontier'], 'frontier-stop')
        dc(['rm', '-f', 'frontier'], 'frontier-remove')
        dc(['up', '-d', '--no-deps', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '40', 'frontier'], 'frontier-recreate')
        require(snapshot('after-restart')['entries'] == pending['entries'], 'Frontier progress changed after recreation')
        require(session_count(connected_status(job, 'restored-status')) == 3, 'Durable quota changed after server restart')
        require(readback('reservation', job, seed, 'restored-seed-reservation') == seed_reservation,
                'Committed seed identity/provenance changed after server restart')
        (p / 'runtime.json').write_text(json.dumps(runtime(100)))
        start(job, 'resumed-start')
        status = connected_status(job, 'final-status')
        require(session_count(status) == 4, 'Discovery budget is not durable/exact')
        final = snapshot('frontier-final')
        by_url = {row['url']: row for row in final['entries']}
        require(set(by_url) == {'http://' + host + path for path in ('/a', '/b', '/c', '/blocked')},
                'Actual Frontier scope/count mismatch')
        require(by_url['http://' + host + '/blocked']['refetch'] == 0, 'Robots refusal not terminal')
        for path in ('/a', '/b', '/c'):
            row = by_url['http://' + host + path]
            require(row['refetch'] > final['captured_at_epoch'] + 3600 and not row['metadata'].get('fetch.error.count'),
                    'Actual successful result not persisted')
        traffic = [json.loads(line) for line in (p / 'archive-requests.jsonl').read_text().splitlines()]
        http = [row for row in traffic if row['phase'] == 'http']
        require(Counter(row['path'] for row in http) == Counter({'/robots.txt': 2, '/a': 1, '/b': 2, '/c': 1}),
                'Unexpected duplicate/scope/robots requests')
        require([row['status'] for row in http if row['path'] == '/b'] == [503, 200], 'Scheduled transient retry absent')
        require(all(row['atyp'] == 3 for row in http), 'Remote onion DNS contract violated')
        require(status['archived_pages'] == 3 and status['snapshots'] == 3, 'Catalog totals mismatch')
        require(any(row['category'] == 'FETCH_ERROR' and row['url'].endswith('/b') for row in status['failure_records']),
                'Actual failed request not observable')
        require(any(row['category'] == 'ROBOTS_DENIED' for row in status['failure_records']), 'Robots refusal not observable')
        pages = cli(['pages', '--job', job, '--limit', '10'], 'pages')
        search = cli(['search-local', 'ARCHIVE_GAMMA', '--job', job], 'search')
        edges = verify_archive(p, host, foreign, job, pages, search, cli)
        # Repeating start after all eligible work completed cannot consume quota or refetch early.
        (p / 'runtime.json').write_text(json.dumps(runtime(5)))
        start(job, 'replayed-start')
        require(session_count(connected_status(job, 'replayed-status')) == 4, 'Repeated start double-counted quota')
        require(readback('reservation', job, seed, 'replayed-seed-reservation') == seed_reservation,
                'Repeated start changed committed seed identity/provenance')
        require(snapshot('replayed-frontier')['entries'] == final['entries'], 'Repeated start rewrote completed state')
        require(cli(['pages', '--job', job, '--limit', '10'], 'replayed-pages') == pages, 'Repeated start changed archive')
        cli(['archive', 'export', '--job', job, '--output', str(p / 'summary.json')], 'export')
        result.update(passed=True, packaged_cli_verified=True, durable_admission_verified=True, job_id=job, frontier_records=4,
                      graph_edges=len(edges), worker_restart_verified=True, scheduled_retry_verified=True,
                      server_restart_quota_verified=True, repeated_start_verified=True, archive_reopen_verified=True)
    except (OSError, ValueError, KeyError, TypeError, RuntimeError, subprocess.SubprocessError, KeyboardInterrupt) as error:
        result['blocker'] = type(error).__name__ + ': ' + str(error)
    finally:
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        try:
            r.command(['docker', 'rm', '-f', subject], 'cli-cleanup.log', allow_failure=True, timeout=20)
        except (OSError, subprocess.SubprocessError) as error:
            result.update(passed=False, worker_cleanup_blocker=str(error))
        cleanup_project(r, None, None, result)
        try:
            after = host_state()
            r.save('host-after.json', after)
            result['host_unchanged'] = before == after
            result['passed'] &= result['host_unchanged']
        except (OSError, ValueError, subprocess.SubprocessError) as error:
            result.update(passed=False, host_check_blocker=str(error))
        result['durable_admission_verified'] = bool(result['passed'] and result['durable_admission_verified'])
        r.save('admission-local.json', result)
        print(p, flush=True)
        print(json.dumps(result, indent=2), flush=True)
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
