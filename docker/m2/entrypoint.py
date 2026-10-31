"""Tor client launcher: private runtime bridge config and allowlisted diagnostics only."""
import base64
import ipaddress
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import tempfile

SECRET = Path('/run/secrets/tor-bridges')


def bridge_lines(text):
    if len(text) > 65536:
        raise ValueError('Bridge configuration is too large')
    lines = []
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith('#'):
            continue
        parts = line.split()
        if len(parts) != 6 or parts[:2] != ['Bridge', 'obfs4']:
            raise ValueError('Use Bridge obfs4 lines only; see bridges.example.conf')
        address = parts[2]
        try:
            host, port = address.rsplit(':', 1)
            ipaddress.ip_address(host.strip('[]'))
            if not 1 <= int(port) <= 65535:
                raise ValueError()
            if not re.fullmatch(r'[A-Fa-f0-9]{40}', parts[3]):
                raise ValueError()
            if not parts[4].startswith('cert=') or parts[5] not in {'iat-mode=0', 'iat-mode=1', 'iat-mode=2'}:
                raise ValueError()
            cert = parts[4][5:]
            if len(base64.b64decode(cert + '=' * (-len(cert) % 4), validate=True)) != 52:
                raise ValueError()
        except (ValueError, TypeError):
            raise ValueError('Invalid obfs4 bridge entry; check address, fingerprint and certificate') from None
        lines.append(' '.join(parts))
    if not 1 <= len(lines) <= 32:
        raise ValueError('Provide between 1 and 32 private Bridge obfs4 lines')
    return lines


def command_args(args):
    source = Path('/etc/tor/torrc.gateway')
    result = []
    allowed = {'--DataDirectory', '--SocksPort', '--ControlPort', '--ControlSocket',
               '--CookieAuthFile', '--DisableNetwork'}
    while args:
        key = args.pop(0)
        if not args:
            raise ValueError('Invalid Tor runtime arguments')
        value = args.pop(0)
        if key == '-f':
            if value not in {'/etc/tor/torrc.gateway', '/etc/tor/torrc.onion'}:
                raise ValueError('Only the gateway or onion client configuration is supported')
            source = Path(value)
        elif key in allowed:
            result += [key, value]
        else:
            raise ValueError('Unsupported Tor runtime override')
    return source, result


def main():
    os.umask(0o077)
    try:
        with SECRET.open(encoding='utf-8-sig') as secret:
            lines = bridge_lines(secret.read(65537))
        source, overrides = command_args(sys.argv[1:])
    except OSError:
        print('TOR_CONFIG_ERROR: mount a readable private M2_BRIDGES_FILE; no direct fallback', flush=True)
        return 1
    except UnicodeError:
        print('TOR_CONFIG_ERROR: bridge file must contain UTF-8 text; values withheld', flush=True)
        return 1
    except ValueError as error:
        print('TOR_CONFIG_ERROR: ' + str(error), flush=True)
        return 1
    with tempfile.TemporaryDirectory(prefix='onionall-tor-') as directory:
        config = Path(directory) / 'torrc'
        config.write_text(source.read_text() + '\nUseBridges 1\nClientTransportPlugin obfs4 exec /usr/bin/obfs4proxy\n'
                          + '\n'.join(lines) + '\n', encoding='utf-8')
        config.chmod(0o600)
        # Tor validates a Unix ControlSocket parent before creating DataDirectory.
        # Preflight overrides use a fresh private directory under /tmp.
        options = dict(zip(overrides[::2], overrides[1::2]))
        if '--DataDirectory' in options:
            data = Path(options['--DataDirectory'])
            data.mkdir(mode=0o700, parents=True, exist_ok=True)
        command = ['tor', '-f', str(config)] + overrides
        check = subprocess.run(command + ['--verify-config'], capture_output=True, timeout=15)
        if check.returncode:
            print('TOR_CONFIG_ERROR: private Tor configuration rejected; values withheld', flush=True)
            return 1
        print('TOR_CONFIG_VALID: obfs4 required; no direct fallback', flush=True)
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors='replace')
        def stop(signum, frame):
            if process.poll() is None:
                process.send_signal(signum)
        signal.signal(signal.SIGTERM, stop)
        signal.signal(signal.SIGINT, stop)
        for line in process.stdout:
            match = re.search(r'Bootstrapped (\d+)%', line)
            if match:
                print('Bootstrapped ' + match[1] + '%', flush=True)
            elif 'Registered client transport' in line and 'obfs4' in line:
                print('TOR_TRANSPORT_STARTED: obfs4', flush=True)
            elif '[err]' in line.lower():
                print('TOR_ERROR: private diagnostic details withheld', flush=True)
            elif '[warn]' in line.lower():
                print('TOR_WARNING: private diagnostic details withheld', flush=True)
        return process.wait()


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (OSError, subprocess.SubprocessError):
        print('TOR_STARTUP_ERROR: check image binaries and private configuration; values withheld', flush=True)
        raise SystemExit(1)
