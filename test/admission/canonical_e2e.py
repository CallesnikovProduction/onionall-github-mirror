"""Durable research session over internal SOCKS, with export and recreation readback.

Only an empty canonical fixture archive is accepted. No fabricated catalog rows,
no direct scheduler mutations, no public network and no volume deletion.
@author Стефан Колесников
@author GPT-6 Astra
"""
from collections import Counter
import hashlib
import json
import re
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
STATE = ROOT / '.runtime/local-fixture'
EVIDENCE = ROOT / '.runtime/canonical-e2e'


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def command(arguments, name, success=True, timeout=60):
    result = subprocess.run([str(ROOT / 'onionall'), '--fixture', *arguments], cwd=ROOT,
                            capture_output=True, text=True, timeout=timeout)
    # Private raw evidence stays ignored; the CI upload contains only result.json.
    (EVIDENCE / (name + '.log')).write_text(result.stdout + result.stderr, encoding='utf-8')
    require((result.returncode == 0) == success, name + ': unexpected command exit')
    return result.stdout


def cli(arguments, name, **options):
    return json.loads(command(['cli', *arguments], name, **options))


def container_ids(service=None):
    args = ['docker', 'ps', '-q', '--filter', 'label=com.docker.compose.project=onionall-runtime-fixture']
    if service is not None:
        args += ['--filter', 'label=com.docker.compose.service=' + service]
    return subprocess.run(args, capture_output=True, text=True, check=True, timeout=15).stdout.split()


def container_id(service):
    ids = container_ids(service)
    require(len(ids) == 1, 'Expected exactly one owned ' + service)
    return ids[0]


def read_archive(job, host, suffix):
    rows = cli(['pages', '--job', job], 'pages-' + suffix)['pages']
    expected = {'http://' + host + path: depth for path, depth in (('/a', 0), ('/b', 1), ('/c', 2))}
    require({r['canonical_url']: r['depth'] for r in rows} == expected, 'Depth 0/1/2 archive mismatch')
    require(len(rows) == 3, 'Duplicate archived page')
    details, edges = [], []
    for row in rows:
        page = cli(['page', 'show', row['page_id']], 'page-' + suffix + '-' + row['page_id'])
        require(page['job_id'] == job and page['source_kind'] == 'CRAWLER'
                and page['http_status'] == 200 and page['fetched_at'], 'Missing actual fetch provenance')
        require(len(page['history']) == 1 and 'ARCHIVE_' in page['text'], 'Duplicate snapshot or missing text')
        location = Path(page['storage_location'])
        require(location.parent == Path('/state/archive/objects'), 'Unexpected object location')
        body = (STATE / 'archive/objects' / location.name).read_bytes()
        require(hashlib.sha256(body).hexdigest() == page['content_hash']
                and len(body) == page['content_size'] and b'ARCHIVE_' in body, 'Invalid stored HTML object')
        for edge in page['links']:
            require(edge['job_id'] == job and edge['source_page_id'] == page['page_id']
                    and edge['source_url'] == page['canonical_url'] and edge['source_host'] == host
                    and edge['discovered_at'], 'Missing graph provenance')
            edges.append(edge)
        details.append(page)
    require(any(e['source_url'].endswith('/a') and e['target_url'].endswith('/b')
                and e['target_page_id'] for e in edges), 'Missing resolved A -> B edge')
    require(any(e['source_url'].endswith('/b') and e['target_url'].endswith('/c')
                and e['target_page_id'] for e in edges), 'Missing resolved B -> C edge')
    for path, disposition in (('/deep', 'DEPTH_REJECTED'), ('/escape', 'SCOPE_REJECTED')):
        require(any(e['target_url'].endswith(path) and e['disposition'] == disposition
                    and e['target_page_id'] is None for e in edges), 'Missing ' + disposition)
    require(any(e['target_url'].endswith('/dump.zip') and e['target_page_id'] is None
                for e in edges), 'File candidate provenance absent')
    require(len(cli(['search-local', 'ARCHIVE_GAMMA', '--job', job], 'search-' + suffix)['results']) == 1,
            'Local archive search failed')
    research = cli(['research', 'results', job], 'research-' + suffix)
    require(research['pages'] == rows and research['status']['authoritative_readback_current'],
            'Research result does not reflect current persisted data')
    require(len(research['file_candidates']) == 1 and research['binary_downloads'] == 0,
            'File-link candidate missing or binary download claimed')
    require(research['metrics']['listed_html_bytes'] == sum(row['content_size'] for row in rows)
            and research['metrics']['fetch_attempts'] is None, 'Research metrics are not actual observations')
    require(cli(['research', 'links', job], 'links-' + suffix)['links'] == research['links'],
            'Public graph readback disagrees with research results')
    local = cli(['research', 'search', '--query', 'http://' + host + '/c', '--job', job, '--host', host],
                'research-search-' + suffix)
    require(len(local['results']) == 1 and local['results'][0]['depth'] == 2 and local['network_requests'] == 0,
            'Research URL/job/host search failed')
    return dict(pages=rows, details=details, edges=edges)


def read_session(session, job, archive, suffix):
    observation = cli(['research', 'status', session], 'session-' + suffix)
    saved = observation['session']
    require(observation['session_id'] == session and saved['session_id'] == session
            and saved['approved'] and saved['approved_at'], 'Lost approved research session')
    seeds = saved['seeds']
    require(len(seeds) == 1 and seeds[0]['job_id'] == job and seeds[0]['execution_state'] == 'ATTEMPTED'
            and seeds[0]['last_worker_exit_code'] == 0, 'Missing persisted child execution')
    require(observation['scheduler_readback_current'] and len(observation['per_seed']) == 1
            and observation['per_seed'][0]['job_id'] == job
            and observation['per_seed'][0]['status']['authoritative_readback_current'],
            'Missing authenticated research scheduler readback')
    require(observation['pages'] == archive['pages'] and observation['links'] == archive['edges']
            and observation['acquisition_status'] == 'DATA_ACQUIRED', 'Session lost archived page or graph evidence')
    require(observation['metrics']['seeds_attempted'] == 1 and observation['metrics']['completed'] is None
            and observation['session_status'] != 'COMPLETED', 'Session manufactured completion')
    return {key: saved[key] for key in
            ('approved_at', 'depth', 'pages_per_seed', 'global_page_budget', 'candidates', 'seeds')}


def export_payload(session, suffix, fmt, filename, count):
    destination = 'research-export-' + suffix + '-' + fmt
    summary = cli(['research', 'export', session, '--format', fmt, '--output', '/state/' + destination],
                  'export-' + suffix + '-' + fmt)
    require(summary['session_id'] == session and summary['format'] == fmt
            and summary['pages_exported'] == count and summary['network_requests'] == 0
            and summary['output_file'] == '/state/' + destination + '/' + filename,
            'Research export summary differs from the requested scope')
    payload = (STATE / destination / filename).read_bytes()
    require(summary['bytes'] == len(payload) and summary['sha256'] == hashlib.sha256(payload).hexdigest(),
            'Research export bytes differ from their reported integrity')
    return payload


def verify_json_export(document, session, details):
    require(document['session_id'] == session and document['export_version'] == 1,
            'Export has wrong research identity or format')
    expected = {page['page_id']: page for page in details}
    rows = document['pages']
    require(len(rows) == len(expected) and {row['page_id'] for row in rows} == set(expected),
            'Export omitted or duplicated archived pages')
    for row in rows:
        original = expected[row['page_id']]
        require(row['session_id'] == session and row['job_id'] == original['job_id']
                and row['source_url'] == original['canonical_url']
                and row['fetched_at'] == original['fetched_at'], 'Export lost wrapper provenance')
        page = row['page']
        require(all(page[key] == original[key] for key in
                    ('page_id', 'job_id', 'canonical_url', 'snapshot_id', 'content_hash',
                     'source_kind', 'fetched_at', 'http_status')), 'Export lost archive snapshot provenance')


def verify_exports(session, archive, suffix):
    details = archive['details']
    payload = export_payload(session, suffix, 'json', 'research.json', len(details))
    verify_json_export(json.loads(payload), session, details)

    markdown = export_payload(session, suffix, 'markdown', 'research.md', len(details)).decode('utf-8')
    # Fixture URLs and text markers contain only this subset of escaped punctuation.
    visible = re.sub(r'\\([:/._])', r'\1', markdown)
    require(all(page['canonical_url'] in visible for page in details)
            and all('ARCHIVE_' + marker in visible for marker in ('ALPHA', 'BETA', 'GAMMA')),
            'Markdown export omitted source URLs or captured content')


def request_records():
    return [json.loads(line) for line in (STATE / 'archive-requests.jsonl').read_text().splitlines()]


def verify_traffic(records):
    http = [row for row in records if row['phase'] == 'http']
    require(Counter(row['path'] for row in http) == Counter(
        {'/robots.txt': 1, '/a': 1, '/b': 2, '/c': 1, '/redirect': 1}), 'Unexpected HTTP traffic')
    require([r['status'] for r in http if r['path'] == '/b'] == [503, 200], 'Retry was not observed')
    require([r['status'] for r in http if r['path'] == '/redirect'] == [302], 'Redirect not fetched')
    require(all(row['atyp'] == 3 for row in http), 'Remote name resolution contract violated')
    return http


def run():
    EVIDENCE.mkdir(parents=True, exist_ok=False)
    result = dict(passed=False, transport='INTERNAL_SOCKS_HTTP_FIXTURE', real_tor='NOT_RUN',
                  crawl_completed_verified=False)
    try:
        require(not (STATE / 'archive').exists() and not (STATE / 'execution').exists(),
                'Refusing existing archive/execution; use a fresh checkout, never delete user state')
        proxy = cli(['config', 'proxy', 'set', '--type', 'socks5', '--host', 'socks-fixture', '--port', '9050'], 'proxy-set')
        require(proxy['host'] == 'socks-fixture' and proxy['direct_fallback'] is False, 'Wrong configured proxy')
        doctor = cli(['system', 'doctor'], 'doctor')
        require(doctor['socks5_negotiation'] == 'READY' and doctor['tor_bootstrap'] == 'NOT_CHECKED',
                'Configured proxy not checked honestly')
        host = (STATE / 'onion-hostname.txt').read_text().strip()
        seed_url = 'http://' + host + '/a'
        imported = cli(['research', 'import', '--seed', seed_url, '--label', 'Canonical synthetic research'],
                       'research-import')
        session = imported['session_id']
        result['research_session_id'] = session
        candidates = imported['candidates']
        require(imported['provider'] == 'manual' and imported['network_requests'] == 0
                and len(candidates) == 1 and candidates[0]['canonical_url'] == seed_url
                and not candidates[0]['selected'] and not imported['approved'],
                'Imported candidate was not an unapproved local seed')
        selected = cli(['research', 'select', session, '--all-valid'], 'research-select')
        require(len(selected['candidates']) == 1 and selected['candidates'][0]['selected']
                and not selected['approved'], 'Selection did not preserve explicit approval')
        plan = cli(['research', 'plan', '--session', session, '--depth', '2', '--pages-per-seed', '20',
                    '--global-pages', '20'], 'research-plan')
        require(plan['depth'] == 2 and plan['pages_per_seed'] == 20 and plan['global_page_budget'] == 20
                and len(plan['seeds']) == 1 and plan['seeds'][0]['url'] == seed_url,
                'Stored research scope differs from fixture budget')
        job = plan['seeds'][0]['job_id']
        result['job_id'] = job
        run_session = ['research', 'run', '--session', session, '--total-seconds', '360',
                       '--slice-seconds', '120', '--request-timeout-ms', '2000']
        refused = cli(run_session, 'unapproved', success=False)
        require('approval' in json.dumps(refused).lower(), 'Unapproved start refused for unrelated cause')
        require(not cli(['crawl', 'show', job], 'unapproved-plan')['approved']
                and not (STATE / 'execution' / job).exists(), 'Unapproved start changed child execution state')
        approved = cli(['research', 'approve', session], 'research-approve')
        require(approved['approved'] and approved['approved_at']
                and cli(['crawl', 'show', job], 'approved-plan')['approved'], 'Explicit approval not persisted')
        cli(run_session, 'start', timeout=360)
        start = json.loads((STATE / 'execution' / job / 'execution.json').read_text())
        require(start['admission'] == 'COMMITTED' and start['worker_exit_code'] == 0,
                'Admission/worker execution failed')
        require(start['crawl_completed_verified'] is False, 'Worker exit manufactured completion')
        status = cli(['crawl', 'status', job], 'status')
        require(status['authoritative_readback_current'], 'No current Frontier readback')
        require(status['authoritative_admission']['reserved_count'] == 5, 'Unexpected durable quota')
        require(status['counts']['pending'] == 0 and status['counts']['held'] == 0,
                'Due work/leases remain after bounded crawl')
        require(status['completion'] != 'COMPLETE', 'Unknown terminal provenance reported as complete')
        for category in ('FETCH_ERROR', 'ROBOTS_DENIED', 'REDIRECTION'):
            require(any(r['category'] == category for r in status['failure_records']), 'Missing ' + category)
        before = read_archive(job, host, 'before')
        before_session = read_session(session, job, before, 'before')
        verify_exports(session, before, 'before')
        http = verify_traffic(request_records())
        config = json.loads((STATE / 'execution' / job / 'crawler-conf.json').read_text())['config']
        require(config['fetcher.threads.number'] <= 4 and config['fetcher.threads.per.queue'] == 1,
                'Concurrency ceiling exceeded')
        ids = {name: container_id(name) for name in ('frontier', 'socks-fixture')}
        command(['down'], 'down', timeout=180)
        require(not container_ids(), 'Fixture containers remained active after stop')
        command(['up'], 'recreated-up', timeout=200)
        require(all(container_id(name) != old for name, old in ids.items()), 'Containers were not recreated')
        require(cli(['config', 'proxy', 'show'], 'proxy-restored')['host'] == proxy['host'], 'Proxy setting lost')
        after = read_archive(job, host, 'after')
        require(before == after, 'Archive or graph changed after container recreation')
        after_session = read_session(session, job, after, 'after')
        require(before_session == after_session, 'Research approval, candidates or child scope changed')
        verify_exports(session, after, 'after')
        require(verify_traffic(request_records()) == http, 'Restart and readback caused extra HTTP')
        restored = cli(['crawl', 'status', job], 'restored-status')
        require(restored['authoritative_readback_current'], 'Restored Frontier unavailable')
        require(restored['authoritative_admission'] == status['authoritative_admission'], 'Admission changed')
        require(restored['counts'] == status['counts'], 'Frontier scheduling counts changed')
        require(restored['next_attempt_at'] == status['next_attempt_at'], 'Retry/refetch date changed')
        result.update(passed=True, archived_pages=3, depths=[0, 1, 2], graph_edges=len(before['edges']),
                      frontier_reservations=5, http_requests=len(http), retry_statuses=[503, 200],
                      redirect_status=302, archive_readback=True, containers_recreated=True,
                      frontier_persistence=True, archive_persistence=True, graph_persistence=True,
                      research_input='MANUAL_SYNTHETIC_SEED', provider_discovery='NOT_RUN',
                      research_session_persistence=True, research_exports_verified=['json', 'markdown'],
                      no_fixture_containers_after_stop=True, no_http_on_restart_readback=True,
                      job_state=restored['job_state'], completion=restored['completion'],
                      scheduler_counts=restored['counts'], effective_concurrency=1)
    except Exception as error:
        result['blocker'] = type(error).__name__ + ': ' + str(error)
    finally:
        # Только имена исключений: исходные Storm-логи могут содержать capability.
        exceptions = Counter()
        for log in (STATE / 'execution').glob('*/worker.log'):
            exceptions.update(re.findall(r'\b[\w.$]+(?:Exception|Error)\b',
                                        log.read_text(encoding='utf-8', errors='replace')[-262144:]))
        result['worker_exception_types'] = dict(exceptions.most_common(12))
        (EVIDENCE / 'result.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
        print(json.dumps(result, indent=2), flush=True)
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    sys.exit(run())
