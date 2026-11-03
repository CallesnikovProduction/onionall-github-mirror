"""Local deterministic graph and retry behavior, independent of public Tor."""
from pathlib import Path
import tempfile
import unittest
from socks_fixture import Fixture


class GraphTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        root = Path(self.directory.name)
        (root/'onion-hostname.txt').write_text('a'*56+'.onion')
        (root/'foreign-hostname.txt').write_text('b'*56+'.onion')
        self.fixture = Fixture(('127.0.0.1',0),root)

    def tearDown(self):
        self.fixture.server_close()
        self.directory.cleanup()

    def test_graph_has_cycle_duplicates_and_controlled_rejections(self):
        a = self.fixture.response('/a')[2].decode()
        self.assertIn('/b#same',a)
        self.assertIn('/blocked',a)
        self.assertIn('/dump.zip',a)
        self.assertEqual(503,self.fixture.response('/b')[0])
        b = self.fixture.response('/b')[2].decode()
        self.assertIn('ARCHIVE_BETA',b)
        self.assertIn('href="/a"',b)
        self.assertIn('/deep',self.fixture.response('/c')[2].decode())
        self.assertIn(b'Disallow: /blocked',self.fixture.response('/robots.txt')[2])

    def test_unexpected_paths_never_turn_into_generic_http_success(self):
        for path in ('/blocked','/dump.zip','/deep'):
            with self.assertRaises(ValueError):
                self.fixture.response(path)
