"""Acceptance gate: every true field requires saved, concrete test evidence."""
import argparse
from collections import Counter
import json
from pathlib import Path
import re
import sys

FIELDS = ['real_tor_transport', 'controlled_onion_fetch', 'remote_onion_resolution',
          'no_direct_egress', 'proxy_bypass_blocked', 'fail_closed_verified',
          'robots_verified', 'crawl_delay_verified', 'frontier_persistence_verified',
          'm1_regression_passed']


def verify(path):
    result = dict(m2_verified=False, **{field: False for field in FIELDS}, evidence=[], blockers=[])
    if (path/'runner-state.json').exists():
        state=json.loads((path/'runner-state.json').read_text())
        result['evidence'].append('runner-state.json')
        if state.get('exit_code'):
            result['blockers'].append('Runner exited nonzero at line ' + str(state.get('failed_line')) + '; inspect docker-info.log and the stage logs')
    if (path/'tor-readiness.json').exists():
        readiness=json.loads((path/'tor-readiness.json').read_text())
        result['evidence'].append('tor-readiness.json')
        if not readiness.get('bootstrap_100_observed', readiness.get('bootstrap_complete', False)):
            result['blockers'].append('No bootstrap100 observed in logs: ' + str(readiness.get('observed_progress_percent')) + '; inspect services.log/startup.log')
    def load(name):
        return json.loads((path / name).read_text(encoding='utf-8-sig'))
    def check(condition, message):
        if not condition:
            raise ValueError(message)
    def stage(files, action):
        try:
            action()
            result['evidence'].extend(files)
        except (OSError, ValueError, KeyError, TypeError) as error:
            result['blockers'].append(str(error))
    def network():
        evidence = load('network.json')
        container=load('network-container-initial.json')[0]
        check(container['Config']['Labels']['com.docker.compose.service']=='crawler', 'Network probe did not execute actual crawler service')
        attachments=container['NetworkSettings']['Networks']
        check(len(attachments)==1 and next(iter(attachments)).endswith('_crawler'), 'Crawler has unexpected network attachment')
        check(container['HostConfig']['Dns']==['127.0.0.1'], 'Crawler external DNS policy changed')
        network=load('network-config-initial.json')[0]
        check(network['Internal'] is True and network['Name'] in attachments, 'Crawler network is not internal')
        check(evidence['passed'], 'Network probes failed')
        check(not evidence['direct_internet']['connected'] and not evidence['direct_fixture']['connected'], 'Direct bypass reachable')
        check(evidence['local_onion_dns'].get('completed') is True and evidence['local_onion_dns'].get('resolved') is False, 'Actual local onion DNS denial missing')
        check(not evidence['public_dns_tcp']['connected'] and not evidence['public_dns_udp']['response_received'], 'Public DNS reachable')
        check(evidence['onion_health']['socks_atyp'] == 3 and evidence['onion_health']['http_status'] == 200, 'Real onion SOCKS request missing')
        result.update(real_tor_transport=True, no_direct_egress=True, remote_onion_resolution=True)
    stage(['network.json','network-container-initial.json','network-config-initial.json'], network)
    def policy():
        evidence = load('policy.json')
        check(evidence['passed'] and len(evidence['checks']) == 4 and all(row['blocked'] for row in evidence['checks']), 'Proxy bypass probe failed')
        result['proxy_bypass_blocked'] = True
    stage(['policy.json'], policy)
    def crawl():
        host = (path / 'onion-hostname.txt').read_text().strip()
        base = 'http://' + host
        def rows(name):
            data = load(name)
            items = data['entries']
            check(len(items) == len({row['url'] for row in items}), 'Duplicate frontier rows')
            check(all(row['crawl'] == 'm2' and row['key'] == host for row in items), 'Wrong frontier crawl/key')
            return {row['url']: row for row in items}, data['captured_at_epoch']
        initial, first_time = rows('crawled.json')
        before, before_time = rows('before.json')
        after, _ = rows('after.json')
        final, final_time = rows('final.json')
        required = {base + name for name in ('/', '/a', '/b', '/missing', '/blocked', '/redirect', '/escape')}
        check(set(initial) == required, 'First crawl scope/discovery mismatch')
        check(all(row['kind'] == 'known' for row in initial.values()), 'First crawl incomplete')
        for name in ('/', '/a', '/b'):
            row = initial[base + name]
            check(row['refetch'] > first_time + 3600 and not row['metadata'].get('fetch.error.count'), 'Successful fetch not persisted')
        missing = initial[base + '/missing']
        check(missing['metadata'].get('fetch.error.count') == ['1'] and missing['refetch'] > first_time, '404 outcome not persisted')
        escaped = initial[base + '/escape']
        check(escaped['metadata'].get('fetch.error.count') == ['1'] and escaped['refetch'] > first_time, 'Rejected redirect not safely scheduled')
        check(initial[base + '/blocked']['refetch'] == 0, 'Robots denial not completed')
        pending = base + '/pending'
        check(set(before) == required | {pending} and before[pending]['metadata'] == {} and 0 < before[pending]['refetch'] <= before_time, 'Pending seed missing')
        check(all(before[url] == initial[url] for url in required), 'Completed state changed before recreation')
        check(before == after, 'Persistent state changed across recreation')
        check(set(final) == set(before) and all(final[url] == before[url] for url in required), 'Second crawl unexpectedly revisited state')
        check(final[pending]['refetch'] > final_time + 3600 and not final[pending]['metadata'].get('fetch.error.count'), 'Pending work did not resume')
        all_requests = [json.loads(line) for line in (path/'requests.jsonl').read_text().splitlines() if line]
        policy_requests = [row for row in all_requests if row['user_agent'] == 'OnionallBrawlerMTwoPolicy']
        check(all(row['path'] in {'/escape','/loop-a','/slow','/reset'} for row in policy_requests), 'Negative probe escaped its controlled paths')
        check(sum(row['path'] == '/loop-a' for row in policy_requests) == 1,
              'Redirect loop probe must produce exactly one /loop-a request')
        requests = [row for row in all_requests if row['user_agent'] not in {'OnionallBrawlerMTwoPolicy', 'OnionallBrawlerMTwoOutage'}]
        counts = Counter(row['path'] for row in requests)
        check(counts == Counter({'/robots.txt': 2, '/': 1, '/a': 1, '/b': 1, '/missing': 1, '/redirect': 1, '/escape': 1, '/pending': 1}), 'Missing, duplicate or out-of-scope fixture requests')
        phase = 0
        previous = None
        gaps = []
        for row in sorted(requests, key=lambda row: row['started_ns']):
            check(row['method'] == 'GET' and row['user_agent'].startswith('OnionallBrawlerMTwoTest'), 'Unexpected fixture client')
            check(row['status'] == (404 if row['path'] == '/missing' else 302 if row['path'] in ('/redirect', '/escape') else 200), 'Unexpected HTTP outcome')
            check(row['finished_ns'] >= row['started_ns'], 'Invalid fixture request timestamps')
            if row['path'] == '/robots.txt':
                phase += 1
                check(phase <= 2, 'Unexpected crawler robots phase')
                previous = None
                continue
            allowed = {'/', '/a', '/b', '/missing', '/redirect', '/escape'} if phase == 1 else {'/pending'} if phase == 2 else set()
            check(row['path'] in allowed, 'Document outside its robots/crawler phase')
            if previous:
                gap = (row['started_ns'] - previous['finished_ns']) / 1e9
                check(gap >= 1.8, f'Unsafe document gap {gap}')
                gaps.append(gap)
            previous = row
        check(phase == 2 and gaps, 'Missing crawler phases')
        for filename, markers in [('crawl-first.log', ['ROOT', 'A', 'B']), ('crawl-second.log', ['PENDING'])]:
            log = (path / filename).read_text()
            for marker in markers:
                check(re.search(r'(?m)^content(?:\t|:\s*)[^\n]*M2_' + marker + '_MARKER', log), 'Missing parsed content ' + marker)
            check('M2_FORBIDDEN_MARKER' not in log, 'Robots content leaked')
        check(load('crawl-runtime.json') == {'first_exit_code': 0, 'second_exit_code': 0}, 'Crawl process exits unavailable/nonzero')
        result.update(controlled_onion_fetch=True, robots_verified=True, crawl_delay_verified=True,
                      frontier_persistence_verified=True, frontier_records=len(final), minimum_observed_gap_seconds=min(gaps))
    stage(['crawled.json','before.json','after.json','final.json','requests.jsonl','crawl-runtime.json'], crawl)
    def outage():
        exits = load('scenario-exits.json')
        check(all(type(exits.get(key)) is int and exits[key] == 0
                  for key in ('scenario_exit_code', 'pipeline_exit_code')),
              'Scenario driver or outage pipeline exits unavailable/nonzero')
        evidence = load('network-outage.json')
        check(evidence['passed'] and evidence['outage'] and not evidence['tor_socks']['connected'], 'Stopped Tor did not fail closed')
        # Network-level failure alone does not establish all application outage scenarios.
        scenarios = load('security-scenarios.json')
        required = {'tor_stopped', 'socks_port_unavailable', 'invalid_socks_endpoint', 'bootstrap_incomplete',
                    'onion_unreachable', 'malformed_onion', 'metadata_bypass', 'out_of_scope_redirect',
                    'redirect_loop', 'timeout', 'connection_reset', 'tor_shutdown_active_crawl', 'safe_retry_after_tor_return'}
        check(required <= scenarios.keys() and all(scenarios[key].get('passed') is True and scenarios[key].get('evidence') for key in required), 'Security scenario matrix incomplete')
        def evidence_files(names):
            check(isinstance(names, list) and bool(names), 'Missing evidence references')
            for name in names:
                check(isinstance(name, str) and (path / name).resolve().is_relative_to(path.resolve()) and (path / name).is_file(), 'Missing or unsafe evidence reference: ' + str(name))
        for key in required:
            evidence_files(scenarios[key]['evidence'])
            if key != 'metadata_bypass':
                raw = load('scenario-' + key + '.json')
                check(raw.get('scenario') == key and raw.get('passed') is True, 'Scenario aggregate differs from raw result: ' + key)
        pipeline = load('outage-pipeline.json')
        check(all(pipeline.get(key) is True for key in ('passed', 'failed_fetch_persisted', 'scheduled_retry_recovered')), 'Actual crawler outage/retry pipeline incomplete')
        check(pipeline.get('outage_exit_code') == 0 and pipeline.get('recovered_exit_code') == 0, 'Outage/recovery crawler process exits unavailable/nonzero')
        evidence_files(pipeline.get('evidence'))
        host = (path / 'onion-hostname.txt').read_text().strip()
        url = 'http://' + host + '/outage'
        def entries(name):
            document = load(name)
            records = document['entries']
            check(len(records) == len({row['url'] for row in records}), 'Duplicate outage frontier rows')
            check(all(row['crawl'] == 'm2' and row['key'] == host for row in records), 'Wrong outage crawl/key')
            return {row['url']: row for row in records}, document['captured_at_epoch']
        original, _ = entries('final.json')
        failed, failed_at = entries('outage-failed.json')
        recovered, recovered_at = entries('outage-recovered.json')
        check(set(failed) == set(original) | {url} and set(recovered) == set(failed), 'Outage/retry changed frontier scope')
        check(all(failed[key] == value and recovered[key] == value for key, value in original.items()), 'Outage/retry changed completed frontier state')
        check(failed[url]['kind'] == 'known' and failed[url]['metadata'].get('fetch.error.count') == ['1'] and failed[url]['metadata'].get('fetch.exception'), 'Outage transport failure not persisted exactly once')
        check(isinstance(failed[url]['refetch'], int) and failed[url]['refetch'] > 0, 'Outage retry is not scheduled')
        check(recovered[url]['kind'] == 'known' and recovered[url]['refetch'] > recovered_at + 3600, 'Failed work did not recover to successful schedule')
        first_log = (path / 'crawl-outage.log').read_text()
        final_log = (path / 'crawl-recovered.log').read_text()
        check('M2_OUTAGE_MARKER' not in first_log and re.search(r'(?m)^content(?:\t|:\s*)[^\n]*M2_OUTAGE_MARKER', final_log), 'Outage recovery parsed-content evidence missing/contradictory')
        traffic = [json.loads(line) for line in (path / 'requests.jsonl').read_text().splitlines() if line]
        traffic = [row for row in traffic if row['user_agent'] == 'OnionallBrawlerMTwoOutage']
        check(Counter(row['path'] for row in traffic) == Counter({'/robots.txt': 2, '/outage': 2}), 'Outage pipeline retried uncontrolled URLs or missed requests')
        ordered = sorted(traffic, key=lambda row: row['started_ns'])
        check([row['path'] for row in ordered] == ['/robots.txt', '/outage', '/robots.txt', '/outage'], 'Outage documents requested before robots')
        check(all(row['method'] == 'GET' and row['status'] == 200 and row['finished_ns'] >= row['started_ns'] for row in traffic), 'Unexpected outage fixture HTTP outcome')
        result['fail_closed_verified'] = True
    stage(['scenario-exits.json','network-outage.json','security-scenarios.json','outage-pipeline.json','outage-failed.json','outage-recovered.json'], outage)
    def regression():
        evidence = load('m1-regression.json')
        check(evidence['exit_code'] == 0, 'M1 regression failed')
        m1 = Path(evidence['evidence_directory'])
        check(m1.is_dir(), 'M1 evidence directory unavailable')
        verification = json.loads((m1/'verification.json').read_text())
        cleanup = json.loads((m1/'result.json').read_text())
        check(all(verification.get(key) is True for key in ('assertions_passed','crawl_evidence_passed','runtime_verified')) and cleanup.get('containers_removed') is True, 'M1 regression evidence incomplete')
        result['m1_regression_passed'] = True
    stage(['m1-regression.json'], regression)
    def cleanup():
        evidence = load('cleanup.json')
        check(evidence['containers_removed'] is True and evidence['network_removed'] is True, 'Cleanup incomplete')
        volumes = evidence.get('retained_volumes')
        check(isinstance(volumes, dict) and
              all(volumes.get(name) is True for name in ('frontier-data', 'tor-state', 'onion-state')),
              'Cleanup retention of frontier-data, tor-state and onion-state not verified')
    stage(['cleanup.json'], cleanup)
    result['m2_verified'] = all(result[field] for field in FIELDS) and not result['blockers']
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('run_dir', type=Path)
    args = parser.parse_args()
    result = verify(args.run_dir.resolve())
    (args.run_dir/'verification.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result, indent=2))
    raise SystemExit(0 if result['m2_verified'] else 1)
