import json
from pathlib import Path
import tempfile
import unittest
from prepare import ROOT, prepare

class PrepareTest(unittest.TestCase):
    def test_config_preserves_provenance_without_claiming_durable_acceptance(self):
        (ROOT/'.runtime/m3').mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=ROOT/'.runtime/m3') as directory:
            target=Path(directory)/'session'
            prepare(target,[c*56+'.onion' for c in ('a','b','c')])
            conf=json.loads((target/'crawler-conf.yaml').read_text())['config']
            self.assertEqual('local-test',conf['discovery.budget.mode'])
            self.assertEqual('session',conf['discovery.crawl.session.id'])
            self.assertTrue(conf['tor.discovery.html.only'])
            self.assertGreater(conf['fetchInterval.default'],0)
            self.assertIn('discovery.*',conf['metadata.persist'])
            self.assertNotIn(str(ROOT),(target/'topology.flux').read_text())
            self.assertFalse(json.loads((target/'manifest.json').read_text())['m3_verified'])
