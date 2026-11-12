"""M6 three-seed research through real CLI/Admission/Frontier/Storm in loopback-only netns.

Synthetic destinations never forward externally. No catalog injection, fake worker
outcome, volume removal or Tor acceptance claim. All raw logs stay private.

@author Стефан Колесников
@author GPT-6.1 Sol
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
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts/runners'))
from runtime import fixture_host
from m6_socks_fixture import Fixture


def require(value, message):
    if not value:
        raise AssertionError(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--storm-libs', type=Path, required=True)
    parser.add_argument('--patched-server', type=Path, required=True)
    args = parser.parse_args()
    require({name for _, name in socket.if_nameindex()} == {'lo'}, 'Isolated loopback-only namespace required')
    require(os.readlink('/proc/self/ns/net') != os.environ['FIXTURE_PARENT_NETNS'], 'Host network forbidden')
    libs, patched = args.storm_libs.resolve(), args.patched_server.resolve()
    require((libs / 'lib-common/storm-client-3.1.0.jar').is_file() and patched.is_file(), 'Storm inputs missing')
    state_root = ROOT / '.runtime/m6'
    state_root.mkdir(parents=True, exist_ok=True)
    state = Path(tempfile.mkdtemp(prefix='multi-', dir=state_root))
    os.chmod(state, 0o700)
    print(state, flush=True)
    result = dict(passed=False, evidence=str(state), transport='INTERNAL_THREE_HOST_SOCKS_FIXTURE',
                  real_tor='NOT_RUN', live_provider_discovery='NOT_RUN', container_recreation='NOT_RUN',
                  crawl_completed_verified=False)
    hosts = [fixture_host(number) for number in (11, 12, 13)]
    token = state / 'admission-token'
    token.write_text(secrets.token_hex(32)); token.chmod(0o600)
    application = ROOT / 'target/onionall-brawler-0.1.0-SNAPSHOT.jar'
    server = ROOT / 'frontier-server/target/urlfrontier-admission.jar'
    result['artifacts'] = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                           for p in (application, server)}
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
    descriptor = state / 'runtime.json'
    descriptor.write_text(json.dumps(dict(frontier_host='localhost', frontier_port=7071,
        socks_host='127.0.0.1', socks_port=19050, storm_executable=str(adapter),
        crawler_jar=str(application), work_directory=str(state / 'execution'), seconds=90,
        http_timeout_ms=2000, retry_minutes=1)))
    fixture = Fixture(('127.0.0.1', 19050), state, hosts)
    threading.Thread(target=fixture.serve_forever, daemon=True).start()
    log = (state / 'frontier.log').open('w')
    process = None

    def cli(arguments, name, success=True, timeout=60):
        reply = subprocess.run([str(ROOT / 'target/onionall/bin/onionall'), '--json',
                                '--archive-dir', str(state / 'archive'), *arguments],
                               cwd=ROOT, env=env, capture_output=True, text=True, timeout=timeout)
        (state / (name + '.log')).write_text(reply.stdout + reply.stderr)
        require((reply.returncode == 0) == success, name + ': inspect private CLI log')
        document = json.loads(reply.stdout)
        (state / (name + '.json')).write_text(json.dumps(document, indent=2))
        return document

    def start_frontier():
        nonlocal process
        process = subprocess.Popen(['java', '--enable-native-access=ALL-UNNAMED', '-cp', str(server),
            'crawlercommons.urlfrontier.service.URLFrontierServer', 'rocksdb.path=' + str(state / 'rocksdb')],
            env=env, stdout=log, stderr=subprocess.STDOUT)
        probe = ['java', '-cp', str(ROOT / 'target/onionall/lib/*'),
            'dev.onionall.brawler.tools.RuntimeProbe', 'frontier', 'localhost', '7071', str(token)]
        until = time.monotonic() + 25
        while time.monotonic() < until:
            require(process.poll() is None, 'Frontier exited before readiness')
            if subprocess.run(probe, env=env, capture_output=True, timeout=15).returncode == 0:
                return
            time.sleep(.25)
        raise RuntimeError('Authenticated Frontier readiness timed out')

    def status(session, name):
        return cli(['research', 'status', session, '--runtime', str(descriptor),
                    '--admission-token-file', str(token)], name)

    def verify_objects(pages, suffix):
        for row in pages:
            page = cli(['page', 'show', row['page_id']], suffix + '-' + row['page_id'])
            require(page['source_kind'] == 'CRAWLER' and page['http_status'] == 200 and page['fetched_at'],
                    'Missing genuine fetch provenance')
            path = Path(page['storage_location'])
            require(path.parent == state / 'archive/objects', 'Unexpected HTML object location')
            body = path.read_bytes()
            require(hashlib.sha256(body).hexdigest() == page['content_hash'] and len(body) == page['content_size'],
                    'HTML object readback mismatch')
            require(b'M6_HOST_' in body, 'Unexpected fixture content')

    try:
        start_frontier(); result['frontier'] = 'READY_AUTHENTICATED'
        seeds = ['http://' + host + '/thread/1' for host in hosts]
        imported = cli(['research', 'import', '--label', 'M6 fixture',
                        *[arg for seed in seeds for arg in ('--seed', seed)]], 'import')
        session = imported['session_id']; result['session_id'] = session
        require(imported['provider'] == 'manual' and imported['network_requests'] == 0,
                'Manual input mislabeled as live discovery')
        candidates = cli(['research', 'candidates', session], 'candidates')
        require(len(candidates['candidates']) == 3 and not any(c['selected'] for c in candidates['candidates']),
                'Imported candidates were automatically approved')
        selected = cli(['research', 'select', session, '--all-valid'], 'select')
        require(sum(c['selected'] for c in selected['candidates']) == 3, 'Batch selection failed')
        cli(['research', 'plan', '--session', session, '--depth', '2', '--pages-per-seed', '5',
             '--global-pages', '14'], 'too-small-global-budget', success=False)
        plan = cli(['research', 'plan', '--session', session, '--depth', '2', '--pages-per-seed', '5',
                    '--global-pages', '15'], 'plan')
        require(plan['global_page_budget'] == 15 and plan['pages_per_seed'] == 5 and len(plan['seeds']) == 3,
                'Separate per-seed/global budget not persisted')
        run = ['research', 'run', '--session', session, '--runtime', str(descriptor),
               '--admission-token-file', str(token), '--total-seconds', '600', '--slice-seconds', '90',
               '--request-timeout-ms', '2000']
        cli(run, 'unapproved', success=False)
        approved = cli(['research', 'approve', session], 'approve')
        require(approved['approved'] and approved['approved_at'], 'Explicit batch approval not persisted')
        cli(run, 'run', timeout=900)
        before = status(session, 'status')
        pages, links = before['pages'], before['links']
        require(len(pages) == 9, 'Expected three actual HTML pages per approved host')
        require(Counter(urlsplit(p['canonical_url']).hostname for p in pages) == Counter({h: 3 for h in hosts}),
                'Per-host archive distribution mismatch')
        for host in hosts:
            require({p['canonical_url']: p['depth'] for p in pages if urlsplit(p['canonical_url']).hostname == host}
                    == {'http://' + host + '/thread/' + str(depth + 1): depth for depth in range(3)},
                    'Depth relative to each approved seed is incorrect')
        require(before['metrics']['seeds_attempted'] == 3 and before['metrics']['completed'] is None,
                'Session status manufactures completion')
        require(before['session_status'] != 'COMPLETED', 'Incomplete Frontier work reported completed')
        per_host = before['per_host']
        require(len(per_host) == 3 and all(p['status']['authoritative_readback_current'] for p in per_host),
                'Missing authenticated per-child scheduler observations')
        counts = [p['status']['counts'] for p in per_host]
        require(all(c['reserved'] == 5 for c in counts) and sum(c['reserved'] for c in counts) == 15,
                'Per-seed or global reservation cap violated')
        require(all(c['pending'] == 0 and c['held'] == 0 for c in counts), 'Due work or held leases remain')
        require(any(e['disposition'] == 'DEPTH_REJECTED' and e['target_url'].endswith('/thread/4') for e in links),
                'Depth-three rejection provenance absent')
        for host in hosts:
            require(any(urlsplit(e['source_url']).hostname == host and e['disposition'] == 'SCOPE_REJECTED'
                        and urlsplit(e['target_url']).hostname in hosts for e in links),
                    'Already selected host bypassed a child origin scope')
        require(all(e['source_page_id'] and e['job_id'] and e['discovered_at'] for e in links), 'Graph provenance absent')
        files = cli(['research', 'files', session], 'files')
        file_rows = files['file_candidates']
        require(len(file_rows) == 9, 'Expected observed PDF/EPUB/TXT links from each host')
        require({f['extension'].lower().lstrip('.') for f in file_rows} == {'pdf', 'epub', 'txt'}, 'Missing file types')
        require(all(f.get('content_length') is None for f in file_rows), 'Fabricated file response size')
        verify_objects(pages, 'page-before-restart')
        output = state / 'export'
        export = cli(['research', 'export', session, '--format', 'markdown', '--output', str(output)], 'export')
        text_files = list(output.rglob('*.md'))
        require(len(text_files) == 1, 'Markdown export output missing')
        text = text_files[0].read_text()
        require(all('M6_HOST_' + str(n) + '_PAGE_' + str(p) in text for n in (1, 2, 3) for p in (1, 2, 3)),
                'Captured discussion text absent from export')
        require(all(seed in text for seed in seeds), 'Export loses source URL boundaries')
        traffic = [json.loads(line) for line in (state / 'requests.jsonl').read_text().splitlines()]
        http = [r for r in traffic if r['phase'] == 'http']
        for host in hosts:
            expected = Counter({'/robots.txt': 1, '/thread/1': 1, '/thread/2': 2 if host == hosts[1] else 1,
                                '/thread/3': 1, '/redirect': 1})
            require(Counter(r['path'] for r in http if r['host'] == host) == expected,
                    'Unexpected HTTP traffic: scope/depth/robots/binary/dedup violated')
        require([r['status'] for r in http if r['host'] == hosts[1] and r['path'] == '/thread/2'] == [503, 200],
                'Actual scheduled retry not observed')
        require(all(r['atyp'] == 3 for r in http), 'Onion names were not resolved remotely')
        process.terminate(); require(process.wait(timeout=20) == 143, 'Frontier stop mismatch')
        start_frontier()
        restored_candidates = cli(['research', 'candidates', session], 'candidates-after-restart')
        require(restored_candidates['approved'] and restored_candidates['candidates'] == before['session']['candidates'],
                'Candidate selection/approval lost on restart')
        after = status(session, 'status-after-restart')
        require(after['pages'] == pages and after['links'] == links and after['file_candidates'] == file_rows,
                'Research archive/graph/files lost on restart')
        require(after['session']['seeds'] == before['session']['seeds'], 'Child job provenance changed')
        for old, new in zip(per_host, after['per_host']):
            for key in ('authoritative_admission', 'counts', 'next_attempt_at'):
                require(old['status'][key] == new['status'][key], 'Durable Frontier state changed: ' + key)
        verify_objects(after['pages'], 'page-after-restart')
        result.update(passed=True, selected_seeds=3, approved_seeds=3, archived_pages=9,
            pages_per_host={host: 3 for host in hosts}, depths=[0, 1, 2], persisted_links=len(links),
            file_candidates=len(file_rows), file_types=['pdf', 'epub', 'txt'], binary_downloads=0,
            frontier_reservations=15, per_seed_cap=5, global_page_cap=15, http_requests=len(http),
            actual_retry_statuses=[503, 200], redirect_status=302, remote_dns_verified=True,
            forum_page_text_export=True, archive_readback=True, frontier_process_restarted=True,
            candidate_persistence=True, approval_persistence=True, graph_persistence=True,
            file_candidate_persistence=True, html_hashes_after_restart=True,
            session_status=after['session_status'], effective_concurrency=1)
    except Exception as failure:
        result['error_type'] = type(failure).__name__
        result['error'] = str(failure) if isinstance(failure, (AssertionError, RuntimeError)) else 'Inspect private evidence'
        raise
    finally:
        if process is not None and process.poll() is None:
            process.terminate(); process.wait(timeout=20)
        log.close(); fixture.shutdown(); fixture.server_close()
        (state / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(result, indent=2), flush=True)


if __name__ == '__main__':
    main()
