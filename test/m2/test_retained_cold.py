"""Retained/cold classification, isolation and passive telemetry contracts."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from retained_cold import interpret, observer_override, PAIR_CODE
from tor_observer import event_record


class RetainedColdTest(unittest.TestCase):
    def evidence(self):
        return {
            'service-events.jsonl': [dict(kind='watch_started'),dict(kind='revision_period',period=10),
                dict(kind='revision_upload',revision_counter=12,period=10),
                dict(kind='HS_DESC',action='UPLOADED',blinded_key_hash='key',revision_counter=12,period=10)],
            'retained-events.jsonl': [dict(kind='watch_started'),dict(kind='STREAM')],
            'cold-events.jsonl': [dict(kind='watch_started'),dict(kind='STREAM'),
                dict(kind='HS_DESC_CONTENT',revision_counter=12,blinded_key_hash='key')]}

    def test_pair_interpretations_do_not_claim_m2_or_disk_corruption(self):
        for control,cold,status in [(False,True,'STATE_PATH_DIFFERENCE'),(False,False,'DEPENDENCY_UNAVAILABLE'),
                                  (True,True,'NOT_REPRODUCED'),(True,False,'INCONCLUSIVE')]:
            actual,reason=interpret({'tor':{'passed':control},'cold':{'passed':cold}},self.evidence())
            self.assertEqual(status,actual)
            if status=='STATE_PATH_DIFFERENCE':self.assertIn('not established',reason)

    def test_missing_period_revision_stream_or_observer_is_inconclusive(self):
        pair={'tor':{'passed':False},'cold':{'passed':True}}
        for file,kind in [
                          ('service-events.jsonl','HS_DESC'),('cold-events.jsonl','HS_DESC_CONTENT'),
                          ('retained-events.jsonl','STREAM'),('cold-events.jsonl','watch_started')]:
            evidence=self.evidence();evidence[file]=[e for e in evidence[file] if e['kind']!=kind]
            self.assertEqual('INCONCLUSIVE',interpret(pair,evidence)[0])

    def test_cold_observer_cannot_replace_or_share_control_socket(self):
        config={'services':{'tor':{'image':'pinned','volumes':[{'source':'tor-state','target':'/var/lib/tor'}],
                'networks':{'crawler':{},'outbound':{}},'cap_drop':['ALL'],'read_only':True,
                'depends_on':{'tor-state-init':{}},'build':{'context':'.'}}}}
        before=copy.deepcopy(config)
        override=observer_override(config,'onionall-brawler-m2-123456abcdef')
        self.assertEqual(before,config);self.assertEqual({'cold'},set(override['services']))
        cold=override['services']['cold'];command=cold['command']
        self.assertEqual(config['services']['tor']['volumes'],cold['volumes'])
        self.assertIn('/var/lib/tor/client-generations/123456abcdef/control',command)
        self.assertIn('/var/lib/tor/client-generations/123456abcdef/control_auth_cookie',command)
        self.assertNotIn('build',cold);self.assertEqual({},cold['depends_on'])
        self.assertIn('client-generations',cold['healthcheck']['test'][-1])

    def test_wire_pair_uses_one_barrier_same_auth_and_deadline_without_resets(self):
        self.assertIn('threading.Barrier(2)',PAIR_CODE)
        self.assertIn("('tor','cold')",PAIR_CODE)
        self.assertIn('deadline=start+20',PAIR_CODE)
        self.assertNotIn('jdk_auth=True',PAIR_CODE)
        for command in ('HSFETCH','NEWNYM','CLOSECIRCUIT'):self.assertNotIn(command,PAIR_CODE)

    def test_telemetry_redacts_addresses_paths_and_credentials(self):
        line='650 STREAM 14 SENTCONNECT 8 secretonion.onion:80 SOURCE_ADDR=10.0.0.4:35 SOCKS_USERNAME=secret PURPOSE=USER REASON=TIMEOUT'
        data=event_record(line)
        self.assertEqual(dict(kind='STREAM',id='14',status='SENTCONNECT',circuit_id='8',purpose='USER',reason='TIMEOUT'),data)
        text=json.dumps(data)
        for secret in ('secretonion','10.0.0.4','secret'):self.assertNotIn(secret,text)
        self.assertIsNone(event_record('650 INFO arbitrary bridge private diagnostic'))


class RetainedColdFlowTest(unittest.TestCase):
    def test_restored_control_does_not_start_observer_or_crawler(self):
        from test_socks_incident import NamespaceComparisonTest
        code,result,operations=NamespaceComparisonTest().run_case(flag='--compare-retained-cold')
        self.assertEqual(1,code)
        self.assertEqual('NOT_REPRODUCED',result['retained_cold']['status'])
        self.assertEqual(0,result['retained_cold']['pair_attempts'])
        self.assertNotIn('crawler_path_passed',result)
        self.assertFalse(any(log=='cold-observer-start.log' for _,log in operations))

    def test_failed_control_delegates_once_and_never_launches_crawler(self):
        from unittest.mock import patch
        from test_socks_incident import NamespaceComparisonTest
        with patch('retained_cold.run',return_value={'status':'INCONCLUSIVE','pair_attempts':0,'m2_verified':False}) as pair:
            code,result,operations=NamespaceComparisonTest().run_case(gateway=False,flag='--compare-retained-cold')
        pair.assert_called_once()
        self.assertEqual(1,code);self.assertFalse(result['m2_verified'])
        self.assertEqual('INCONCLUSIVE',result['retained_cold']['status'])
        self.assertNotIn('crawler_path_passed',result)

    def test_unlinked_revision_period_cannot_support_causal_result(self):
        evidence=RetainedColdTest().evidence()
        del evidence['service-events.jsonl'][-1]['period']
        self.assertEqual('INCONCLUSIVE',interpret({'tor':{'passed':False},'cold':{'passed':True}},evidence)[0])

    def test_experiment_ceiling_still_runs_bounded_cleanup(self):
        from unittest.mock import patch
        from test_socks_incident import NamespaceComparisonTest
        import socks_incident
        with patch.object(socks_incident.time,'monotonic',side_effect=[0,601]):
            code,result,operations=NamespaceComparisonTest().run_case(flag='--compare-retained-cold')
        self.assertEqual(1,code)
        self.assertEqual('INCONCLUSIVE',result['retained_cold']['status'])
        self.assertTrue(any(log=='cleanup.log' for _,log in operations))
        self.assertTrue(result['volumes_retained'])
