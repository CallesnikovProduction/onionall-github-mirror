import json,tempfile,unittest
from pathlib import Path
from socks_incident import cold_gateway_override

class ColdGatewayTest(unittest.TestCase):
    def test_override_only_changes_gateway_directory_and_retains_original(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)
            project='onionall-brawler-m2-123456abcdef'
            override=cold_gateway_override(path,project)
            data=json.loads(override.read_text())
            self.assertEqual({'services':{'tor':{'command':['--DataDirectory','/var/lib/tor/client-generations/123456abcdef']}}},data)
            with self.assertRaises(FileExistsError):cold_gateway_override(path,project)
    def test_unowned_names_rejected_without_creating_override(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)
            for project in ('other','onionall-brawler-m2-../../keys','onionall-brawler-m2-ABCDEF123456'):
                with self.assertRaises(ValueError):cold_gateway_override(path,project)
            self.assertEqual([],list(path.iterdir()))
