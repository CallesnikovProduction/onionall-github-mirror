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

    def result(self):
        return verifier.verify(self.path)

    def outage_evidence(self):
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


if __name__ == '__main__':
    unittest.main()
