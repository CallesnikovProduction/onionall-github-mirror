"""Canonical CLI, real HTTP over internal SOCKS, then container recreation.

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


def container_id(service):
    result = subprocess.run(['docker', 'ps', '-q', '--filter',
                             'label=com.docker.compose.project=onionall-runtime-fixture',
                             '--filter', 'label=com.docker.compose.service=' + service],
                            capture_output=True, text=True, check=True, timeout=15)
    ids = result.stdout.split()
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
    return dict(pages=rows, details=details, edges=edges)


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
        host = (STATE / 'onion-hostname.txt').read_text().strip()
        plan = cli(['research', 'plan', '--url', 'http://' + host + '/a', '--depth', '2',
                    '--max-pages', '20'], 'plan')
        job = plan['plan_id']
        result['job_id'] = job
        refused = cli(['research', 'run', '--plan', job], 'unapproved', success=False)
        require('approval' in json.dumps(refused).lower(), 'Unapproved start refused for unrelated cause')
        start = cli(['research', 'run', '--plan', job, '--approve'], 'start', timeout=260)
        require(cli(['crawl', 'show', job], 'approved-plan')['approved'], 'Explicit approval not persisted')
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
        traffic = [json.loads(line) for line in (STATE / 'archive-requests.jsonl').read_text().splitlines()]
        http = verify_traffic(traffic)
        config = json.loads((STATE / 'execution' / job / 'crawler-conf.json').read_text())['config']
        require(config['fetcher.threads.number'] <= 4 and config['fetcher.threads.per.queue'] == 1,
                'Concurrency ceiling exceeded')
        ids = {name: container_id(name) for name in ('frontier', 'socks-fixture')}
        command(['down'], 'down', timeout=180)
        command(['up'], 'recreated-up', timeout=200)
        require(all(container_id(name) != old for name, old in ids.items()), 'Containers were not recreated')
        after = read_archive(job, host, 'after')
        require(before == after, 'Archive or graph changed after container recreation')
        restored = cli(['crawl', 'status', job], 'restored-status')
        require(restored['authoritative_readback_current'], 'Restored Frontier unavailable')
        require(restored['authoritative_admission'] == status['authoritative_admission'], 'Admission changed')
        require(restored['counts'] == status['counts'], 'Frontier scheduling counts changed')
        require(restored['next_attempt_at'] == status['next_attempt_at'], 'Retry/refetch date changed')
        result.update(passed=True, archived_pages=3, depths=[0, 1, 2], graph_edges=len(before['edges']),
                      frontier_reservations=5, http_requests=len(http), retry_statuses=[503, 200],
                      redirect_status=302, archive_readback=True, containers_recreated=True,
                      frontier_persistence=True, archive_persistence=True, graph_persistence=True,
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
