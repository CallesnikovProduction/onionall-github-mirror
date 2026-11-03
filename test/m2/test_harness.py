"""Verifier regressions use synthetic evidence; they do not establish Tor acceptance."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('m2_verifier', Path(__file__).with_name('verify.py'))
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class VerificationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.path = Path(self.temporary.name)
        self.host = 'a' * 56 + '.onion'
        self.base = 'http://' + self.host
        (self.path / 'onion-hostname.txt').write_text(self.host)
        def row(name):
            return dict(url=self.base + name, crawl='m2', key=self.host, kind='known',
                        metadata={'fetch.error.count': ['1']} if name in {'/missing', '/escape'} else {},
                        refetch=0 if name in {'/blocked', '/redirect'} else 86400)
        self.initial = dict(entries=[row(name) for name in ['/', '/a', '/b', '/missing', '/blocked', '/redirect', '/escape']], captured_at_epoch=100)
        self.before = copy.deepcopy(self.initial)
        self.before['entries'].append(dict(url=self.base + '/pending', crawl='m2', key=self.host, kind='known', metadata={}, refetch=100))
        self.final = copy.deepcopy(self.before)
        self.final['entries'][-1]['refetch'] = 86400
        self.write('crawled.json', self.initial)
        self.write('before.json', self.before)
        self.write('after.json', self.before)
        self.write('final.json', self.final)
        self.write('crawl-runtime.json', dict(first_exit_code=0, second_exit_code=0))
        self.requests = []
        for index, name in enumerate(['/robots.txt', '/', '/a', '/b', '/missing', '/redirect', '/escape', '/robots.txt', '/pending']):
            self.requests.append(dict(path=name, method='GET', status=404 if name == '/missing' else 302 if name in {'/redirect', '/escape'} else 200,
                                      user_agent='OnionallBrawlerMTwoTest', started_ns=index * 3_000_000_000, finished_ns=index * 3_000_000_000 + 100))
        # The separate policy probe must return the loop redirect exactly once.
        self.requests.append(dict(path='/loop-a', user_agent='OnionallBrawlerMTwoPolicy'))
        self.write_requests()
        (self.path / 'crawl-first.log').write_text('\n'.join('content\tM2_' + item + '_MARKER' for item in ['ROOT', 'A', 'B']))
        (self.path / 'crawl-second.log').write_text('content\tM2_PENDING_MARKER')

    def write(self, name, document):
        (self.path / name).write_text(json.dumps(document))

    def write_requests(self):
        (self.path / 'requests.jsonl').write_text('\n'.join(json.dumps(row) for row in self.requests))

    def test_missing_and_duplicate_loop_probe_both_fail_without_claiming_a_repeat(self):
        baseline = [row for row in self.requests if row['path'] != '/loop-a']
        for count in (0, 2):
            with self.subTest(count=count):
                self.requests = baseline + [dict(path='/loop-a', user_agent='OnionallBrawlerMTwoPolicy')] * count
                self.write_requests()
                result = self.result()
                self.assertTrue(result['controlled_onion_fetch'])
                self.assertFalse(result['m2_verified'])
                self.assertEqual('FAILED', result['stages']['policy_traffic']['status'])
                self.assertEqual('PASS', result['stages']['crawl']['status'])
                self.assertIn('Redirect loop probe must produce exactly one /loop-a request', result['blockers'])

    def test_missing_evidence_is_distinguished_from_failed_assertion(self):
        result = self.result()
        self.assertEqual('EVIDENCE_MISSING', result['stages']['network']['status'])
        (self.path / 'after.json').unlink()
        result = self.result()
        self.assertEqual('EVIDENCE_MISSING', result['stages']['crawl']['status'])
        self.assertFalse(result['controlled_onion_fetch'])
        self.assertFalse(result['m2_verified'])

    def test_policy_scope_failure_remains_mandatory_when_crawl_passes(self):
        self.requests.append(dict(path='/uncontrolled', user_agent='OnionallBrawlerMTwoPolicy'))
        self.write_requests()
        result = self.result()
        self.assertTrue(result['controlled_onion_fetch'])
        self.assertEqual('FAILED', result['stages']['policy_traffic']['status'])
        self.assertIn('Negative probe escaped its controlled paths', result['blockers'])
        self.assertFalse(result['m2_verified'])

    def test_complete_synthetic_full_gate_still_rejects_missing_policy_traffic(self):
        # A complete synthetic baseline isolates the newly separated predicate:
        # the rejection must not depend on unrelated missing evidence.
        self.outage_evidence()
        self.write('network.json', dict(passed=True, direct_internet={'connected': False},
            direct_fixture={'connected': False}, local_onion_dns={'completed': True, 'resolved': False},
            public_dns_tcp={'connected': False}, public_dns_udp={'response_received': False},
            onion_health={'socks_atyp': 3, 'http_status': 200}))
        self.write('network-container-initial.json', [dict(Config={'Labels': {'com.docker.compose.service': 'crawler'}},
            HostConfig={'Dns': ['127.0.0.1']}, NetworkSettings={'Networks': {'owned_crawler': {}}})])
        self.write('network-config-initial.json', [dict(Internal=True, Name='owned_crawler')])
        self.write('cleanup.json', self.cleanup_evidence())
        m1 = self.path / 'm1'
        m1.mkdir()
        (m1 / 'verification.json').write_text(json.dumps(dict(assertions_passed=True, crawl_evidence_passed=True, runtime_verified=True)))
        (m1 / 'result.json').write_text(json.dumps(dict(containers_removed=True)))
        self.write('m1-regression.json', dict(exit_code=0, evidence_directory=str(m1)))
        baseline = self.result()
        self.assertTrue(baseline['m2_verified'], baseline['blockers'])
        self.requests = [row for row in self.requests if row['path'] != '/loop-a']
        self.write_requests()
        failed = self.result()
        self.assertTrue(all(failed[field] for field in verifier.FIELDS))
        self.assertFalse(failed['m2_verified'])
        self.assertEqual(['Redirect loop probe must produce exactly one /loop-a request'], failed['blockers'])

    def test_malformed_evidence_is_distinct_from_missing_and_failed(self):
        (self.path / 'after.json').write_text('{')
        result = self.result()
        self.assertEqual('EVIDENCE_INVALID', result['stages']['crawl']['status'])
        self.assertFalse(result['m2_verified'])

    def result(self):
        return verifier.verify(self.path)

    def outage_evidence(self):
        self.write('scenario-exits.json', dict(scenario_exit_code=0, pipeline_exit_code=0))
        self.write('network-outage.json', dict(passed=True, outage=True, tor_socks={'connected': False}))
        names = ['tor_stopped', 'socks_port_unavailable', 'invalid_socks_endpoint', 'bootstrap_incomplete',
                 'onion_unreachable', 'malformed_onion', 'out_of_scope_redirect', 'redirect_loop', 'timeout',
                 'connection_reset', 'tor_shutdown_active_crawl', 'safe_retry_after_tor_return']
        scenarios = {}
        for name in names:
            filename = 'scenario-' + name + '.json'
            self.write(filename, dict(scenario=name, passed=True))
            scenarios[name] = dict(passed=True, evidence=[filename])
        self.write('policy.json', dict(passed=True, checks=[{'blocked': True}] * 4))
        scenarios['metadata_bypass'] = dict(passed=True, evidence=['policy.json'])
        self.write('security-scenarios.json', scenarios)
        failed = copy.deepcopy(self.final)
        failed['entries'].append(dict(url=self.base + '/outage', crawl='m2', key=self.host, kind='known', refetch=200,
                                      metadata={'fetch.error.count': ['1'], 'fetch.exception': ['SOCKS_FAILURE']}))
        recovered = copy.deepcopy(failed)
        recovered['entries'][-1]['refetch'] = 86400
        self.write('outage-failed.json', failed)
        self.write('outage-recovered.json', recovered)
        self.write('outage-pipeline.json', dict(passed=True, failed_fetch_persisted=True, scheduled_retry_recovered=True,
                                               outage_exit_code=0, recovered_exit_code=0, evidence=['outage-failed.json', 'outage-recovered.json']))
        (self.path / 'crawl-outage.log').write_text('controlled fetch failed')
        (self.path / 'crawl-recovered.log').write_text('content\tM2_OUTAGE_MARKER')
        for index, name in enumerate(['/robots.txt', '/outage', '/robots.txt', '/outage']):
            self.requests.append(dict(path=name, method='GET', status=200, user_agent='OnionallBrawlerMTwoOutage',
                                      started_ns=(index + 20) * 3_000_000_000, finished_ns=(index + 20) * 3_000_000_000 + 100))
        self.write_requests()

    def test_valid_synthetic_crawl_contract_is_recognized_without_claiming_m2(self):
        result = self.result()
        self.assertTrue(result['controlled_onion_fetch'])
        self.assertFalse(result['m2_verified'])

    def test_state_changed_before_recreation_is_rejected(self):
        mutated = copy.deepcopy(self.before)
        mutated['entries'][0]['metadata']['unexpected'] = ['changed']
        self.write('before.json', mutated)
        self.write('after.json', mutated)
        final = copy.deepcopy(mutated)
        final['entries'][-1]['refetch'] = 86400
        self.write('final.json', final)
        self.assertFalse(self.result()['controlled_onion_fetch'])

    def test_document_before_first_robots_is_rejected(self):
        self.requests[1]['started_ns'] = -1000
        self.requests[1]['finished_ns'] = -100
        self.write_requests()
        self.assertFalse(self.result()['controlled_onion_fetch'])

    def test_pending_document_in_wrong_crawl_phase_is_rejected(self):
        self.requests[8]['started_ns'] = 19_000_000_000
        self.requests[8]['finished_ns'] = 19_000_000_100
        self.write_requests()
        self.assertFalse(self.result()['controlled_onion_fetch'])

    def test_inverted_request_times_are_rejected(self):
        self.requests[3]['finished_ns'] = self.requests[3]['started_ns'] - 1
        self.write_requests()
        self.assertFalse(self.result()['controlled_onion_fetch'])

    def test_raw_outage_and_retry_are_required_separately_from_protocol_probes(self):
        self.outage_evidence()
        self.assertTrue(self.result()['fail_closed_verified'])
        (self.path / 'outage-pipeline.json').unlink()
        result = self.result()
        self.assertFalse(result['fail_closed_verified'])
        self.assertFalse(result['m2_verified'])

    def test_scenario_aggregate_cannot_replace_missing_raw_result(self):
        self.outage_evidence()
        (self.path / 'scenario-timeout.json').unlink()
        self.assertFalse(self.result()['fail_closed_verified'])

    def test_scenario_aggregate_cannot_override_failed_raw_result(self):
        self.outage_evidence()
        self.write('scenario-timeout.json', dict(scenario='timeout', passed=False))
        self.assertFalse(self.result()['fail_closed_verified'])

    def test_outage_failure_metadata_must_exist(self):
        self.outage_evidence()
        failed = json.loads((self.path / 'outage-failed.json').read_text())
        failed['entries'][-1]['metadata'].pop('fetch.exception')
        self.write('outage-failed.json', failed)
        self.assertFalse(self.result()['fail_closed_verified'])

    def test_outage_recovery_must_process_failed_work(self):
        self.outage_evidence()
        failed = json.loads((self.path / 'outage-failed.json').read_text())
        self.write('outage-recovered.json', failed)
        self.assertFalse(self.result()['fail_closed_verified'])

    def test_outage_retry_cannot_change_completed_corpus(self):
        self.outage_evidence()
        recovered = json.loads((self.path / 'outage-recovered.json').read_text())
        recovered['entries'][0]['refetch'] += 1
        self.write('outage-recovered.json', recovered)
        self.assertFalse(self.result()['fail_closed_verified'])

    def test_outage_nonzero_process_exit_is_not_success(self):
        self.outage_evidence()
        pipeline = json.loads((self.path / 'outage-pipeline.json').read_text())
        pipeline['recovered_exit_code'] = 1
        self.write('outage-pipeline.json', pipeline)
        self.assertFalse(self.result()['fail_closed_verified'])

    def test_outage_retry_cannot_fetch_uncontrolled_paths(self):
        self.outage_evidence()
        self.requests.append(dict(path='/a', method='GET', status=200, user_agent='OnionallBrawlerMTwoOutage', started_ns=100, finished_ns=101))
        self.write_requests()
        self.assertFalse(self.result()['fail_closed_verified'])


    def test_missing_scenario_driver_exit_file_cannot_pass_fail_closed(self):
        self.outage_evidence()
        (self.path / 'scenario-exits.json').unlink()
        self.assertFalse(self.result()['fail_closed_verified'])

    def test_replacement_requires_complete_journal_and_real_http(self):
        self.outage_evidence()
        journal = dict(passed=True, state='recovered', replacement_limit=1, automatic_direct_fallback=False,
            events=[{'state': state} for state in ['quarantining', 'quarantined', 'replacing',
                'verifying', 'resuming', 'recovered']])
        health = dict(passed=True, health=dict(http_status=200, health_body='ready', socks_atyp=3,
            remote_hostname_sent=True))
        self.write('recovery-series.json', dict(passed=True, completed=['/robots.txt','/health']*3))
        self.write('gateway-recovery.json', journal)
        self.write('recovery-health.json', health)
        self.requests += [dict(path='/robots.txt', method='GET', status=200, user_agent='OnionallBrawlerMTwoRecovery')] * 4
        self.write_requests()
        self.assertTrue(self.result()['fail_closed_verified'])
        valid_requests = list(self.requests)
        for count in (0, 3, 5):
            self.requests = [row for row in valid_requests if row['user_agent'] != 'OnionallBrawlerMTwoRecovery'] + [dict(path='/robots.txt', method='GET', status=200, user_agent='OnionallBrawlerMTwoRecovery')] * count
            self.write_requests()
            self.assertFalse(self.result()['fail_closed_verified'])
        self.requests = valid_requests
        self.write_requests()
        for key, value in [('passed', False), ('socks_atyp', 1), ('health_body', 'wrong')]:
            bad = copy.deepcopy(health)
            if key == 'passed':
                bad[key] = value
            else:
                bad['health'][key] = value
            self.write('recovery-health.json', bad)
            self.assertFalse(self.result()['fail_closed_verified'])
        self.write('recovery-health.json', health)
        journal['state'] = 'blocked'
        self.write('recovery-series.json', dict(passed=True, completed=['/robots.txt','/health']*3))
        self.write('gateway-recovery.json', journal)
        self.assertFalse(self.result()['fail_closed_verified'])

    def test_each_missing_nonzero_or_boolean_driver_exit_is_rejected(self):
        self.outage_evidence()
        for key in ('scenario_exit_code', 'pipeline_exit_code'):
            for value in (None, 1, -1, False, True, '0'):
                with self.subTest(key=key, value=value):
                    exits = dict(scenario_exit_code=0, pipeline_exit_code=0)
                    if value is None:
                        exits.pop(key)
                    else:
                        exits[key] = value
                    self.write('scenario-exits.json', exits)
                    result = self.result()
                    self.assertFalse(result['fail_closed_verified'])
                    self.assertIn('Scenario driver or outage pipeline exits unavailable/nonzero', result['blockers'])

    def cleanup_evidence(self):
        return dict(containers_removed=True, network_removed=True, volume_retained=True,
                    retained_volumes={'frontier-data': True, 'tor-state': True, 'onion-state': True})

    def test_cleanup_retains_all_three_volumes(self):
        self.write('cleanup.json', self.cleanup_evidence())
        result = self.result()
        self.assertIn('cleanup.json', result['evidence'])
        self.assertNotIn('Cleanup retention of frontier-data, tor-state and onion-state not verified', result['blockers'])

    def test_frontier_only_legacy_cleanup_cannot_verify_tor_keys_retention(self):
        cleanup = self.cleanup_evidence()
        cleanup.pop('retained_volumes')
        self.write('cleanup.json', cleanup)
        result = self.result()
        self.assertNotIn('cleanup.json', result['evidence'])
        self.assertIn('Cleanup retention of frontier-data, tor-state and onion-state not verified', result['blockers'])

    def test_missing_or_failed_retention_of_each_volume_is_rejected(self):
        for volume in ('frontier-data', 'tor-state', 'onion-state'):
            for value in (None, False, 1):
                with self.subTest(volume=volume, value=value):
                    cleanup = self.cleanup_evidence()
                    if value is None:
                        cleanup['retained_volumes'].pop(volume)
                    else:
                        cleanup['retained_volumes'][volume] = value
                    self.write('cleanup.json', cleanup)
                    result = self.result()
                    self.assertNotIn('cleanup.json', result['evidence'])
                    self.assertFalse(result['m2_verified'])
                    self.assertIn('Cleanup retention of frontier-data, tor-state and onion-state not verified', result['blockers'])

    def test_cleanup_still_rejects_retained_containers_or_networks(self):
        for key in ('containers_removed', 'network_removed'):
            cleanup = self.cleanup_evidence()
            cleanup[key] = False
            self.write('cleanup.json', cleanup)
            result = self.result()
            self.assertNotIn('cleanup.json', result['evidence'])
            self.assertIn('Cleanup incomplete', result['blockers'])

    def test_bootstrap_log_history_never_sets_transport_or_fail_closed_fields(self):
        self.write('tor-readiness.json', dict(bootstrap_100_observed=True,
                                              observation_kind='log_history',
                                              current_connectivity_verified=False))
        result = self.result()
        self.assertFalse(result['real_tor_transport'])
        self.assertFalse(result['remote_onion_resolution'])
        self.assertFalse(result['fail_closed_verified'])
        self.assertFalse(any('No bootstrap100 observed' in blocker for blocker in result['blockers']))

    def test_missing_observed_bootstrap_is_reported_as_log_observation(self):
        self.write('tor-readiness.json', dict(bootstrap_100_observed=False,
                                              observed_progress_percent={'tor': 10, 'onion': 10}))
        result = self.result()
        self.assertTrue(any(blocker.startswith('No bootstrap100 observed in logs:') for blocker in result['blockers']))
        self.assertFalse(any('Tor bootstrap incomplete:' in blocker for blocker in result['blockers']))


if __name__ == '__main__':
    unittest.main()
