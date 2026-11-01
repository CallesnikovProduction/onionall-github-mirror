"""Prepare offline M3 inputs only; deliberately does not start a blocked Tor gate."""
import argparse
import json
from pathlib import Path
import re
ROOT=Path(__file__).resolve().parents[2]

def prepare(directory,hosts):
    directory=directory.resolve()
    if not directory.is_relative_to(ROOT/'.runtime/m3') or directory.exists():
        raise ValueError('Use a new ignored M3 directory')
    if len(set(hosts))!=3 or not all(re.fullmatch(r'[a-z2-7]{56}\.onion',h) for h in hosts):
        raise ValueError('Three distinct owned Tor v3 hostnames required; runtime validates checksum')
    directory.mkdir(parents=True)
    config={'tor.mode':'ONLY','tor.socks.host':'tor','tor.socks.port':9050,'tor.allowed.hosts':hosts,
        'http.protocol.implementation':'dev.onionall.brawler.tor.TorProtocol',
        'https.protocol.implementation':'dev.onionall.brawler.tor.TorProtocol',
        'protocol.md.prefix':'protocol.','http.timeout':20000,'http.content.limit':65536,
        'http.agent.name':'OnionallBrawlerMThreeTest','http.robots.file.skip':False,
        'http.allow.redirects':False,'http.trust.everything':False,
        'discovery.max.pages':10,'discovery.max.depth':3,'discovery.max.links':100,
        'metadata.persist':['discovery.*','fetch.exception','fetch.statusCode','protocol.tor.category'],
        'metadata.transfer':[], 'parser.emitOutlinks.max.per.page':100,
        'jsoup.treat.non.html.as.error':True,'parser.mimetype.whitelist':[],
        'sitemap.discovery':False,'fetchInterval.default':-1,'fetchInterval.fetch.error':120,
        'fetchInterval.error':-1,'max.fetch.errors':3,'topology.max.spout.pending':10,
        'urlfrontier.host':'frontier','urlfrontier.crawlid':'m3',
        'urlfrontier.max.buckets':3,'urlfrontier.max.urls.per.bucket':2,
        'fetcher.server.delay':2.0,'fetcher.server.delay.force':True,
        'parsefilters.config.file':'m3-parsefilters.json','urlfilters.config.file':'m3-urlfilters.json'}
    (directory/'crawler-conf.yaml').write_text(json.dumps({'config':config},indent=2))
    (directory/'m3-parsefilters.json').write_text(json.dumps({'org.apache.stormcrawler.parse.ParseFilters':[
        {'class':'dev.onionall.brawler.discovery.DiscoveryParseFilter','name':'M3Discovery','params':{}}]},indent=2))
    (directory/'m3-urlfilters.json').write_text(json.dumps({'org.apache.stormcrawler.filtering.URLFilters':[
        {'class':'dev.onionall.brawler.tor.TorScopeFilter','name':'TorScope','params':{}}]},indent=2))
    topology=(ROOT/'crawler.flux').read_text().replace('name: "crawler"','name: "crawler-m3"',1)
    topology=topology.replace('file: "crawler-conf.yaml"','file: '+json.dumps(str(directory/'crawler-conf.yaml')),1)
    (directory/'topology.flux').write_text(topology)
    (directory/'manifest.json').write_text(json.dumps({'m3_verified':False,'seed':'http://'+hosts[0]+'/',
        'owned_hosts':hosts,'blocker':'M2 gate, durable global budget, pre-body MIME gate and live topology delivery unverified'},indent=2))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('directory',type=Path);p.add_argument('hosts',nargs=3);a=p.parse_args();prepare(a.directory,a.hosts)
