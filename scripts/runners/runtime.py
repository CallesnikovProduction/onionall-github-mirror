"""Canonical local Compose lifecycle; scheduling and approval stay in the JVM CLI."""
import argparse
import hashlib
import base64
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
STORM_IMAGE = 'onionall-storm:3.1.0-jre25-cleanup2'


def execute(command, *, env=None, capture=False, timeout=180):
    return subprocess.run([str(value) for value in command], cwd=ROOT, env=env,
                          text=True, capture_output=capture, check=True, timeout=timeout)


def fixture_host(number):
    key = bytes([number]) * 32
    payload = key + hashlib.sha3_256(b'.onion checksum' + key + b'\3').digest()[:2] + b'\3'
    return base64.b32encode(payload).decode().lower() + '.onion'


class Runtime:
    def __init__(self, fixture=False):
        self.fixture = fixture
        self.mode = 'fixture' if fixture else 'tor'
        self.project = 'onionall-runtime-fixture' if fixture else 'onionall-runtime'
        self.state = ROOT / '.runtime' / ('local-fixture' if fixture else 'local')
        self.env = dict(os.environ,
                        ONIONALL_RUNTIME_DIR=str(self.state),
                        ONIONALL_RUNTIME_USER=f'{os.getuid()}:{os.getgid()}')
        self.compose = ['docker', 'compose', '-p', self.project,
                        '-f', ROOT / 'compose.runtime.yaml', '--profile', self.mode]

    def dc(self, *args, **kwargs):
        return execute([*self.compose, *args], env=self.env, **kwargs)

    def prepare(self):
        for artifact in ('target/onionall/bin/onionall',
                         'target/onionall-brawler-0.1.0-SNAPSHOT.jar',
                         'frontier-server/target/urlfrontier-admission.jar'):
            if not (ROOT / artifact).is_file():
                raise RuntimeError('Missing build artifact; run ./onionall build first.')
        self.state.mkdir(parents=True, exist_ok=True, mode=0o700)
        token = self.state / 'admission-token'
        if not token.exists():
            with open(token, 'x', encoding='ascii', opener=lambda path, flags: os.open(path, flags, 0o600)) as output:
                output.write(secrets.token_hex(32))
        if token.is_symlink() or not token.is_file() or token.stat().st_mode & 0o077:
            raise RuntimeError('Admission token must be a private regular file (mode 600).')
        descriptor = dict(frontier_host='frontier', frontier_port=7071,
                          socks_host='socks-fixture' if self.fixture else 'tor', socks_port=9050,
                          storm_executable='/apache-storm/bin/storm', crawler_jar='/app/crawler.jar',
                          work_directory='/state/execution', seconds=120 if self.fixture else 180,
                          http_timeout_ms=2000 if self.fixture else 20000,
                          retry_minutes=1 if self.fixture else 120)
        # Fixed deployment configuration, never remote content or a new scheduler.
        (self.state / 'runtime.json').write_text(json.dumps(descriptor, indent=2) + '\n')
        if self.fixture:
            for filename, number in (('onion-hostname.txt', 1), ('foreign-hostname.txt', 2)):
                (self.state / filename).write_text(fixture_host(number))

    def check_docker(self):
        try:
            execute(['docker', 'info', '--format', '{{.ServerVersion}}'], capture=True, timeout=15)
            execute(['docker', 'compose', 'version'], capture=True, timeout=15)
        except (OSError, subprocess.SubprocessError) as error:
            raise RuntimeError('Docker Engine/Compose unavailable. Start Docker and check docker info; '
                               'no services or data were removed.') from error

    def up(self):
        self.check_docker()
        other = 'onionall-runtime' if self.fixture else 'onionall-runtime-fixture'
        live = execute(['docker', 'ps', '-q', '--filter', f'label=com.docker.compose.project={other}'],
                       capture=True).stdout.strip()
        if live:
            raise RuntimeError('Another canonical runtime mode is active. Stop it before switching mode.')
        self.prepare()
        image = self.env.get('ONIONALL_STORM_IMAGE', STORM_IMAGE)
        try:
            label = execute(['docker', 'image', 'inspect', image, '--format',
                             '{{index .Config.Labels "dev.onionall.storm.lifecycle-fix"}}'],
                            capture=True).stdout.strip()
        except subprocess.CalledProcessError as error:
            raise RuntimeError('Patched Storm image missing; run ./onionall build --images.') from error
        if label != 'cleanup-submit-lock-2':
            raise RuntimeError('Expected pinned Storm cleanup2 image; run ./onionall build --images.')
        if not self.fixture:
            bridge = Path(self.env.get('ONIONALL_BRIDGES_FILE', ROOT / '.secrets/m2/bridges.conf')).resolve()
            if not bridge.is_file():
                raise RuntimeError('Set ONIONALL_BRIDGES_FILE to your private obfs4 bridge file; '
                                   'see docker/m2/bridges.example.conf. No direct Tor fallback.')
            self.env['ONIONALL_BRIDGES_FILE'] = str(bridge)
        transport = 'socks-fixture' if self.fixture else 'tor'
        # Tor bootstrap has a bounded 120s wait; failure leaves state and diagnostics intact.
        self.dc('up', '-d', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '120',
                'frontier', transport, timeout=150)
        return self.status()

    def status(self):
        self.check_docker()
        self.dc('ps', '--all')
        self.dc('run', '--rm', '--no-deps', 'probe', 'frontier', 'frontier', '7071', '/state/admission-token',
                timeout=35)
        if not self.fixture:
            self.dc('exec', '-T', 'tor', 'python3', '/usr/local/lib/tor-health.py', timeout=15)
        self.dc('run', '--rm', '--no-deps', 'probe', 'socks',
                'socks-fixture' if self.fixture else 'tor', '9050', timeout=25)
        # Exercise the packaged CLI and installed Storm, not just container state.
        self.dc('run', '--rm', '--no-deps', 'crawler', '--help', capture=True, timeout=25)
        self.dc('run', '--rm', '--no-deps', '--entrypoint', '/apache-storm/bin/storm',
                'crawler', 'version', timeout=25)
        print(json.dumps(dict(frontier='READY_AUTHENTICATED', crawler='PREPARED',
                              tor='NOT_RUN_FIXTURE' if self.fixture else 'BOOTSTRAP_100_SOCKS_READY',
                              data=str(self.state), project=self.project), indent=2))
        if self.fixture:
            print('Controlled seed: http://' + fixture_host(1) + '/a')
        return 0

    def cli(self, args):
        if not args:
            args = ['--help']
        # Deployment defaults only. Original CLI still owns plan/approval/limits and exit codes.
        if args[:2] in (['crawl', 'start'], ['crawl', 'status']):
            if '--runtime' not in args:
                args += ['--runtime', '/state/runtime.json']
            if '--admission-token-file' not in args:
                args += ['--admission-token-file', '/state/admission-token']
        self.check_docker()
        self.dc('run', '--rm', '--no-deps', 'crawler', *args, timeout=740)
        return 0

    def down(self):
        self.check_docker()
        # Compose run creates one-off containers. Select them by BOTH ownership
        # labels and stop their immutable container IDs before stopping Frontier.
        workers = execute(['docker', 'ps', '-q', '--filter',
                           f'label=com.docker.compose.project={self.project}', '--filter',
                           'label=com.docker.compose.service=crawler'], capture=True).stdout.split()
        if workers:
            execute(['docker', 'stop', '--time', '120', *workers], timeout=150)
        self.dc('down', '--timeout', '30', timeout=90)
        print('Stopped; Frontier, Tor identity, archive and execution data retained.')
        return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture', action='store_true', help='Isolated synthetic SOCKS graph; no real Tor')
    commands = parser.add_subparsers(dest='command', required=True)
    for name in ('up', 'status', 'down'):
        commands.add_parser(name)
    cli = commands.add_parser('cli', help='Forward commands to the existing packaged CLI')
    cli.add_argument('args', nargs=argparse.REMAINDER)
    args = parser.parse_args(argv)
    runtime = Runtime(args.fixture)
    try:
        if args.command == 'cli':
            return runtime.cli(args.args)
        if args.command == 'down':
            return runtime.down()
        return getattr(runtime, args.command)()
    except subprocess.CalledProcessError as error:
        print(f'{args.command} failed (exit {error.returncode}). Data retained at {runtime.state}. '
              'For runtime failures check status and private bridge configuration; '
              'for build failures inspect the preceding compiler/test output.', file=sys.stderr)
        return error.returncode if error.returncode > 0 else 1
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
