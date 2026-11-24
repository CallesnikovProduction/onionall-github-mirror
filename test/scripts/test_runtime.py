"""Independent packaged CLI build contract used by the canonical runtime."""
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]


class RuntimeBuildTest(unittest.TestCase):
    def test_build_targets_existing_crawler_project_and_packages_crawl_services(self):
        source = (ROOT / 'scripts/cli/build-cli.sh').read_text()
        self.assertIn('-f "$root/crawler/pom.xml"', source)
        self.assertIn('archive admission crawl research;', source)
        self.assertIn('kotlinx-coroutines-core-jvm-[0-9]*.jar', source)
        self.assertNotIn('-pl :onionall-brawler', source)


if __name__ == '__main__':
    unittest.main()
