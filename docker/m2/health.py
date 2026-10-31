"""Local cookie-authenticated bootstrap check; never print the cookie or destinations."""
from pathlib import Path
import socket
import sys


def check():
    running = False
    for process in Path('/proc').iterdir():
        if process.name.isdigit():
            try:
                running |= (process / 'comm').read_text().strip() == 'obfs4proxy'
            except OSError:
                pass
    if not running:
        raise RuntimeError('obfs4 client transport unavailable')
    cookie = Path('/var/lib/tor/control_auth_cookie').read_bytes()
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
        client.settimeout(2)
        client.connect('/var/lib/tor/control')
        stream = client.makefile('rwb', buffering=0)
        stream.write(b'AUTHENTICATE ' + cookie.hex().encode('ascii') + b'\r\n')
        if stream.readline() != b'250 OK\r\n':
            raise RuntimeError('control authentication unavailable')
        stream.write(b'GETINFO status/bootstrap-phase\r\n')
        response = stream.readline(4096)
        if not response.startswith(b'250-status/bootstrap-phase=') or b'PROGRESS=100 ' not in response:
            raise RuntimeError('Tor bootstrap incomplete')
        if stream.readline(4096) != b'250 OK\r\n':
            raise RuntimeError('invalid bootstrap response')
        stream.write(b'GETCONF UseBridges\r\n')
        if stream.readline(4096) != b'250 UseBridges=1\r\n':
            raise RuntimeError('bridge-only policy unavailable')


if __name__ == '__main__':
    try:
        check()
    except (OSError, RuntimeError) as error:
        # Messages deliberately exclude authentication material and remote hosts.
        print('Tor not ready: ' + type(error).__name__, file=sys.stderr)
        sys.exit(1)
