"""Real PTY presentation contracts; all networking uses an owned loopback fixture."""
import os
import unittest

if os.name != 'posix':
    raise unittest.SkipTest('Interactive PTY rendering tests require POSIX')

import fcntl
from pathlib import Path
import pty
import re
import select
import signal
import struct
import subprocess
import tempfile
import termios
import time

from test_distribution import BUNDLE, ROOT, SocksFixture, URL


class Screen:
    """Small replay model for the renderer's documented CUP/EL/ED/SGR/DECSTBM subset."""
    def __init__(self, width, height):
        self.width, self.height = width, height
        self.rows = [[' '] * width for _ in range(height)]
        self.x = self.y = 0
        self.top, self.bottom = 0, height - 1

    def newline(self):
        if self.y == self.bottom:
            del self.rows[self.top]
            self.rows.insert(self.bottom, [' '] * self.width)
        else:
            self.y = min(self.height - 1, self.y + 1)

    def replay(self, raw):
        text = raw.decode('utf-8', errors='replace')
        position = 0
        while position < len(text):
            if text.startswith('\x1b[', position):
                match = re.match(r'\x1b\[([0-9;?]*)([A-Za-z])', text[position:])
                if not match:
                    break  # An unfinished terminal write can be completed by the next capture.
                arguments, command = match.groups()
                values = [int(n) if n else 0 for n in arguments.lstrip('?').split(';')]
                if arguments == '?1049' and command == 'h':
                    self.saved = (self.rows, self.x, self.y, self.top, self.bottom)
                    self.rows = [[' '] * self.width for _ in range(self.height)]
                    self.x = self.y = self.top = 0
                    self.bottom = self.height - 1
                elif arguments == '?1049' and command == 'l':
                    self.rows, self.x, self.y, self.top, self.bottom = self.saved
                elif command in ('H', 'f'):
                    self.y = max(0, min(self.height - 1, (values[0] or 1) - 1))
                    self.x = max(0, min(self.width - 1, (values[1] if len(values) > 1 else 1) - 1))
                elif command == 'K':
                    if values[0] == 2:
                        self.rows[self.y] = [' '] * self.width
                    else:
                        self.rows[self.y][self.x:] = [' '] * (self.width - self.x)
                elif command == 'J' and values[0] == 2:
                    self.rows = [[' '] * self.width for _ in range(self.height)]
                elif command == 'r':
                    self.top = max(0, (values[0] or 1) - 1)
                    self.bottom = min(self.height - 1, (values[1] if len(values) > 1 else self.height) - 1)
                    self.x = self.y = 0
                position += match.end()
                continue
            char = text[position]
            if char == '\r':
                self.x = 0
            elif char == '\n':
                self.newline()
            elif char >= ' ' and char != '\x7f':
                if self.x >= self.width:
                    self.x = 0
                    self.newline()
                self.rows[self.y][self.x] = char
                self.x += 1
            position += 1
        return [''.join(row).rstrip() for row in self.rows]


class Terminal:
    def __init__(self, width=80, height=30, no_color=False, extra=()):
        self.width, self.height = width, height
        self.master, slave = pty.openpty()
        fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack('HHHH', height, width, 0, 0))
        env = dict(os.environ, TERM='xterm-256color', COLORTERM='truecolor',
                   COLUMNS=str(width), LINES=str(height))
        env.pop('NO_COLOR', None)
        if no_color:
            env['NO_COLOR'] = '1'
        self.process = subprocess.Popen([str(BUNDLE), *extra], cwd=ROOT, env=env,
                                        stdin=slave, stdout=slave, stderr=slave,
                                        start_new_session=True)
        os.close(slave)
        self.raw = bytearray()

    def drain(self, duration=.1):
        end = time.monotonic() + duration
        while time.monotonic() < end:
            if select.select([self.master], [], [], min(.05, max(0, end-time.monotonic())))[0]:
                try:
                    data = os.read(self.master, 65536)
                except OSError:
                    return
                if not data:
                    return
                self.raw.extend(data)

    def wait(self, token, after=0, timeout=8):
        end = time.monotonic() + timeout
        while token not in self.raw[after:] and time.monotonic() < end:
            self.drain(.05)
            if self.process.poll() is not None:
                self.drain(.05)
                break
        if token not in self.raw[after:]:
            raise AssertionError(f'Missing {token!r}; exit={self.process.poll()}, output={bytes(self.raw)!r}')
        self.drain(.06)

    def send(self, text):
        boundary = len(self.raw)
        os.write(self.master, text.encode())
        return boundary

    def screen(self):
        return Screen(self.width, self.height).replay(bytes(self.raw))

    def resize(self, width, height):
        self.width, self.height = width, height
        fcntl.ioctl(self.master, termios.TIOCSWINSZ, struct.pack('HHHH', height, width, 0, 0))
        self.process.send_signal(signal.SIGWINCH)

    def save(self, name):
        directory = ROOT / '.runtime/cli'
        directory.mkdir(parents=True, exist_ok=True)
        (directory / f'render-{name}.ansi').write_bytes(bytes(self.raw))
        (directory / f'render-{name}.txt').write_text('\n'.join(self.screen())+'\n')

    def close(self):
        if self.process.poll() is None:
            self.process.kill()
        self.process.wait(timeout=4)
        os.close(self.master)

    def __enter__(self):
        return self

    def __exit__(self, *unused):
        self.close()


@unittest.skipUnless(os.name == 'posix' and BUNDLE.is_file(), 'Build the Linux CLI first')
class RenderingTest(unittest.TestCase):
    def header(self, terminal):
        match = re.search(rb'\x1b\[(\d+);\d+r', terminal.raw)
        self.assertIsNotNone(match, 'A pinned session must set a dynamic scrolling region')
        return terminal.screen()[:int(match.group(1))-1]

    def test_three_layouts_keep_header_stationary_during_help_and_errors(self):
        for width in (80, 120, 50):
            with self.subTest(width=width), Terminal(width) as terminal:
                terminal.wait(b'Choose ')
                initial = self.header(terminal)
                self.assertIn(b'\x1b[95m(1)', terminal.raw)
                self.assertTrue(any('Tor-native fetch engine' in line for line in initial))
                self.assertTrue(all(len(line) < width for line in initial))
                boundary = terminal.send('4\n')
                terminal.wait(b'Enter to return >', boundary)
                self.assertEqual(initial, self.header(terminal))
                boundary = terminal.send('\n')
                terminal.wait(b'Choose ', boundary)
                self.assertEqual(initial, self.header(terminal))
                boundary = terminal.send('2\n')
                terminal.wait(b'File >', boundary)
                boundary = terminal.send('/nonexistent-onionall-render-test.html\n')
                terminal.wait(b'Enter to return >', boundary)
                self.assertIn(b'NoSuchFileException', terminal.raw[boundary:])
                self.assertEqual(initial, self.header(terminal))
                boundary = terminal.send('\n')
                terminal.wait(b'Choose ', boundary)
                self.assertEqual(initial, self.header(terminal))
                boundary = terminal.send('3\n')
                terminal.wait(b'Enter to return >', boundary)
                self.assertTrue(any('TCP reachable' in row or 'TCP unavailable' in row for row in terminal.screen()))
                self.assertTrue(any('NOT_TESTED' in row for row in terminal.screen()))
                self.assertEqual(initial, self.header(terminal))
                boundary = terminal.send('\n')
                terminal.wait(b'Choose ', boundary)
                self.assertEqual(1, terminal.raw.count(b'Tor-native fetch engine'))
                self.assertEqual(1, terminal.raw.count(b'\x1b[?1049h'))
                terminal.save(str(width))
                terminal.send('0\n')
                self.assertEqual(0, terminal.process.wait(timeout=4))

    def test_resize_reflows_only_after_size_changes(self):
        with Terminal(120) as terminal:
            terminal.wait(b'Choose ')
            for count, width in ((2, 80), (3, 50)):
                terminal.resize(width, 30)
                end = time.monotonic() + 4
                while terminal.raw.count(b'Tor-native fetch engine') < count and time.monotonic() < end:
                    terminal.drain(.1)
                self.assertEqual(count, terminal.raw.count(b'Tor-native fetch engine'))
                terminal.drain(.7)
                self.assertEqual(count, terminal.raw.count(b'Tor-native fetch engine'))
                self.assertEqual(1, terminal.raw.count(b'\x1b[?1049h'))
            terminal.save('resize-50')
            terminal.send('0\n')
            self.assertEqual(0, terminal.process.wait(timeout=4))

    def test_completed_fetch_result_fits_120_by_24_viewport(self):
        with tempfile.TemporaryDirectory() as directory, SocksFixture() as proxy:
            with Terminal(120, 24, extra=('--socks-port', str(proxy.port), '--timeout-ms', '1500',
                                         '--download-dir', directory)) as terminal:
                terminal.wait(b'Choose ')
                initial = self.header(terminal)
                boundary = terminal.send('1\n')
                terminal.wait(b'URL >', boundary)
                boundary = terminal.send(URL+'\n')
                terminal.wait(b'Enter to return >', boundary)
                visible = '\n'.join(terminal.screen())
                self.assertIn('http_status: 200', visible)
                self.assertIn('title: Fixture & Onion', visible)
                self.assertIn('Enter to return >', visible)
                self.assertEqual(initial, self.header(terminal))
                self.assertTrue((Path(directory)/'ahmia.html').is_file())
                self.assertEqual(1, proxy.connections)
                terminal.save('fetch-complete-120x24')
                boundary = terminal.send('\n')
                terminal.wait(b'Choose ', boundary)
                self.assertEqual(initial, self.header(terminal))
                self.assertEqual(1, terminal.raw.count(b'Tor-native fetch engine'))
                self.assertEqual(1, terminal.raw.count(b'\x1b[?1049h'))
                terminal.send('0\n')
                self.assertEqual(0, terminal.process.wait(timeout=4))

    def test_fetch_progress_and_sigint_preserve_header_and_restore_terminal(self):
        with tempfile.TemporaryDirectory() as directory, SocksFixture(hold=True) as proxy:
            with Terminal(80, extra=('--socks-port', str(proxy.port), '--timeout-ms', '3000',
                                     '--download-dir', directory)) as terminal:
                terminal.wait(b'Choose ')
                initial = self.header(terminal)
                boundary = terminal.send('1\n')
                terminal.wait(b'URL >', boundary)
                terminal.send(URL+'\n')
                self.assertTrue(proxy.accepted.wait(5), 'Fetch must reach only the owned SOCKS fixture')
                terminal.wait(b'Running fetch...')
                terminal.drain(.3)
                self.assertEqual(initial, self.header(terminal))
                self.assertEqual(1, terminal.raw.count(b'Tor-native fetch engine'))
                self.assertEqual(1, terminal.raw.count(b'\x1b[?1049h'))
                terminal.save('fetch-progress')
                terminal.process.send_signal(signal.SIGINT)
                self.assertEqual(130, terminal.process.wait(timeout=6))
                terminal.drain(.1)
                self.assertIn(b'\x1b[r\x1b[?25h', terminal.raw)
                self.assertFalse((Path(directory)/'ahmia.html').exists())

    def test_alternate_screen_restores_primary_on_quit_eof_interrupt_and_handled_error(self):
        for mode in ('quit', 'eof', 'interrupt', 'error'):
            with self.subTest(mode=mode), Terminal(80) as terminal:
                terminal.wait(b'Choose ')
                if mode == 'error':
                    terminal.send('2\n')
                    terminal.wait(b'File >')
                    terminal.send('/nonexistent/onionall-test.html\n')
                    terminal.wait(b'Enter to return >')
                    terminal.send('\n')
                    terminal.wait(b'Choose ', after=len(terminal.raw)-1)
                if mode == 'interrupt':
                    terminal.process.send_signal(signal.SIGINT)
                else:
                    terminal.send('\x04' if mode == 'eof' else '0\n')
                terminal.process.wait(timeout=6)
                terminal.drain(.1)
                self.assertEqual(1, terminal.raw.count(b'\x1b[?1049h'))
                self.assertEqual(1, terminal.raw.count(b'\x1b[?1049l'))
                self.assertNotIn(b'\x1b[2J', terminal.raw)
                self.assertNotIn(b'\x1b[3J', terminal.raw)
                before = b'previous shell output\r\n$ onionall'
                expected = Screen(terminal.width, terminal.height).replay(before)
                restored = Screen(terminal.width, terminal.height).replay(before + bytes(terminal.raw))
                self.assertEqual(expected, restored)

    def test_no_color_stays_linear_without_terminal_escape_codes(self):
        with Terminal(80, no_color=True) as terminal:
            terminal.wait(b'Choose ')
            boundary = terminal.send('4\n')
            terminal.wait(b'Enter to return >', boundary)
            boundary = terminal.send('\n')
            terminal.wait(b'Choose ', boundary)
            self.assertEqual(1, terminal.raw.count(b'Tor-native fetch engine'))
            self.assertNotIn(b'\x1b', terminal.raw)
            terminal.send('0\n')
            self.assertEqual(0, terminal.process.wait(timeout=4))


if __name__ == '__main__':
    unittest.main()
