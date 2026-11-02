"""Keep the actual M2 metadata merge boundary namespaced in both topologies."""
import importlib.util
from pathlib import Path
import re
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('m2_prepare_config', Path(__file__).with_name('prepare.py'))
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class MetadataPrefixTest(unittest.TestCase):
    def test_both_generated_configs_pin_stormcrawler_metadata_prefix(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'test/m1').mkdir(parents=True)
            for name in ('crawler.flux', 'test/m1/crawler-conf.yaml'):
                (root / name).write_text((ROOT / name).read_text(), encoding='utf-8')
            evidence = root / '.runtime/m2/config-test'
            evidence.mkdir(parents=True)
            (evidence / 'onion-hostname.txt').write_text('a' * 56 + '.onion', encoding='utf-8')
            with patch.object(prepare, 'ROOT', root), patch.dict(prepare.os.environ, {'M2_TOR_DIAGNOSTICS':'0'}):
                prepare.prepare(evidence)
            for name in ('crawler-conf.yaml', 'crawler-outage-conf.yaml'):
                values = re.findall(r'^\s+protocol\.md\.prefix:\s*(.+)$',
                                    (evidence / name).read_text(), re.MULTILINE)
                self.assertEqual(['"protocol."'], values)
                self.assertIn('    - protocol.tor.category', (evidence / name).read_text())
                self.assertNotIn('    - tor.category', (evidence / name).read_text())
    def test_diagnostics_are_explicit_opt_in_in_both_topologies(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            (root/'test/m1').mkdir(parents=True)
            for name in ('crawler.flux','test/m1/crawler-conf.yaml'):
                (root/name).write_text((ROOT/name).read_text())
            for enabled in ('0','1'):
                evidence=root/'.runtime/m2'/('config-'+enabled);evidence.mkdir(parents=True)
                (evidence/'onion-hostname.txt').write_text('a'*56+'.onion')
                with patch.object(prepare,'ROOT',root), patch.dict(prepare.os.environ,{'M2_TOR_DIAGNOSTICS':enabled}):
                    prepare.prepare(evidence)
                for name in ('crawler-conf.yaml','crawler-outage-conf.yaml'):
                    self.assertEqual(enabled=='1','tor.diagnostics.enabled: true' in (evidence/name).read_text())


if __name__ == '__main__':
    unittest.main()
