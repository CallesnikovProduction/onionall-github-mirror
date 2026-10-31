"""Native orchestration contracts without starting infrastructure or trusting fake acceptance."""
import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('m2_runner',ROOT/'scripts/m2.py')
m2_runner=importlib.util.module_from_spec(spec)
spec.loader.exec_module(m2_runner)


class RunnerContractTest(unittest.TestCase):
    def runner(self,path):
        runner=m2_runner.Runner.__new__(m2_runner.Runner)
        runner.environment={}
        runner.jdk_tools={}
        runner.path=path
        runner.project='onionall-test'
        runner.started=True
        runner.exit_code=1
        runner.failed_stage='startup.log'
        runner.compose=['docker','compose']
        return runner

    def test_cleanup_checks_every_retained_volume_and_keeps_failure_stage(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory);runner=self.runner(path)
            def result(args,*unused,**kwargs):
                text=''
                if args[:3]==['docker','volume','inspect']:
                    self.assertEqual(args[3:],['onionall-test_frontier-data','onionall-test_tor-state','onionall-test_onion-state'])
                    text='[{"Name":"onionall-test_frontier-data"},{"Name":"onionall-test_tor-state"},{"Name":"onionall-test_onion-state"}]'
                return subprocess.CompletedProcess(args,0,stdout=text,stderr='')
            runner.command=result
            runner.dc=lambda args,*unused,**kwargs:subprocess.CompletedProcess(args,0,stdout='',stderr='')
            runner.cleanup()
            import json
            cleanup=json.loads((path/'cleanup.json').read_text())
            self.assertTrue(all(cleanup['retained_volumes'].values()))
            self.assertEqual(json.loads((path/'runner-state.json').read_text())['failed_line'],'startup.log')

    def test_missing_volume_is_not_reported_retained(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory);runner=self.runner(path)
            def result(args,*unused,**kwargs):
                text='[{"Name":"onionall-test_frontier-data"}]' if args[:3]==['docker','volume','inspect'] else ''
                return subprocess.CompletedProcess(args,0,stdout=text,stderr='')
            runner.command=result
            runner.dc=lambda args,*unused,**kwargs:subprocess.CompletedProcess(args,0,stdout='',stderr='')
            runner.cleanup()
            import json
            cleanup=json.loads((path/'cleanup.json').read_text())
            self.assertFalse(cleanup['volume_retained'])
            self.assertFalse(cleanup['retained_volumes']['onion-state'])

    def test_jdk_mismatch_rejected_before_docker(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory);runner=self.runner(path)
            binpath=path/'jdk/bin';binpath.mkdir(parents=True)
            for name in ('java','javac','jar'):(binpath/name).touch()
            runner.environment['JAVA_HOME']=str(binpath.parent)
            def version(args,**kwargs):
                name=Path(args[0]).name
                text='openjdk version "17.0.1"' if name=='java' else name+' '+('21.0.1' if name=='javac' else '17.0.1')
                return subprocess.CompletedProcess(args,0,stdout=text,stderr='')
            with patch.object(m2_runner.subprocess,'run',side_effect=version):
                with self.assertRaisesRegex(RuntimeError,'same JDK'):
                    runner.validate_toolchain()
