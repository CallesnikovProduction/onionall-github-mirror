"""Verify CLI rejection and staging boundaries with fixture-only GnuPG commands.

Authors: Стефан Колесников and GPT-6.1 Sol.
"""

from datetime import datetime, timezone
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

FINGERPRINT = 'A3C4F0F979CAA22CDBA8F512EE8CBC9E886DDD89'
PACKAGE = 'tor_0.4.9.15-1~d12.bookworm+1_amd64.deb'
PIN = 'eca04d5ab2c6285601526419b3d9ca1a25bec477abb25360f4c97da92d79c88e'
TOOLING = os.environ.get('ONIONALL_TOOLING') or shutil.which('onionall-tooling')

FAKE_GPG = r'''
import os
from pathlib import Path
import sys

mode = Path(sys.argv[0]).name
args = sys.argv[1:]
step = 'show' if '--with-colons' in args else 'verify' if mode == 'gpgv' else 'dearmor'
with open(os.environ['TEST_GPG_TRACE'], 'a') as trace:
    trace.write(step + '\n')
if step == 'show':
    print('pub::::::::::')
    print('fpr:::::::::' + os.environ['TEST_PRIMARY'] + ':')
elif step == 'verify':
    if os.environ['TEST_BAD_SIGNATURE'] == '1':
        raise SystemExit(8)
    Path(args[args.index('--output') + 1]).write_bytes(Path(os.environ['TEST_RELEASE_FIXTURE']).read_bytes())
'''


def exercise(command, scenario, reference=None):
    """Record normalized process effects independently of error wording."""
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        bundle = root / 'bundle'
        bundle.mkdir()
        bin_dir = root / 'bin'
        bin_dir.mkdir()
        for name in ['gpg', 'gpgv']:
            path = bin_dir / name
            path.write_text('#!' + sys.executable + '\n' + FAKE_GPG, encoding='utf-8')
            path.chmod(0o700)
        env = dict(os.environ, PATH=str(bin_dir),
                   TEST_GPG_TRACE=str(root / 'trace'),
                   TEST_RELEASE_FIXTURE=str(root / 'release-fixture'),
                   TEST_PRIMARY='WRONG' if scenario == 'wrong-key' else FINGERPRINT,
                   TEST_BAD_SIGNATURE=str(int(scenario == 'wrong-signature')))
        payload = b'untrusted candidate fixture'
        index = (f'Package: tor\nVersion: 0.4.9.15-1~d12.bookworm+1\nArchitecture: amd64\n'
                 f'Filename: pool/main/t/tor/{PACKAGE}\nSize: {len(payload)}\nSHA256: {PIN}\n').encode()
        index_hash = '0' * 64 if scenario == 'wrong-index' else hashlib.sha256(index).hexdigest()
        date = ('Tue, 01 Jan 2019 12:00:00 +0000' if scenario == 'stale'
                else datetime.now(timezone.utc).strftime('%a, %d %b %Y %H:%M:%S +0000'))
        (root / 'release-fixture').write_text(
            f'Codename: bookworm\nDate: {date}\nSHA256:\n {index_hash} {len(index)} main/binary-amd64/Packages\n')
        for name, data in [('tor-signing-key.asc', b'test-only'), ('InRelease', b'test-only'),
                           ('Packages', index), (PACKAGE, payload)]:
            (bundle / name).write_bytes(data)
        destination = root / ('outside' if scenario == 'outside' else '.runtime/stage')
        if scenario == 'exists':
            destination.mkdir(parents=True)
            (destination / 'evidence').write_bytes(b'kept')
        if reference is not None:
            script = root / 'scripts/security/reference.py'
            script.parent.mkdir(parents=True)
            shutil.copyfile(reference, script)
            command = [sys.executable, str(script)]
        result = subprocess.run([*command, str(bundle), str(destination)], cwd=root, env=env,
                                capture_output=True, text=True, timeout=8)
        trace = root / 'trace'
        return dict(exit_code=result.returncode, calls=trace.read_text().splitlines() if trace.exists() else [],
                    artifacts=sorted(p.name for p in destination.iterdir()) if destination.exists() else [],
                    original_bytes=(destination / 'evidence').read_text() if scenario == 'exists' else None)


@unittest.skipUnless(os.name == 'posix' and TOOLING, 'Requires the Linux Rust tooling executable')
class CandidateTest(unittest.TestCase):
    def test_original_rejection_contracts(self):
        for scenario, calls in {
            'outside': [], 'exists': [], 'wrong-key': ['show'],
            'wrong-signature': ['show', 'dearmor', 'verify'],
            'wrong-index': ['show', 'dearmor', 'verify'],
            'wrong-package': ['show', 'dearmor', 'verify'],
            'stale': ['show', 'dearmor', 'verify'],
        }.items():
            with self.subTest(scenario=scenario):
                observed = exercise([TOOLING, 'verify-tor-candidate'], scenario)
                self.assertEqual(observed, dict(exit_code=1, calls=calls,
                    artifacts=['evidence'] if scenario == 'exists' else [],
                    original_bytes='kept' if scenario == 'exists' else None))
