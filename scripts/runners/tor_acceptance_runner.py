"""Shared native Linux/Windows M2 orchestration; Docker owns infrastructure."""
import argparse
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'test/m2'))
from tor_preflight import IMAGE, bridge_file, run as preflight


class Runner:
    def __init__(self):
        self.project = 'onionall-brawler-m2-' + uuid.uuid4().hex[:12]
        self.relative = '.runtime/m2/' + self.project
        self.path = ROOT / self.relative
        self.path.joinpath('classes').mkdir(parents=True)
        self.environment = os.environ.copy()
        self.environment.update(M2_RUN_DIR=str(self.path), M2_RUN_REL=self.relative,
            M2_RUNTIME_USER=f'{os.getuid()}:{os.getgid()}' if os.name != 'nt' else '1000:1000')
        self.compose = ['docker', 'compose', '-p', self.project, '-f', 'compose.m2.yaml']
        self.cached_images = False
        self.started = False
        self.failed_stage = None
        self.exit_code = 1
        self.jdk_tools = {}

    def validate_toolchain(self):
        """Compile and package using one JDK; no Maven or Docker needed to reject mismatches."""
        suffix = '.exe' if os.name == 'nt' else ''
        if self.environment.get('JAVA_HOME'):
            directory = Path(self.environment['JAVA_HOME']) / 'bin'
        else:
            compiler = shutil.which('javac')
            if not compiler: raise RuntimeError('A JDK 25+ with javac and jar is required')
            directory = Path(compiler).resolve().parent
        versions = {}
        for name in ('java','javac','jar'):
            executable = (directory / (name + suffix)).resolve()
            if not executable.is_file(): raise RuntimeError('JAVA_HOME/PATH must select one complete JDK: missing ' + name)
            result = subprocess.run([str(executable), '-version' if name != 'jar' else '--version'],
                                    capture_output=True, text=True, timeout=15)
            import re
            match = re.search(r'(?:version\s+"|(?:javac|jar)\s+)(\d+)', result.stdout + result.stderr)
            if result.returncode or not match: raise RuntimeError('Could not determine selected JDK version: ' + name)
            versions[name] = int(match.group(1))
            self.jdk_tools[name] = str(executable)
        if len(set(versions.values())) != 1 or min(versions.values()) < 25:
            raise RuntimeError('java, javac and jar must belong to the same JDK 25+ toolchain')
        self.environment['JAVA_HOME'] = str(directory.parent)
        self.environment['PATH'] = str(directory) + os.pathsep + self.environment.get('PATH','')
        self.save('toolchain.json', dict(java_feature=versions['java'], javac_feature=versions['javac'],
                                       jar_feature=versions['jar'], compile_release=25))

    def tool(self, name):
        if name in self.jdk_tools: return self.jdk_tools[name]
        suffix = '.exe' if os.name == 'nt' else ''
        if name in {'java', 'javac', 'jar'} and self.environment.get('JAVA_HOME'):
            selected = Path(self.environment['JAVA_HOME']) / 'bin' / (name + suffix)
            if selected.is_file(): return str(selected)
        found = shutil.which(name + ('.cmd' if os.name == 'nt' and name == 'mvn' else ''))
        if not found: raise RuntimeError('Required command unavailable: ' + name)
        return found

    def command(self, args, log=None, capture=False, allow_failure=False, timeout=None):
        if log: self.failed_stage = log
        with (self.path / log).open('w', encoding='utf-8') if log else open(os.devnull, 'w') as output:
            result = subprocess.run([str(x) for x in args], cwd=ROOT, env=self.environment,
                                    stdout=subprocess.PIPE if capture else output,
                                    stderr=subprocess.PIPE if capture else subprocess.STDOUT,
                                    text=True, timeout=timeout)
        if result.returncode and not allow_failure:
            raise RuntimeError('M2 command failed; inspect ' + (log or 'stage output'))
        return result

    def dc(self, args, log=None, **kwargs):
        return self.command(self.compose + args, log, **kwargs)

    def save(self, name, value):
        (self.path / name).write_text(json.dumps(value, indent=2), encoding='utf-8')

    def inspect_to(self, args, name):
        result = self.command(args, capture=True)
        self.save(name, json.loads(result.stdout))

    def snapshot(self, name):
        self.dc(['run', '--rm', '--no-deps', 'probe', 'snapshot', name], 'snapshot-' + name + '.log')

    def network(self, phase, fixture_ip, outage=False):
        name = self.project + '-network-' + phase
        args = ['run', '--name', name, '--no-deps', '--user', self.environment['M2_RUNTIME_USER'],
                '--entrypoint', 'python3', 'crawler', '/workspace/test/m2/network_probe.py', fixture_ip]
        if outage: args += ['--outage']
        try:
            result = self.dc(args, 'network-' + phase + '.log', allow_failure=True)
            self.inspect_to(['docker', 'inspect', name], 'network-container-' + phase + '.json')
            self.inspect_to(['docker', 'network', 'inspect', self.project + '_crawler'], 'network-config-' + phase + '.json')
            if result.returncode: raise RuntimeError('Crawler network probe failed: ' + phase)
        finally:
            self.command(['docker', 'rm', '-f', name], 'network-cleanup-' + phase + '.log', allow_failure=True)

    def execute(self):
        self.validate_toolchain()
        self.environment['M2_BRIDGES_FILE'] = str(bridge_file())
        self.command(['docker', 'info'], 'docker-info.log', timeout=20)
        self.failed_stage = 'preflight'
        # Preflight uses the same environment/secret and does not launch Maven.
        previous = os.environ.get('M2_BRIDGES_FILE')
        os.environ['M2_BRIDGES_FILE'] = self.environment['M2_BRIDGES_FILE']
        try: ready = preflight(self.path / 'preflight')
        finally:
            if previous is None: os.environ.pop('M2_BRIDGES_FILE', None)
            else: os.environ['M2_BRIDGES_FILE'] = previous
        if not ready['ready']: raise RuntimeError('Tor bridge preflight failed; full M2 was not started')
        maven = [self.tool('mvn'), '-B', '-Dmaven.compiler.release=25']
        if self.environment.get('M2_MAVEN_REPOSITORY'):
            maven += ['-Dmaven.repo.local=' + self.environment['M2_MAVEN_REPOSITORY']]
        self.command(maven + ['verify'], 'build.log')
        self.command(maven + ['org.apache.maven.plugins:maven-dependency-plugin:3.10.0:build-classpath',
                             '-Dmdep.outputFile=' + str(self.path/'classpath.txt')], 'dependencies.log')
        cp = str(ROOT/'target/onionall-brawler-0.1.0-SNAPSHOT.jar') + os.pathsep + (self.path/'classpath.txt').read_text().strip()
        self.command([self.tool('javac'), '--release', '25', '-cp', cp, '-d', self.path/'classes'] +
                     sorted((ROOT/'test/m2').glob('*.java')), 'compile-probes.log')
        shutil.copy2(ROOT/'target/onionall-brawler-0.1.0-SNAPSHOT.jar', self.path/'crawler-m2.jar')
        self.dc(['--profile', 'tools', 'config', '--quiet'], 'config.log')
        self.save('compose.json', json.loads(self.dc(['--profile','tools','config','--format','json'], capture=True).stdout))
        if self.cached_images:
            self.inspect_to(['docker','image','inspect',IMAGE,'crawlercommons/url-frontier:2.4',
                self.environment.get('M2_STORM_IMAGE', 'storm:3.1.0-jre25'),'python:3.12-slim'], 'cached-images.json')
            self.save('image-policy.json', dict(mode='existing-local-images', build=False, pull=False))
        else:
            self.dc(['build'], 'build-images.log')
            self.dc(['--profile','tools','pull','--ignore-buildable'], 'pull.log')
        self.started = True
        startup=['up','-d','--wait','--wait-timeout','360']
        if self.cached_images: startup += ['--no-build','--pull','never']
        self.dc(startup+['fixture','onion','tor','frontier'], 'startup.log')
        hostname = self.dc(['exec','-T','onion','cat','/var/lib/tor/hidden_service/hostname'], capture=True).stdout
        (self.path/'onion-hostname.txt').write_text(hostname, encoding='utf-8')
        self.command([sys.executable, '-B', 'test/m2/prepare.py', self.path], 'prepare.log')
        self.command([self.tool('jar'),'--update','--file',self.path/'crawler-m2.jar','-C',self.path/'classes','.',
                      '-C',self.path/'resources','.'], 'package-probes.log')
        self.inspect_to(['docker','image','inspect',IMAGE,'crawlercommons/url-frontier:2.4',self.environment.get('M2_STORM_IMAGE', 'storm:3.1.0-jre25'),'python:3.12-slim'], 'images.json')
        fixture = self.dc(['ps','-q','fixture'], capture=True).stdout.strip()
        self.inspect_to(['docker','inspect',fixture], 'fixture-inspect.json')
        networks = json.loads((self.path/'fixture-inspect.json').read_text())[0]['NetworkSettings']['Networks']
        if len(networks) != 1: raise RuntimeError('Fixture must have exactly one isolated network')
        fixture_ip = next(iter(networks.values()))['IPAddress']
        self.network('initial', fixture_ip)
        self.dc(['run','--rm','--no-deps','--entrypoint','java','probe','-cp','/apache-storm/lib/*:/apache-storm/lib-common/*:/evidence/crawler-m2.jar',
                 'dev.onionall.brawler.m2.TorPolicyProbe'], 'policy.log')
        self.dc(['run','--rm','--no-deps','probe','ready'], 'ready.log')
        self.snapshot('empty.json')
        if json.loads((self.path/'empty.json').read_text())['entries']: raise RuntimeError('New M2 frontier must be empty')
        self.dc(['run','--rm','--no-deps','probe','seed','root'], 'seed-root.log')
        self.dc(['run','--rm','--no-deps','crawler'], 'crawl-first.log')
        self.save('crawl-runtime.json', {'first_exit_code':0})
        self.snapshot('crawled.json')
        self.dc(['run','--rm','--no-deps','probe','seed','pending'], 'seed-pending.log')
        self.snapshot('before.json')
        self.dc(['stop','--timeout','30','frontier'], 'frontier-stop.log')
        self.dc(['logs','--no-color','frontier'], 'frontier-before.log')
        self.dc(['rm','-f','frontier'], 'frontier-remove.log')
        self.dc(['up','-d','--wait','--wait-timeout','60','frontier'], 'frontier-recreated.log')
        self.dc(['run','--rm','--no-deps','probe','ready'], 'ready-recreated.log')
        self.snapshot('after.json')
        self.dc(['run','--rm','--no-deps','crawler'], 'crawl-second.log')
        self.save('crawl-runtime.json', {'first_exit_code':0,'second_exit_code':0})
        self.snapshot('final.json')
        self.dc(['stop','--timeout','30','tor'], 'tor-stop.log')
        self.network('outage', fixture_ip, True)
        self.dc(['up','-d','--wait','--wait-timeout','360','tor'], 'tor-return.log')
        scenario = self.command([sys.executable,'-B','test/m2/scenarios.py',self.project,self.path], 'scenario-driver.log', allow_failure=True)
        if scenario.returncode == 0:
            outage_code = self.command([sys.executable,'-B','test/m2/outage_pipeline.py',self.project,self.path],
                'outage-driver.log', allow_failure=True).returncode
        else:
            outage_code = 1
            self.save('outage-pipeline.json', dict(passed=False, evidence=[],
                blocker='Not executed after failed security scenarios; quarantine must not be bypassed'))
        self.save('scenario-exits.json', dict(scenario_exit_code=scenario.returncode,pipeline_exit_code=outage_code))
        self.run_m1_regression()
        self.exit_code = 0

    def run_m1_regression(self):
        """Bind the child evidence path before launch; never infer success from mtimes."""
        project = self.project + '-m1'
        evidence = ROOT / '.runtime/m1' / project
        if evidence.exists():
            raise RuntimeError('Refusing to reuse existing M1 evidence: ' + str(evidence))
        if os.name == 'nt':
            shell = shutil.which('pwsh') or shutil.which('powershell')
            if not shell: raise RuntimeError('PowerShell required for Windows M1 regression')
            args = [shell, '-NoProfile', '-File', str(ROOT / 'scripts/run-controlled-crawl.ps1')]
        else:
            args = ['bash', 'scripts/run-controlled-crawl.sh']
        record = dict(exit_code=None, evidence_directory=str(evidence),
                      log=str(self.path / 'm1-regression.log'), cached_images=self.cached_images)
        self.save('m1-regression.json', record)
        selected = {'M1_PROJECT_NAME': project, 'M1_CACHED_IMAGES': '1' if self.cached_images else '0'}
        if self.environment.get('M2_STORM_IMAGE'):
            selected['M1_STORM_IMAGE'] = self.environment['M2_STORM_IMAGE']
        previous = {key: self.environment.get(key) for key in selected}
        self.environment.update(selected)
        try:
            try:
                child = self.command(args, 'm1-regression.log', allow_failure=True)
            except (OSError, subprocess.SubprocessError) as error:
                record['blocker'] = 'M1 child could not complete: ' + str(error)
                self.save('m1-regression.json', record)
                raise
        finally:
            for key, value in previous.items():
                if value is None: self.environment.pop(key, None)
                else: self.environment[key] = value
        record['exit_code'] = child.returncode
        record['result_present'] = (evidence / 'result.json').is_file()
        self.save('m1-regression.json', record)
        if child.returncode:
            log = self.path / 'm1-regression.log'
            text = log.read_text(encoding='utf-8', errors='replace') if log.is_file() else ''
            image_errors = ('failed to resolve reference', 'pull access denied', 'no such image',
                            'unable to get image', 'failed to pull', 'image inspection failed')
            daemon_registry_failure = (any(prefix in text.lower() for prefix in
                ('error response from daemon: get ', 'error response from daemon: head '))
                and any(value in text.lower() for value in
                    ('network is unreachable', 'no such host', 'tls handshake timeout', 'connection timed out')))
            record['failure_kind'] = ('image-resolution' if daemon_registry_failure or
                                      any(value in text.lower() for value in image_errors)
                                      else 'child-command-failure')
            self.save('m1-regression.json', record)
            raise RuntimeError(f'M1 regression exited {child.returncode}; inspect {record["log"]}; '
                               f'partial evidence: {evidence}')
        if not record['result_present']:
            raise RuntimeError('M1 regression exited 0 without result.json at ' + str(evidence))

    def cleanup(self):
        failure_stage = self.failed_stage
        if self.started:
            self.dc(['logs','--no-color'], 'services.log', allow_failure=True)
            down = self.dc(['down','--timeout','60'], 'cleanup.log', allow_failure=True)
            remaining = self.dc(['ps','-aq'], capture=True, allow_failure=True)
            networks = self.command(['docker','network','ls','--filter','label=com.docker.compose.project='+self.project,'--format','{{.ID}}'],capture=True,allow_failure=True)
            expected = {key: self.project + '_' + key for key in ('frontier-data','tor-state','onion-state')}
            retained = self.command(['docker','volume','inspect'] + list(expected.values()),capture=True,allow_failure=True)
            inspection = json.loads(retained.stdout) if retained.stdout.strip() else []
            self.save('retained-volumes.json', inspection)
            found = {volume['Name'] for volume in inspection}
            retained_volumes = {key: name in found for key,name in expected.items()}
            self.save('cleanup.json',dict(containers_removed=down.returncode==0 and remaining.returncode==0 and not remaining.stdout.strip(),
                network_removed=networks.returncode==0 and not networks.stdout.strip(),
                volume_retained=all(retained_volumes.values()), retained_volumes=retained_volumes))
        self.save('runner-state.json',dict(exit_code=self.exit_code,failed_line=failure_stage if self.exit_code else None))
        self.command([sys.executable,'-B','test/m2/bootstrap_evidence.py',self.path], 'bootstrap-summary.log',allow_failure=True)
        verification = self.command([sys.executable,'-B','test/m2/verify.py',self.path], 'verification.log',allow_failure=True)
        self.exit_code = 0 if self.exit_code == 0 and verification.returncode == 0 else 1
        print('M2 evidence: ' + str(self.path) + '; exit ' + str(self.exit_code) + '; persistent volumes retained')


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--cached-images', action='store_true', help='Use and record prebuilt local images; never build/pull')
    args=parser.parse_args()
    runner=Runner()
    runner.cached_images=args.cached_images
    def interrupted(signum, frame):
        raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    try: runner.execute()
    except KeyboardInterrupt:
        runner.exit_code=130
        print('M2 interrupted; collecting evidence',file=sys.stderr)
    except (OSError,RuntimeError,ValueError,subprocess.SubprocessError) as error:
        print(str(error),file=sys.stderr)
    finally:
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        try: runner.cleanup()
        except (OSError,RuntimeError,ValueError,subprocess.SubprocessError):
            runner.exit_code=1
            print('M2 cleanup or evidence verification failed; inspect project '+runner.project,file=sys.stderr)
    return runner.exit_code


if __name__=='__main__':
    raise SystemExit(main())
