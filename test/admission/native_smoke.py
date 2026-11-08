"""Docker-free generated-product fixture in an isolated Linux network namespace.

Run through unshare -Urn after enabling ONLY that namespace's loopback. Requires
locally extracted Storm 3.1.0 lib/lib-common and the existing cleanup2 patched JAR.
Never enables network access or changes host networking. Not Compose/Tor evidence.
"""
import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts/runners'))
sys.path.insert(0, str(ROOT / 'test/archive'))
from runtime import fixture_host
from socks_fixture import Fixture


def require(value, message):
    if not value:
        raise AssertionError(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--storm-libs', type=Path, required=True)
    parser.add_argument('--patched-server', type=Path, required=True)
    args = parser.parse_args()
    require({name for _, name in socket.if_nameindex()} == {'lo'}, 'Run only in an isolated network namespace')
    require(os.readlink('/proc/self/ns/net') != os.environ['FIXTURE_PARENT_NETNS'], 'Host network forbidden')
    libs, patched = args.storm_libs.resolve(), args.patched_server.resolve()
    require((libs / 'lib-common/storm-client-3.1.0.jar').is_file() and patched.is_file(), 'Storm inputs missing')
    state = Path(tempfile.mkdtemp(prefix='native-', dir=ROOT / '.runtime'))
    result = dict(passed=False, evidence=str(state), real_tor='NOT_RUN', compose='BLOCKED_DOCKER_UNAVAILABLE')
    print(state, flush=True)
    host, foreign = fixture_host(1), fixture_host(2)
    (state / 'onion-hostname.txt').write_text(host)
    (state / 'foreign-hostname.txt').write_text(foreign)
    token = state / 'admission-token'
    token.write_text(secrets.token_hex(32))
    token.chmod(0o600)
    application = ROOT / 'target/onionall-brawler-0.1.0-SNAPSHOT.jar'
    server = ROOT / 'frontier-server/target/urlfrontier-admission.jar'
    result['artifacts'] = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                           for p in (application, server)}
    # Only adapt Storm's command syntax to its actual LocalCluster main. This
    # invokes the same upstream local runtime and generated Flux graph, no fake scheduler.
    adapter = state / 'storm'
    adapter.write_text('#!/usr/bin/env python3\n' + '''import json, os, sys
from urllib.parse import quote
from pathlib import Path
args=sys.argv[1:]
assert args.pop(0)=='local'
options=[]
while args[0]=='-c':
    args.pop(0)
    key,value=args.pop(0).split('=',1)
    options.append(quote(key+'='+json.dumps(value),safe=''))
jar,main,topology,flag,seconds=args
assert main=='org.apache.storm.flux.Flux' and flag=='--local-ttl'
cp=os.environ['FIXTURE_STORM_CP']+':'+jar
os.execvp('java',['java','--enable-native-access=ALL-UNNAMED',
    '-Dstorm.options='+','.join(options),'-Dstorm.local.sleeptime='+seconds,
    '-Dstorm.home='+str(Path(topology).parent),'-Dstorm.jar='+jar,'-cp',cp,
    'org.apache.storm.LocalCluster',main,topology])
''')
    adapter.chmod(0o755)
    env = dict(os.environ, ONIONALL_ADMISSION_TOKEN=token.read_text(),
               FIXTURE_STORM_CP=f'{patched}:{libs}/lib/*:{libs}/lib-common/*')
    descriptor = dict(frontier_host='localhost', frontier_port=7071,
                      socks_host='127.0.0.1', socks_port=19050,
                      storm_executable=str(adapter), crawler_jar=str(application),
                      work_directory=str(state / 'execution'), seconds=120,
                      http_timeout_ms=2000, retry_minutes=1)
    (state / 'runtime.json').write_text(json.dumps(descriptor))
    fixture = Fixture(('127.0.0.1', 19050), state)
    thread = threading.Thread(target=fixture.serve_forever, daemon=True)
    thread.start()
    log = (state / 'frontier.log').open('w')
    process = subprocess.Popen(['java', '--enable-native-access=ALL-UNNAMED', '-cp', str(server),
                                'crawlercommons.urlfrontier.service.URLFrontierServer',
                                'rocksdb.path=' + str(state / 'rocksdb')],
                               env=env, stdout=log, stderr=subprocess.STDOUT)

    def cli(arguments, name, success=True):
        command = [str(ROOT / 'target/onionall/bin/onionall'), '--json',
                   '--archive-dir', str(state / 'archive'), *arguments]
        reply = subprocess.run(command, cwd=ROOT, env=env, capture_output=True, text=True, timeout=260)
        (state / (name + '.log')).write_text(reply.stdout + reply.stderr)
        require((reply.returncode == 0) == success, name + ': inspect CLI log')
        document = json.loads(reply.stdout)
        (state / (name + '.json')).write_text(json.dumps(document, indent=2))
        return document

    probe = ['java', '-cp', str(ROOT / 'target/onionall/lib/*'),
             'dev.onionall.brawler.tools.RuntimeProbe', 'frontier', 'localhost', '7071', str(token)]
    try:
        until = time.monotonic() + 25
        while time.monotonic() < until:
            require(process.poll() is None, 'Frontier exited before readiness')
            ready = subprocess.run(probe, env=env, capture_output=True, timeout=15)
            if ready.returncode == 0:
                break
            time.sleep(.25)
        else:
            raise RuntimeError('Authenticated Frontier readiness timed out')
        result['frontier'] = 'READY_AUTHENTICATED'
        plan = cli(['crawl', 'plan', '--seed', 'http://' + host + '/a', '--depth', '2',
                    '--max-pages', '20', '--scope', 'same-host'], 'plan')
        job = plan['plan_id']
        result['job_id'] = job
        start = ['crawl', 'start', '--plan', job, '--runtime', str(state / 'runtime.json'),
                 '--admission-token-file', str(token)]
        refused = cli(start, 'unapproved', success=False)
        require('approval' in json.dumps(refused).lower(), 'Approval failed for unrelated reason')
        require(cli(['crawl', 'approve', job], 'approve')['approved'], 'Plan not approved')
        cli(start, 'start')
        status = cli(['crawl', 'status', job, '--runtime', str(state / 'runtime.json'),
                      '--admission-token-file', str(token)], 'status')
        require(status['authoritative_readback_current'], 'No current Frontier readback')
        require(status['authoritative_admission']['reserved_count'] == 4, 'Unexpected admission quota')
        require(status['archived_pages'] == 3 and status['snapshots'] == 3, 'Missing actual archive records')
        require(any(row['category'] == 'FETCH_ERROR' and row['url'].endswith('/b')
                    for row in status['failure_records']), 'HTTP503 failure observation missing')
        require(any(row['category'] == 'ROBOTS_DENIED' for row in status['failure_records']),
                'Robots failure observation missing')
        rows = cli(['pages', '--job', job], 'pages')['pages']
        require({row['canonical_url']: row['depth'] for row in rows} ==
                {'http://' + host + path: depth for path, depth in (('/a', 0), ('/b', 1), ('/c', 2))},
                'Depth 0/1/2 archive mismatch')
        links = []
        for row in rows:
            page = cli(['page', 'show', row['page_id']], 'page-' + row['page_id'])
            snapshot = Path(page['storage_location'])
            require(snapshot.is_relative_to(state / 'archive') and snapshot.is_file(), 'Snapshot missing')
            require(hashlib.sha256(snapshot.read_bytes()).hexdigest() == page['content_hash'], 'HTML hash mismatch')
            require(page['source_kind'] == 'CRAWLER' and page['http_status'] == 200, 'Not actual crawl HTML')
            require(len(page['history']) == 1, 'Duplicate snapshot')
            links += page['links']
        require(any(edge['disposition'] == 'DEPTH_REJECTED' and edge['target_url'].endswith('/deep')
                    for edge in links), 'Missing depth-3 rejection')
        require(any(edge['disposition'] == 'SCOPE_REJECTED' and foreign in edge['target_url']
                    for edge in links), 'Missing foreign-host rejection')
        require(all(edge['job_id'] == job and edge['source_page_id'] for edge in links), 'Missing graph provenance')
        require(len(cli(['search-local', 'ARCHIVE_GAMMA', '--job', job], 'search')['results']) == 1, 'Local search failed')
        traffic = [json.loads(line) for line in (state / 'archive-requests.jsonl').read_text().splitlines()]
        http = [row for row in traffic if row['phase'] == 'http']
        require(Counter(row['path'] for row in http) == Counter({'/robots.txt': 1, '/a': 1, '/b': 2, '/c': 1}),
                'Unexpected HTTP traffic; depth/scope/robots/dedup failure')
        require([row['status'] for row in http if row['path'] == '/b'] == [503, 200], 'Retry not executed')
        config = json.loads((state / 'execution' / job / 'crawler-conf.json').read_text())['config']
        require(config['fetcher.threads.number'] <= 4 and config['fetcher.threads.per.queue'] == 1,
                'Concurrency ceiling violated')
        # Reopen persisted H2 through a separate CLI process after graceful Frontier shutdown.
        process.terminate()
        require(process.wait(timeout=20) == 143, 'Frontier shutdown exit mismatch')
        require(cli(['pages', '--job', job], 'pages-after-stop')['pages'] == rows, 'Archive lost on stop')
        result.update(passed=True, archived_pages=len(rows), snapshots=3, persisted_links=len(links),
                      frontier_reservations=4, depths=[0, 1, 2], scheduled_retry_verified=True,
                      robots_verified=True, scope_verified=True, data_retained=True,
                      concurrency_ceiling=4, effective_fetch_concurrency=1,
                      failure_observations=len(status['failure_records']), scheduler_counts=status['counts'],
                      job_state=status.get('job_state'), crawl_completed_verified=False)
    except Exception as error:
        result['error'] = type(error).__name__ + ': ' + str(error)
        raise
    finally:
        if process.poll() is None:
            process.terminate()
            process.wait(timeout=20)
        log.close()
        fixture.shutdown()
        fixture.server_close()
        (state / 'result.json').write_text(json.dumps(result, indent=2))
        print(json.dumps(result, indent=2), flush=True)


if __name__ == '__main__':
    main()
