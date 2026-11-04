"""Native orchestration contracts without starting infrastructure or trusting fake acceptance."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('m2_runner',ROOT/'scripts/runners/tor_acceptance_runner.py')
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

    def test_jdk25_selected_consistently(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory);runner=self.runner(path)
            binpath=path/'jdk/bin';binpath.mkdir(parents=True)
            for name in ('java','javac','jar'):(binpath/(name+('.exe' if os.name=='nt' else ''))).touch()
            runner.environment['JAVA_HOME']=str(binpath.parent)
            def version(args,**kwargs):
                name=Path(args[0]).stem
                value='openjdk version "25.0.5"' if name=='java' else name+' 25.0.5'
                return subprocess.CompletedProcess(args,0,stdout=value,stderr='')
            with patch.object(m2_runner.subprocess,'run',side_effect=version):
                runner.validate_toolchain()
            import json
            self.assertEqual(25,json.loads((path/'toolchain.json').read_text())['compile_release'])

    def test_jdk_mismatch_rejected_before_docker(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory);runner=self.runner(path)
            binpath=path/'jdk/bin';binpath.mkdir(parents=True)
            for name in ('java','javac','jar'):(binpath/(name+('.exe' if os.name=='nt' else ''))).touch()
            runner.environment['JAVA_HOME']=str(binpath.parent)
            def version(args,**kwargs):
                name=Path(args[0]).stem
                text='openjdk version "17.0.1"' if name=='java' else name+' '+('21.0.1' if name=='javac' else '17.0.1')
                return subprocess.CompletedProcess(args,0,stdout=text,stderr='')
            with patch.object(m2_runner.subprocess,'run',side_effect=version):
                with self.assertRaisesRegex(RuntimeError,'same JDK'):
                    runner.validate_toolchain()


class M1RegressionContractTest(unittest.TestCase):
    def runner(self, root):
        runner=m2_runner.Runner.__new__(m2_runner.Runner)
        runner.environment={'M1_PROJECT_NAME':'existing-parent-value'}
        runner.path=root/'m2';runner.path.mkdir()
        runner.project='onionall-test'
        runner.cached_images=True
        return runner

    def test_selected_storm_candidate_is_passed_to_child_and_environment_restored(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);runner=self.runner(root)
            image='onionall-storm:3.1.0-jre25-cleanup1'
            runner.environment.update(M2_STORM_IMAGE=image,M1_STORM_IMAGE='original-user-value')
            def child(args,log,**kwargs):
                self.assertEqual(image,runner.environment['M1_STORM_IMAGE'])
                evidence=root/'.runtime/m1'/ (runner.project+'-m1');evidence.mkdir(parents=True)
                (evidence/'result.json').write_text('{"assertions_passed":true}')
                return subprocess.CompletedProcess(args,0)
            runner.command=child
            with patch.object(m2_runner,'ROOT',root):runner.run_m1_regression()
            self.assertEqual('original-user-value',runner.environment['M1_STORM_IMAGE'])
            self.assertEqual(image,runner.environment['M2_STORM_IMAGE'])

    def test_failed_child_preserves_image_failure_before_checking_result(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);runner=self.runner(root)
            def child(args,log,**kwargs):
                self.assertEqual('onionall-test-m1',runner.environment['M1_PROJECT_NAME'])
                self.assertEqual('1',runner.environment['M1_CACHED_IMAGES'])
                (runner.path/log).write_text('failed to resolve reference: network is unreachable')
                return subprocess.CompletedProcess(args,17)
            runner.command=child
            with patch.object(m2_runner,'ROOT',root):
                with self.assertRaisesRegex(RuntimeError,'M1 regression exited 17'):
                    runner.run_m1_regression()
            evidence=json.loads((runner.path/'m1-regression.json').read_text())
            self.assertEqual(17,evidence['exit_code'])
            self.assertEqual('image-resolution',evidence['failure_kind'])
            self.assertFalse(evidence['result_present'])
            self.assertEqual(str(root/'.runtime/m1/onionall-test-m1'),evidence['evidence_directory'])
            self.assertEqual({'M1_PROJECT_NAME':'existing-parent-value'},runner.environment)

    def test_daemon_registry_get_failure_is_classified_without_manifest_marker(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);runner=self.runner(root)
            def child(args,log,**kwargs):
                (runner.path/log).write_text('Error response from daemon: Get "https://registry-1.docker.io/v2/": network is unreachable')
                return subprocess.CompletedProcess(args,1)
            runner.command=child
            with patch.object(m2_runner,'ROOT',root):
                with self.assertRaisesRegex(RuntimeError,'M1 regression exited 1'):runner.run_m1_regression()
            self.assertEqual('image-resolution',json.loads((runner.path/'m1-regression.json').read_text())['failure_kind'])

    @unittest.skipIf(os.name == 'nt', 'Bash runner test; Windows regex parity checked separately')
    def test_unsafe_compose_project_names_are_rejected_before_any_docker_operation(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'scripts/runners').mkdir(parents=True);(root/'target').mkdir()
            (root/'scripts/runners/run-controlled-crawl.sh').write_text((ROOT/'scripts/runners/run-controlled-crawl.sh').read_text())
            (root/'target/onionall-brawler-0.1.0-SNAPSHOT.jar').write_bytes(b'test-only')
            tools=root/'tools';tools.mkdir()
            for command in ('docker','javac','jar'):
                executable=tools/command
                executable.write_text('#!/bin/sh\necho unexpected-tool-execution >&2\nexit 97\n')
                executable.chmod(0o755)
            for name in ('postgres','other-project','onionall-brawler-m1-anything',
                         'onionall-brawler-m1-012345abcdef/path','onionall-brawler-m2-012345abcdef'):
                env=dict(os.environ,M1_PROJECT_NAME=name,PYTHON=os.sys.executable,
                         JAVA_HOME=str(root),PATH=str(tools)+os.pathsep+os.environ.get('PATH',''))
                # Select tools from PATH, not inherited JAVA_HOME.
                env.pop('JAVA_HOME',None)
                result=subprocess.run(['bash',str(root/'scripts/runners/run-controlled-crawl.sh')],env=env,capture_output=True,text=True,timeout=3)
                self.assertEqual(2,result.returncode,result.stderr)
                self.assertIn('Invalid M1_PROJECT_NAME',result.stderr)
                self.assertNotIn('unexpected-tool-execution',result.stderr)

    def test_both_platforms_use_narrow_project_names_and_cached_pull_policy(self):
        bash=(ROOT/'scripts/runners/run-controlled-crawl.sh').read_text();powershell=(ROOT/'scripts/runners/run-controlled-crawl.ps1').read_text()
        expected='^onionall-brawler-(m1-[a-f0-9]{12}|m2-[a-f0-9]{12}-m1)$'
        self.assertIn(expected,bash);self.assertIn(expected,powershell)
        self.assertIn('M1_CACHED_IMAGES',bash);self.assertIn('M1_CACHED_IMAGES',powershell)
        self.assertIn('M1_PULL_POLICY=never',bash)
        self.assertIn("if ($cachedImages) { 'never' }",powershell)
        compose=(ROOT/'compose.m1.yaml').read_text()
        self.assertEqual(compose.count('    image:'),compose.count('    pull_policy: ${M1_PULL_POLICY:-missing}'))

    def test_success_uses_allocated_evidence_not_mtimes_or_other_results(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);runner=self.runner(root)
            unrelated=root/'.runtime/m1/unrelated';unrelated.mkdir(parents=True)
            (unrelated/'result.json').write_text('{}')
            def child(args,log,**kwargs):
                evidence=root/'.runtime/m1'/runner.environment['M1_PROJECT_NAME']
                evidence.mkdir();(evidence/'result.json').write_text('{}')
                return subprocess.CompletedProcess(args,0)
            runner.command=child
            with patch.object(m2_runner,'ROOT',root):runner.run_m1_regression()
            record=json.loads((runner.path/'m1-regression.json').read_text())
            self.assertEqual(0,record['exit_code']);self.assertTrue(record['result_present'])
            self.assertTrue(record['evidence_directory'].endswith('onionall-test-m1'))

    def test_exit_zero_without_result_is_explicit_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);runner=self.runner(root)
            runner.command=lambda args,*a,**k:subprocess.CompletedProcess(args,0)
            with patch.object(m2_runner,'ROOT',root):
                with self.assertRaisesRegex(RuntimeError,'exited 0 without result.json'):
                    runner.run_m1_regression()

    def test_existing_child_evidence_is_never_reused(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);runner=self.runner(root)
            (root/'.runtime/m1/onionall-test-m1').mkdir(parents=True)
            with patch.object(m2_runner,'ROOT',root):
                with self.assertRaisesRegex(RuntimeError,'Refusing to reuse'):
                    runner.run_m1_regression()

    def test_failed_spawn_preserves_failure_and_restores_environment(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);runner=self.runner(root)
            runner.command=lambda *a,**k:(_ for _ in ()).throw(OSError('shell unavailable'))
            with patch.object(m2_runner,'ROOT',root):
                with self.assertRaisesRegex(OSError,'shell unavailable'):runner.run_m1_regression()
            record=json.loads((runner.path/'m1-regression.json').read_text())
            self.assertIsNone(record['exit_code']);self.assertIn('shell unavailable',record['blocker'])
            self.assertEqual({'M1_PROJECT_NAME':'existing-parent-value'},runner.environment)
