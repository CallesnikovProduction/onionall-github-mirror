"""M6.1 actual forum pipeline in a private loopback-only network namespace.

Uses real packaged CLI, authenticated owning Frontier, generated Storm worker and
immutable archive. Synthetic SOCKS never forwards; raw logs remain private. The
current one-scope worker is measured and reported as a blocked concurrency gate.
Pause by creating <state>/pause.requested, or SIGINT the exact harness PID only.
The current bounded CLI operation finishes first (a batch can contain multiple
workers); this does not cancel the product or protect children from group signals.

@author Стефан Колесников
@author GPT-6.1 Sol
"""
import argparse
import hashlib
import json
import os
import re
from html import unescape
from pathlib import Path
import secrets
import signal
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
from m61_forum_fixture import Fixture


def require(value, message):
    if not value:
        raise AssertionError(message)


def literal_markdown_text(value):
    """Decode literal ASCII escapes/entities for source identity checks, without rendering HTML."""
    return unescape(re.sub(r'\\([!"#$%&\'()*+,\-./:;<=>?@\[\]^_`{|}~\\])', r'\1', value))


class FixturePause(Exception):
    """Cooperative harness pause, not a claim of product cancellation."""


class PauseControl:
    def __init__(self, state):
        self.marker = state / 'pause.requested'
        self.requested = False

    def request(self, *_):
        # Raising KeyboardInterrupt here would make subprocess.run kill its CLI.
        self.requested = True

    def check(self):
        if self.requested or self.marker.exists():
            raise FixturePause('Pause requested between bounded CLI calls')


class NativeRun:
    """Owns exactly one fresh Frontier process and its retained private evidence."""
    def __init__(self, libs, patched):
        root = ROOT / '.runtime/m61'
        root.mkdir(parents=True, exist_ok=True)
        self.state = Path(tempfile.mkdtemp(prefix='forum-', dir=root))
        self.state.chmod(0o700)
        self.pause = PauseControl(self.state)
        self.last_completed_cli = None
        self.hosts = [fixture_host(n) for n in (21, 22)]
        self.token = self.state / 'admission-token'
        self.token.write_text(secrets.token_hex(32)); self.token.chmod(0o600)
        self.application = ROOT / 'target/onionall-brawler-0.1.0-SNAPSHOT.jar'
        self.server = ROOT / 'frontier-server/target/urlfrontier-admission.jar'
        self.process = None
        self.log = (self.state / 'frontier.log').open('w')
        adapter = self.state / 'storm'
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
        self.env = dict(os.environ, ONIONALL_ADMISSION_TOKEN=self.token.read_text(),
                        FIXTURE_STORM_CP=f'{patched}:{libs}/lib/*:{libs}/lib-common/*')
        self.descriptor = self.state / 'runtime.json'
        self.descriptor.write_text(json.dumps(dict(frontier_host='localhost', frontier_port=7071,
            socks_host='127.0.0.1', socks_port=19050, storm_executable=str(adapter),
            crawler_jar=str(self.application), work_directory=str(self.state / 'execution'), seconds=90,
            http_timeout_ms=2000, retry_minutes=1)))
        self.fixture = Fixture(('127.0.0.1', 19050), self.state, self.hosts, slow_seconds=.25)
        self.fixture_thread = threading.Thread(target=self.fixture.serve_forever, daemon=True)
        self.fixture_thread.start()

    def cli(self, args, name, success=True, timeout=60):
        self.pause.check()
        response = subprocess.run([str(ROOT / 'target/onionall/bin/onionall'), '--json',
            '--archive-dir', str(self.state / 'archive'), *args], cwd=ROOT, env=self.env,
            capture_output=True, text=True, timeout=timeout)
        (self.state / (name + '.log')).write_text(response.stdout + response.stderr)
        require((response.returncode == 0) == success, name + ': inspect private CLI log')
        document = json.loads(response.stdout)
        (self.state / (name + '.json')).write_text(json.dumps(document, indent=2))
        self.last_completed_cli = name
        self.pause.check()
        return document

    def start_frontier(self):
        self.pause.check()
        require(self.process is None or self.process.poll() is not None, 'Frontier already owned')
        self.process = subprocess.Popen(['java', '--enable-native-access=ALL-UNNAMED', '-cp', str(self.server),
            'crawlercommons.urlfrontier.service.URLFrontierServer', 'rocksdb.path=' + str(self.state / 'rocksdb')],
            env=self.env, stdout=self.log, stderr=subprocess.STDOUT)
        probe = ['java', '-cp', str(ROOT / 'target/onionall/lib/*'),
            'dev.onionall.brawler.tools.RuntimeProbe', 'frontier', 'localhost', '7071', str(self.token)]
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline:
            self.pause.check()
            require(self.process.poll() is None, 'Frontier exited before readiness')
            if subprocess.run(probe, env=self.env, capture_output=True, timeout=15).returncode == 0:
                return
            time.sleep(.25)
        raise RuntimeError('Authenticated Frontier readiness timed out')

    def stop_frontier(self):
        if self.process is not None and self.process.poll() is None:
            self.process.terminate()
            require(self.process.wait(timeout=20) == 143, 'Frontier stop mismatch')

    def status(self, session, name):
        return self.cli(['research', 'status', session, '--runtime', str(self.descriptor),
                         '--admission-token-file', str(self.token)], name)

    def crawl(self, session, name, thread=False):
        args = ['research', 'thread', 'crawl', session] if thread else ['research', 'run', '--session', session]
        return self.cli(args + ['--runtime', str(self.descriptor), '--admission-token-file', str(self.token),
            '--total-seconds', '900', '--slice-seconds', '90', '--request-timeout-ms', '2000'], name, timeout=1200)

    def verify_objects(self, pages, suffix):
        for row in pages:
            page = self.cli(['page', 'show', row['page_id']], suffix + '-' + row['page_id'])
            require(page['source_kind'] == 'CRAWLER' and page['http_status'] == 200 and page['fetched_at'],
                    'Missing real archive fetch provenance')
            path = Path(page['storage_location'])
            require(path.parent == self.state / 'archive/objects', 'Unexpected HTML object location')
            data = path.read_bytes()
            require(hashlib.sha256(data).hexdigest() == page['content_hash'] and len(data) == page['content_size'],
                    'Immutable HTML object readback mismatch')
            require(b'CONTROLLED_' in data, 'Unexpected fixture archive content')

    def traffic(self):
        return [json.loads(line) for line in (self.state / 'requests.jsonl').read_text().splitlines()]

    def close(self):
        self.stop_frontier()
        self.log.close()
        self.fixture.shutdown(); self.fixture_thread.join(timeout=5); self.fixture.server_close()


def verify_site(result, hosts):
    pages, links = result['pages'], result['links']
    require(result['session']['depth'] == 3, 'Ordinary site navigation depth not stored')
    require(result['metrics']['seeds_attempted'] == 2 and result['metrics']['completed'] is None,
            'Site execution or conservative completion mismatch')
    expected = {
        hosts[0]: {'/': 0, '/forum/': 1, '/forum/security/': 2, '/thread?id=123&page=1': 3},
        hosts[1]: {'/': 0, '/categories/': 1, '/topics/': 2, '/thread/alternative/': 3}}
    for host, paths in expected.items():
        actual = {}
        for row in pages:
            url = urlsplit(row['canonical_url'])
            if url.hostname == host:
                actual[url.path + ('?' + url.query if url.query else '')] = row['depth']
        require(actual == paths, 'Homepage/category/list/topic depth chain mismatch')
        require(any(urlsplit(e['source_url']).hostname == host and e['disposition'] == 'SCOPE_REJECTED'
                    and urlsplit(e['target_url']).hostname in hosts for e in links), 'Cross-origin scope rejection absent')
        require(any(urlsplit(e['source_url']).hostname == host and e['disposition'] == 'DEPTH_REJECTED'
                    for e in links), 'Site depth-four pagination rejection absent')
    require(len(pages) == 8 and all(e['source_page_id'] and e['job_id'] for e in links), 'Site graph provenance mismatch')
    require(result['session_status'] != 'COMPLETED', 'Site manufactured completion')
    counts = [row['status']['counts'] for row in result['per_seed']]
    require(all(row['status']['authoritative_readback_current'] for row in result['per_seed']) and
            all(count['reserved'] <= 20 for count in counts) and sum(count['reserved'] for count in counts) <= 40,
            'Actual Frontier per-seed/global reservation cap exceeded')


def verify_thread(result, host, layout):
    pages, links = result['pages'], result['links']
    prefix = 'http://' + host
    expected = ([prefix + '/thread?id=123&page=' + str(n) for n in (1, 2, 3)] if layout == 'A' else
                [prefix + '/thread/alternative/', prefix + '/thread/alternative/page/2/', prefix + '/thread/alternative/page/3/'])
    require({p['canonical_url']: p['depth'] for p in pages} == {url: depth for depth, url in enumerate(expected)},
            'Thread pagination pages/depths mismatch')
    require(result['session']['mode'] == 'THREAD' and result['session']['thread_max_pages'] == 5,
            'Explicit thread budget/scope missing')
    require(result['metrics']['completed'] is None and result['session_status'] != 'COMPLETED',
            'Thread manufactured scheduler completion')
    threads = result['threads']
    require(len(threads) == 1, 'Expected exactly one captured discussion')
    discussion = threads[0]
    posts = discussion['posts']
    require(len(discussion['captured_pages']) == 3 and len(posts) == (4 if layout == 'A' else 5),
            'Repeated post deduplication or captured page grouping failed')
    require(discussion['full_thread_coverage_verified'] is False and
            discussion['completeness_status'] == 'OBSERVED_PAGINATION_CAPTURED', 'Observed pagination coverage mismatch')
    require(discussion['extraction_status'] == 'STRUCTURED_POSTS', 'Structured extraction failed')
    require(all(post['post_id'] and post['source_page_id'] and post['snapshot_id'] and post['provenance']
                and post['source_kind'] == 'CRAWLER' for post in posts), 'Post archive/snapshot provenance absent')
    require(len({post['post_id'] for post in posts}) == len(posts), 'Internal post IDs collided')
    require(any(len(post['provenance']) == 2 for post in posts), 'Repeated source post lost both page observations')
    require(any(edge['disposition'] == 'THREAD_REJECTED' for edge in links), 'Non-thread navigation not rejected')
    files = result['file_candidates']
    require(len(files) == 2 and all(file.get('content_length') is None for file in files), 'Attachment metadata mismatch')
    require(all(file['source_post_observation_ids'] for file in files), 'Attachment/post association missing')
    by_id = {post['source_post_id']: post for post in posts}
    if layout == 'A':
        require(set(by_id) == {'p101', 'p102', 'p103', 'p104'}, 'Traditional source IDs changed')
        require(by_id['p101']['author'] == 'Alice' and by_id['p101']['posted_at'] == '2026-11-13T10:00:00Z',
                'Observed author/date extraction mismatch')
        require(by_id['p103']['quoted_text'] and 'Alice said' not in by_id['p103']['text'], 'Quote separation failed')
        require({file['extension'].lower().lstrip('.') for file in files} == {'pdf', 'md'}, 'Traditional attachment types missing')
        require(any('download=1&lang=en' in file['target_url'] for file in files), 'HTML query entity incorrectly escaped')
        require(all('CONTROLLED_A_POST_' + str(number) in by_id['p' + str(number)]['text']
                    for number in (101, 102, 103, 104)), 'Traditional post/source marker association changed')
    else:
        require(set(by_id) == {'comment-201', 'comment-202', 'comment-203', 'comment-204', None}, 'Semantic source IDs changed')
        require(by_id['comment-202']['author'] is None and by_id['comment-202']['posted_at'] is None and
                by_id[None]['author'] is None, 'Missing source author/date/ID were fabricated')
        require(any(quote['depth'] > 0 for quote in by_id['comment-203']['quotes']), 'Nested quote evidence missing')
        require({file['extension'].lower().lstrip('.') for file in files} == {'epub', 'txt'}, 'Semantic attachment types missing')
        require(all('CONTROLLED_B_POST_' + str(number) in by_id['comment-' + str(number)]['text']
                    for number in (201, 202, 203, 204)) and 'CONTROLLED_B_POST_IDLESS' in by_id[None]['text'],
                'Semantic post/source marker association changed')
    return discussion


def verify_budget(result, host):
    expected = ['http://' + host + '/thread?id=123&page=' + str(n) for n in (1, 2)]
    require({p['canonical_url']: p['depth'] for p in result['pages']} ==
            {url: depth for depth, url in enumerate(expected)}, 'Two-page cap archived unexpected pages/depths')
    require(result['session']['thread_max_pages'] == 2 and result['session']['global_page_budget'] == 2,
            'Independent two-page budget missing')
    require(result['session_status'] == 'PAUSED' and result['metrics']['completed'] is None,
            'Budget exhaustion manufactured completion')
    require(result['per_seed'][0]['status']['authoritative_readback_current'] and
            result['per_seed'][0]['status']['counts']['reserved'] == 2, 'Frontier reservation budget not reached')
    require(len(result['threads']) == 1, 'Budget case lost captured discussion')
    discussion = result['threads'][0]
    require(discussion['completeness_status'] == 'PARTIAL_OR_UNKNOWN' and
            not discussion['full_thread_coverage_verified'], 'Budget case claims complete discussion')
    missing = 'http://' + host + '/thread?id=123&page=3'
    require(any(edge['target_url'] == missing and not edge['captured'] and
                edge['disposition'] in {'DEPTH_REJECTED', 'LIMIT_REJECTED'}
                for edge in discussion['observed_pagination']), 'Missing page lacks authoritative budget/depth rejection')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--storm-libs', type=Path, required=True)
    parser.add_argument('--patched-server', type=Path, required=True)
    args = parser.parse_args()
    require({name for _, name in socket.if_nameindex()} == {'lo'}, 'Isolated loopback-only namespace required')
    require(os.readlink('/proc/self/ns/net') != os.environ['FIXTURE_PARENT_NETNS'], 'Host network forbidden')
    libs, patched = args.storm_libs.resolve(), args.patched_server.resolve()
    require((libs / 'lib-common/storm-client-3.1.0.jar').is_file() and patched.is_file(), 'Storm inputs missing')
    require(all(path.is_file() for path in (ROOT / 'target/onionall-brawler-0.1.0-SNAPSHOT.jar',
            ROOT / 'frontier-server/target/urlfrontier-admission.jar', ROOT / 'target/onionall/bin/onionall')),
            'Packaged application/Frontier inputs missing')
    run = NativeRun(libs, patched)
    previous_interrupt = signal.signal(signal.SIGINT, run.pause.request)
    print(run.state, flush=True)
    summary = dict(passed=False, evidence=str(run.state), transport='INTERNAL_TWO_HOST_FORUM_SOCKS_FIXTURE',
        real_tor='NOT_RUN', live_discovery='NOT_RUN', container_recreation='NOT_RUN',
        concurrency_gate='BLOCKED_SINGLE_SCOPE_WORKER', cross_host_parallelism_verified=False,
        scheduler_completion_verified=False)
    summary['artifacts'] = {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
                            for path in (run.application, run.server)}
    sessions = []
    try:
        run.start_frontier()
        seeds = ['http://' + host + '/' for host in run.hosts]
        imported = run.cli(['research', 'import', '--label', 'M6.1 internal forum fixture',
            *[arg for seed in seeds for arg in ('--seed', seed)]], 'site-import')
        session = imported['session_id']
        require(imported['provider'] == 'manual' and imported['network_requests'] == 0, 'Manual seeds mislabeled live discovery')
        run.cli(['research', 'select', session, '--all-valid'], 'site-select')
        plan = run.cli(['research', 'plan', '--session', session, '--depth', '3', '--pages-per-seed', '20',
                        '--global-pages', '40'], 'site-plan')
        require(len(plan['seeds']) == 2 and not plan['approved'] and plan['global_page_budget'] == 40, 'Site plan budget/approval mismatch')
        run.cli(['research', 'approve', session], 'site-approve')
        run.crawl(session, 'site-run')
        site = run.status(session, 'site-status')
        verify_site(site, run.hosts)
        run.verify_objects(site['pages'], 'site-object-before')
        sessions.append((session, site))
        for index, layout in enumerate(('A', 'B')):
            url = 'http://' + run.hosts[index] + ('/thread?page=1&id=123' if layout == 'A' else '/thread/alternative/')
            plan = run.cli(['research', 'thread', 'plan', url, '--max-pages', '5'], 'thread-' + layout + '-plan')
            identity = plan['session_id']
            require(not plan['approved'] and len(plan['seeds']) == 1, 'Thread plan bypassed approval')
            run.cli(['research', 'approve', identity], 'thread-' + layout + '-approve')
            run.crawl(identity, 'thread-' + layout + '-crawl', thread=True)
            observed = run.status(identity, 'thread-' + layout + '-status')
            discussion = verify_thread(observed, run.hosts[index], layout)
            shown = run.cli(['research', 'thread', 'show', identity], 'thread-' + layout + '-show')
            require(shown['threads'] == observed['threads'], 'Thread show changed persisted extraction')
            run.verify_objects(observed['pages'], 'thread-' + layout + '-object-before')
            output = run.state / ('export-' + layout)
            run.cli(['research', 'export', identity, '--format', 'markdown', '--output', str(output)], 'thread-' + layout + '-export')
            markdown = (output / 'research.md').read_text()
            visible = literal_markdown_text(markdown)
            require(all(post['text'].split()[0] in visible for post in discussion['posts']), 'Export loses actual post text')
            require(all(page['url'] in visible for page in discussion['captured_pages']), 'Export loses page sources')
            require(all(post['author'] in visible for post in discussion['posts'] if post['author']), 'Export loses observed authors')
            require('![probe](' not in markdown, 'Untrusted text became an active Markdown image')
            sessions.append((identity, observed))
        events = run.traffic()
        http = [event for event in events if event['phase'] == 'http']
        require(all(event['atyp'] == 3 for event in http), 'SOCKS target DNS was not remote')
        require(not any(event['phase'] in {'socks_rejected', 'handler_end'} for event in events), 'Unexpected or forbidden HTTP destination attempted')
        retry = [event['status'] for event in http if event['host'] == run.hosts[0] and event['path'] == '/thread?id=123&page=2']
        require(retry == [503, 200], 'Real Frontier scheduled retry absent or duplicate identity fetched')
        metrics = run.fixture.metrics()
        require(metrics['max_global_in_flight'] == 1 and metrics['active_remaining'] == 0 and
                all(value == 1 for value in metrics['max_per_host'].values()), 'Configured one-worker in-flight limit violated')
        require(all(event['path'] not in {'/blocked', '/escape', '/book.epub', '/manual.pdf', '/notes.txt', '/notes.md'} for event in http),
                'Robots/scope/metadata-only document boundary violated')
        budget_start = len(events)
        budget_plan = run.cli(['research', 'thread', 'plan', 'http://' + run.hosts[0] + '/thread?id=123&page=1',
                               '--max-pages', '2'], 'budget-plan')
        budget_id = budget_plan['session_id']
        require(not budget_plan['approved'], 'Budget case implicitly approved')
        run.cli(['research', 'approve', budget_id], 'budget-approve')
        run.crawl(budget_id, 'budget-crawl', thread=True)
        budget = run.status(budget_id, 'budget-status')
        verify_budget(budget, run.hosts[0])
        run.verify_objects(budget['pages'], 'budget-object-before')
        sessions.append((budget_id, budget))
        events = run.traffic()
        require(not any(event.get('phase') == 'http' and event.get('path') == '/thread?id=123&page=3'
                        for event in events[budget_start:]), 'Budget case fetched rejected third page')
        http = [event for event in events if event['phase'] == 'http']
        metrics = run.fixture.metrics()
        require(metrics['max_global_in_flight'] == 1 and metrics['active_remaining'] == 0 and
                all(value == 1 for value in metrics['max_per_host'].values()), 'Budget case violated in-flight limits')
        run.stop_frontier(); run.start_frontier()
        for number, (identity, before) in enumerate(sessions):
            after = run.status(identity, 'restart-status-' + str(number))
            for field in ('pages', 'links', 'file_candidates', 'threads'):
                require(after[field] == before[field], 'Archive/graph/thread/file readback changed after Frontier restart: ' + field)
            for field in ('seeds', 'candidates', 'approved', 'approved_at'):
                require(after['session'][field] == before['session'][field], 'Stored selection/approval/child identity changed: ' + field)
            if number:
                shown = run.cli(['research', 'thread', 'show', identity], 'restart-thread-' + str(number))
                require(shown['threads'] == before['threads'], 'Thread show lost posts after restart')
            run.verify_objects(after['pages'], 'restart-object-' + str(number))
        run.pause.check()
        summary.update(passed=True, site_pages=len(site['pages']), thread_pages=6, unique_posts=9,
            thread_sessions=2, budget_case_pages=2, budget_case_reserved=2, budget_case_session_id=budget_id,
            budget_exhaustion_verified=True, budget_case_completion='NOT_COMPLETED',
            site_depths=[0, 1, 2, 3], thread_depths=[0, 1, 2],
            file_candidates=4, file_types=['pdf', 'epub', 'txt', 'md'], binary_downloads=0,
            controlled_retry=[503, 200], measured_concurrency=metrics, http_requests=len(http),
            frontier_process_restarted=True, html_hash_readback=True, graph_persistence=True,
            thread_post_persistence=True, candidate_approval_persistence=True, markdown_export=True,
            session_ids=[identity for identity, _ in sessions])
    except FixturePause:
        summary.update(status='PAUSED_BY_OWNER_NOT_COMPLETE', pause_requested=True,
            pause_mechanism='COOPERATIVE_BETWEEN_BOUNDED_CLI_CALLS',
            last_completed_cli=run.last_completed_cli, product_cancellation_verified=False,
            session_ids=[identity for identity, _ in sessions])
    except Exception as error:
        summary['error_type'] = type(error).__name__
        summary['error'] = str(error) if isinstance(error, (AssertionError, RuntimeError)) else 'Inspect private evidence'
        raise
    finally:
        try:
            run.close()
        except Exception as cleanup_error:
            summary.update(passed=False, cleanup_error_type=type(cleanup_error).__name__)
            raise
        finally:
            signal.signal(signal.SIGINT, previous_interrupt)
            (run.state / 'result.json').write_text(json.dumps(summary, indent=2) + '\n')
            print(json.dumps(summary, indent=2), flush=True)
    return 3 if summary.get('pause_requested') else 0


if __name__ == '__main__':
    sys.exit(main())
