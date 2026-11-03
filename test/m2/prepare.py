"""Generate a bounded onion-only topology from the unchanged M0 graph."""
import argparse
import json
import os
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'test/m1'))
from prepare import topology


def prepare(run_dir):
    run_dir = run_dir.resolve()
    if not run_dir.is_relative_to(ROOT / '.runtime/m2'):
        raise ValueError('Evidence must remain under .runtime/m2')
    host = (run_dir / 'onion-hostname.txt').read_text().strip()
    if not re.fullmatch(r'[a-z2-7]{56}\.onion', host):
        raise ValueError('Unexpected generated onion hostname')
    config = (ROOT / 'test/m1/crawler-conf.yaml').read_text()
    config = config.replace('urlfrontier.crawlid: "m1"', 'urlfrontier.crawlid: "m2"')
    config = config.replace('http.timeout: 5000', 'http.timeout: 20000')
    config = '\n'.join(line for line in config.splitlines() if 'http.filter.ipaddress.include' not in line)
    config = config.replace('OnionallBrawlerMOneTest', 'OnionallBrawlerMTwoTest')
    config = config.replace('http://fixture:8080/', f'http://{host}/')
    config = config.replace('urlfilters.config.file: "m1-urlfilters.json"', 'urlfilters.config.file: "m2-urlfilters.json"')
    config = config.replace('    - fetch.exception', '    - fetch.exception\n    - protocol.tor.category')
    config += '\n' + '\n'.join([
        '  protocol.md.prefix: "protocol."',
        '  http.protocol.implementation: "dev.onionall.brawler.tor.TorProtocol"',
        '  https.protocol.implementation: "dev.onionall.brawler.tor.TorProtocol"',
        '  storm.log.dir: "/tmp/storm-logs"', '  storm.local.dir: "/tmp/storm-local"', '  dev.zookeeper.path: "/tmp/storm-zookeeper"',
        '  http.trust.everything: false', '  http.allow.redirects: false', '  http.robots.file.skip: false',
        '  tor.mode: "ONLY"', '  tor.socks.host: "tor"', '  tor.socks.port: 9050',
        f'  tor.allowed.hosts: [{json.dumps(host)}]',
    ]) + '\n'
    if os.environ.get('M2_TOR_DIAGNOSTICS') == '1':
        config += '  tor.diagnostics.enabled: true\n'
    (run_dir / 'crawler-conf.yaml').write_text(config, encoding='utf-8')
    relative = run_dir.relative_to(ROOT).as_posix()
    derived = topology((ROOT / 'crawler.flux').read_text()).replace('crawler-m1', 'crawler-m2')
    derived = derived.replace('test/m1/crawler-conf.yaml', f'{relative}/crawler-conf.yaml')
    (run_dir / 'topology.flux').write_text(derived, encoding='utf-8')
    outage_config=config.replace('fetchInterval.fetch.error: 120','fetchInterval.fetch.error: 1')
    outage_config=outage_config.replace('OnionallBrawlerMTwoTest','OnionallBrawlerMTwoOutage')
    (run_dir / 'crawler-outage-conf.yaml').write_text(outage_config, encoding='utf-8')
    (run_dir / 'topology-outage.flux').write_text(derived.replace(f'{relative}/crawler-conf.yaml',f'{relative}/crawler-outage-conf.yaml'),encoding='utf-8')
    resources = run_dir / 'resources'
    resources.mkdir(exist_ok=True)
    filters = {'org.apache.stormcrawler.filtering.URLFilters': [
        {'class': 'org.apache.stormcrawler.filtering.basic.BasicURLNormalizer', 'name': 'Normalizer',
         'params': {'removeAnchorPart': True, 'checkValidURI': True, 'removeHashes': True}},
        {'class': 'dev.onionall.brawler.tor.TorScopeFilter', 'name': 'TorScope', 'params': {}},
        {'class': 'org.apache.stormcrawler.filtering.basic.SelfURLFilter', 'name': 'Self', 'params': {}},
    ]}
    (resources / 'm2-urlfilters.json').write_text(json.dumps(filters), encoding='utf-8')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('run_dir', type=Path)
    prepare(parser.parse_args().run_dir)
