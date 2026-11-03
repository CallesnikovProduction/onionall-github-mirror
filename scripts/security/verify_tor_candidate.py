"""Verify a manually transferred Tor APT bundle offline, then stage build inputs."""
import argparse
from datetime import datetime, timezone, timedelta
from email.utils import parsedate_to_datetime
import hashlib
from pathlib import Path
import shutil
import subprocess
import tempfile

VERSION = '0.4.9.15-1~d12.bookworm+1'
SHA256 = 'eca04d5ab2c6285601526419b3d9ca1a25bec477abb25360f4c97da92d79c88e'
FINGERPRINT = 'A3C4F0F979CAA22CDBA8F512EE8CBC9E886DDD89'
PACKAGE = f'tor_{VERSION}_amd64.deb'
ROOT = Path(__file__).resolve().parents[2]


def fields(text):
    result = {}
    current = None
    for line in text.splitlines():
        if line.startswith((' ', '\t')) and current:
            result[current] += '\n' + line.strip()
        elif ':' in line:
            current, value = line.split(':', 1)
            result[current] = value.strip()
    return result


def verify(bundle, destination):
    bundle = bundle.resolve()
    destination = destination.resolve()
    if not destination.is_relative_to(ROOT / '.runtime') or destination.exists():
        raise ValueError('Use a new ignored .runtime/ build directory')
    with tempfile.TemporaryDirectory(prefix='tor-signature-') as directory:
        temporary = Path(directory)
        key = subprocess.run(['gpg', '--homedir', directory, '--batch', '--with-colons',
            '--import-options', 'show-only', '--import', str(bundle / 'tor-signing-key.asc')],
            check=True, capture_output=True, text=True).stdout
        # Require one primary key with the independently documented fingerprint.
        primaries = []
        pending = False
        for line in key.splitlines():
            parts = line.split(':')
            if parts[0] == 'pub':
                pending = True
            elif parts[0] == 'fpr' and pending:
                primaries.append(parts[9]); pending = False
        if primaries != [FINGERPRINT]:
            raise ValueError('Unexpected Tor repository signing key')
        subprocess.run(['gpg', '--homedir', directory, '--batch', '--dearmor', '--output',
            str(temporary / 'keyring.gpg'), str(bundle / 'tor-signing-key.asc')], check=True)
        subprocess.run(['gpgv', '--homedir', directory, '--keyring', str(temporary / 'keyring.gpg'),
            '--output', str(temporary / 'Release'), str(bundle / 'InRelease')], check=True)
        release = fields((temporary / 'Release').read_text())
        if release.get('Codename') != 'bookworm':
            raise ValueError('Unexpected signed repository codename')
        now = datetime.now(timezone.utc)
        date = parsedate_to_datetime(release['Date'])
        if date.tzinfo is None or not now - timedelta(days=7) <= date <= now + timedelta(minutes=5):
            raise ValueError('Signed index is stale or future-dated; obtain a current bundle')
        if 'Valid-Until' in release and parsedate_to_datetime(release['Valid-Until']) < now:
            raise ValueError('Signed index has expired')
        entries = [line.split() for line in release['SHA256'].splitlines() if line.strip()]
        matches = [row for row in entries if len(row) == 3 and row[2] == 'main/binary-amd64/Packages']
        index = (bundle / 'Packages').read_bytes()
        if len(matches) != 1 or matches[0][:2] != [hashlib.sha256(index).hexdigest(), str(len(index))]:
            raise ValueError('Packages does not match signed Release')
        packages = [fields(paragraph) for paragraph in index.decode().split('\n\n')]
        records = [record for record in packages if record.get('Package') == 'tor'
            and record.get('Version') == VERSION and record.get('Architecture') == 'amd64']
        data = (bundle / PACKAGE).read_bytes()
        if (len(records) != 1 or records[0].get('SHA256') != SHA256
                or records[0].get('Filename') != 'pool/main/t/tor/' + PACKAGE
                or records[0].get('Size') != str(len(data)) or hashlib.sha256(data).hexdigest() != SHA256):
            raise ValueError('Pinned package/index/checksum mismatch; do not install')
        destination.mkdir(parents=True)
        (destination / 'tor-update.deb').write_bytes(data)
        shutil.copy2(ROOT / 'docker/m2/Dockerfile.security-candidate', destination / 'Dockerfile')
        # Retain provenance for review; no private operational data is included.
        for name in ('tor-signing-key.asc', 'InRelease', 'Packages'):
            shutil.copy2(bundle / name, destination / name)
    print('Verified signed APT chain and pinned package; staged: ' + str(destination))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('bundle', type=Path)
    parser.add_argument('destination', type=Path)
    args = parser.parse_args()
    verify(args.bundle, args.destination)
