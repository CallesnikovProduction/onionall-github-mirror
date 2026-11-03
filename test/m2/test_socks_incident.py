import json,tempfile,unittest
from pathlib import Path
import subprocess
import sys
from types import SimpleNamespace
from unittest.mock import patch
import socks_incident as incident
from socks_incident import cold_gateway_override

class ColdGatewayTest(unittest.TestCase):
    def test_override_only_changes_gateway_directory_and_retains_original(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)
            project='onionall-brawler-m2-123456abcdef'
            override=cold_gateway_override(path,project)
            data=json.loads(override.read_text())
            self.assertEqual({'services':{'tor':{'command':['--DataDirectory','/var/lib/tor/client-generations/123456abcdef']}}},data)
            with self.assertRaises(FileExistsError):cold_gateway_override(path,project)
    def test_unowned_names_rejected_without_creating_override(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)
            for project in ('other','onionall-brawler-m2-../../keys','onionall-brawler-m2-ABCDEF123456'):
                with self.assertRaises(ValueError):cold_gateway_override(path,project)
            self.assertEqual([],list(path.iterdir()))


class NamespaceComparisonTest(unittest.TestCase):
    """Exercise optional A/B/A control flow without claiming real Tor availability."""
    def run_case(self, gateway=True, fresh=True, original=True, flag='--compare-fresh-context'):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            project='onionall-brawler-m2-123456abcdef'
            path=root/'.runtime/m2'/project;path.mkdir(parents=True)
            (root/'target').mkdir()
            (root/'target/onionall-brawler-0.1.0-SNAPSHOT.jar').write_bytes(b'test artifact')
            (root/'target/cli-classpath.txt').write_text('test classpath')
            (root/'test/m2').mkdir(parents=True)
            (root/'test/m2/tor_observer.py').write_text('test observer')
            operations=[]
            def save(name,data):(path/name).write_text(json.dumps(data))
            def execute(args,log=None,**kwargs):
                operations.append((list(args),log))
                output=''
                if log=='config.log':output='{}'
                if log=='identity.log':output='a'*56+'.onion'
                if log in ('service-identity-before.log','service-identity-after.log'):output='{}'
                if args[:2]==['docker','exec'] and 'bash' not in args:
                    filename=args[-2] if args[-1]=='auth' else args[-1]
                    phase=filename.rsplit('-',1)[0]
                    if filename.endswith('-python-auth.json'):phase=filename[:-17]
                    passed={'initial':True,'service-return':False,'gateway-return':gateway,
                        'fresh-context':fresh,'original-recheck':original}[phase]
                    save(filename,dict(passed=passed,phase='complete' if passed else 'socks_connect_reply',error_type='TimeoutError',network_namespace='B' if args[2].endswith('-fresh-incident') else 'A'))
                if log=='crawler-path.log':(path/log).write_text('M2_PENDING_MARKER')
                if 'snapshot' in args:
                    save('crawler-final.json',dict(captured_at_epoch=1,
                        entries=[dict(refetch=86401,metadata={})]))
                if args[:3]==['docker','volume','inspect']:output='[{},{},{}]'
                return subprocess.CompletedProcess(args,0,stdout=output)
            runner=SimpleNamespace(project=project,path=path,compose=['compose'],environment={},save=save,
                command=execute,dc=execute,validate_toolchain=lambda:None,tool=lambda name:name)
            with patch.object(incident,'ROOT',root), patch.object(incident,'Runner',return_value=runner), \
                    patch.object(incident,'host_state',return_value={}), \
                    patch.object(incident.signal,'signal'), \
                    patch.object(incident.subprocess,'Popen') as popen, \
                    patch.object(incident.subprocess,'check_output',return_value=''), \
                    patch.object(sys,'argv',['socks_incident.py',flag]):
                popen.return_value.poll.return_value=None
                code=incident.main()
            return code,json.loads((path/'incident.json').read_text()),operations

    def test_both_contexts_pass_without_unnecessary_original_recheck(self):
        code,result,_=self.run_case()
        self.assertEqual(0,code);self.assertTrue(result['fresh_context_passed'])
        self.assertNotIn('original_context_recheck_passed',result)
        self.assertTrue(result['crawler_path_passed'])

    def test_fresh_failure_with_original_success_is_not_reported_as_pass(self):
        code,result,_=self.run_case(fresh=False)
        self.assertEqual(1,code);self.assertFalse(result['passed'])
        self.assertTrue(result['original_context_recheck_passed'])
        self.assertTrue(result['crawler_path_passed'])
        self.assertEqual(['initial','service-return','gateway-return','fresh-context','original-recheck'],
            [row['phase'] for row in result['comparisons']])

    def test_both_contexts_fail_prevents_crawler_and_any_retry_loop(self):
        code,result,_=self.run_case(fresh=False,original=False)
        self.assertEqual(1,code);self.assertNotIn('crawler_path_passed',result)
        self.assertEqual(5,len(result['comparisons']))

    def test_failed_gateway_prerequisite_does_not_launch_fresh_context(self):
        code,result,operations=self.run_case(gateway=False)
        self.assertEqual(1,code);self.assertNotIn('fresh_context_passed',result)
        self.assertNotIn('crawler_path_passed',result)
        self.assertFalse(any(log=='fresh-client-start.log' for _,log in operations))
