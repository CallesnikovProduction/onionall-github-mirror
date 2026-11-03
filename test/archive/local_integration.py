"""Controlled local crawl→archive vertical, explicitly NOT durable admission/Tor acceptance.

Run after scripts/cli/build-cli.sh with JDK25 and M2_STORM_IMAGE=...cleanup2.
Uses actual CLI approval, StormCrawler/TorProtocol/Frontier and an internal SOCKS
fixture. Only local-test discovery is enabled; no external Tor or host ports.
"""
import base64
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts'))
from runners.tor_acceptance_runner import Runner
from restart_recovery import host_state
from deterministic_recovery import cleanup_project


def require(condition,message):
    if not condition:
        raise RuntimeError(message)


def onion(number):
    key=bytes([number])*32
    payload=key+hashlib.sha3_256(b'.onion checksum'+key+b'\3').digest()[:2]+b'\3'
    return base64.b32encode(payload).decode().lower()+'.onion'


def main():
    r=Runner(); p=r.path
    result=dict(passed=False,m2_verified=False,m3_verified=False,
                transport='internal controlled SOCKS with production TorProtocol',
                admission='local-test diagnostic only',durable_admission_verified=False)
    before=host_state();r.save('host-before.json',before)
    def dc(args,name,timeout=60,**kwargs):
        print(name,flush=True)
        return r.dc(args,name+'.log',timeout=timeout,**kwargs)
    def cli(args,name,expected=0):
        command=[str(ROOT/'target/onionall/bin/onionall'),'--json','--archive-dir',str(p/'archive'),*args]
        process=subprocess.run(command,cwd=ROOT,env=r.environment,capture_output=True,text=True,timeout=30)
        (p/(name+'.log')).write_text(process.stdout+process.stderr)
        require(process.returncode==expected,name+' unexpected CLI exit; inspect '+name+'.log')
        value=json.loads(process.stdout)
        r.save(name+'.json',value)
        return value
    def snapshot(name):
        dc(['run','--rm','--no-deps','probe','snapshot',name+'.json'],name)
        return json.loads((p/(name+'.json')).read_text())
    def interrupted(signum,frame):
        raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM,interrupted);signal.signal(signal.SIGINT,interrupted)
    try:
        r.validate_toolchain()
        host=onion(1);foreign=onion(2);seed='http://'+host+'/a'
        (p/'onion-hostname.txt').write_text(host)
        (p/'foreign-hostname.txt').write_text(foreign)
        plan=cli(['crawl','plan','--seed',seed,'--scope','same-host','--depth','2','--max-pages','4','--provider','controlled-fixture'],'plan')
        job=plan['plan_id']
        approved=cli(['crawl','approve',job],'approve')
        require(approved['approved'] is True,'Plan not explicitly approved')
        cli(['crawl','start','--plan',job],'production-start-blocked',expected=7)
        r.command([sys.executable,'-B','test/m2/prepare.py',p],'prepare.log',timeout=20)
        config=(p/'crawler-conf.yaml').read_text()
        config=config.replace('http.timeout: 20000','http.timeout: 2000').replace('tor.socks.host: "tor"','tor.socks.host: "socks-fixture"')
        config=config.replace('fetchInterval.fetch.error: 120','fetchInterval.fetch.error: 1')
        config=config.replace('urlfilters.config.file: "m2-urlfilters.json"','urlfilters.config.file: "archive-urlfilters.json"')
        config=config.replace('    - fetch.exception','    - fetch.exception\n    - discovery.*')
        config+='\n'+ '\n'.join([
            '  parsefilters.config.file: "archive-parsefilters.json"',
            '  discovery.budget.mode: "local-test"', '  discovery.crawl.session.id: '+json.dumps(job),
            '  discovery.max.pages: 4','  discovery.max.depth: 2','  discovery.max.links: 100',
            '  parser.emitOutlinks.max.per.page: 100','  tor.discovery.html.only: true',
            '  archive.root: "/evidence/archive"','  archive.job.id: '+json.dumps(job)])+'\n'
        (p/'crawler-conf.yaml').write_text(config)
        topology=(p/'topology.flux').read_text().replace('org.apache.stormcrawler.urlfrontier.StatusUpdaterBolt','dev.onionall.brawler.archive.ArchiveStatusUpdaterBolt')
        (p/'topology.flux').write_text(topology)
        parse={'org.apache.stormcrawler.parse.ParseFilters':[
            {'class':'dev.onionall.brawler.discovery.DiscoveryParseFilter','name':'Discovery','params':{}},
            {'class':'dev.onionall.brawler.archive.ArchiveParseFilter','name':'Archive','params':{}}]}
        filters={'org.apache.stormcrawler.filtering.URLFilters':[
            {'class':'dev.onionall.brawler.discovery.DiscoveryScopeFilter','name':'Scope','params':{}}]}
        (p/'resources/archive-parsefilters.json').write_text(json.dumps(parse))
        (p/'resources/archive-urlfilters.json').write_text(json.dumps(filters))
        artifact=ROOT/'target/onionall-brawler-0.1.0-SNAPSHOT.jar'
        shutil.copy2(artifact,p/'crawler-m2.jar')
        r.save('artifact.json',dict(sha256=hashlib.sha256(artifact.read_bytes()).hexdigest(),plan_id=job))
        # Diagnostic-only seed probe: exact approved /a, no arbitrary target input.
        probe=(ROOT/'test/m2/FrontierProbe.java').read_text().replace('"pending"','"a"')
        (p/'FrontierProbe.java').write_text(probe)
        r.command([r.tool('javac'),'--release','25','-cp',artifact,'-d',p/'classes',p/'FrontierProbe.java'],'compile.log',timeout=30)
        r.command([r.tool('jar'),'--update','--file',p/'crawler-m2.jar','-C',p/'classes','.','-C',p/'resources','.'],'package.log',timeout=20)
        overrides={'services':{'socks-fixture':{
            'image':'python:3.12-slim','user':r.environment['M2_RUNTIME_USER'],
            'entrypoint':['python3','-B','/fixture.py'],
            'volumes':[str(ROOT/'test/archive/socks_fixture.py')+':/fixture.py:ro',str(p)+':/evidence'],
            'networks':['crawler'],'read_only':True,'cap_drop':['ALL'],
            'security_opt':['no-new-privileges:true'],'tmpfs':['/tmp']}}}
        override=p/'archive-compose.json';override.write_text(json.dumps(overrides));r.compose+=['-f',str(override)]
        composed=json.loads(dc(['--profile','tools','config','--format','json'],'config',capture=True).stdout)
        r.save('compose.json',composed)
        require(composed['networks']['crawler']['internal'],'Internal network required')
        require(all(set(composed['services'][name]['networks'])=={'crawler'} and not composed['services'][name].get('ports')
                    for name in ('crawler','frontier','socks-fixture')),'Unexpected egress/exposure')
        r.started=True
        dc(['up','-d','--no-build','--pull','never','--wait','--wait-timeout','40','frontier','socks-fixture'],'startup')
        dc(['run','--rm','--no-deps','probe','ready'],'frontier-ready')
        dc(['run','--rm','--no-deps','probe','seed','a'],'approved-seed')
        initial=snapshot('seeded')
        require([row['url'] for row in initial['entries']]==[seed],'Approved seed mismatch')
        dc(['run','--rm','--no-deps','--name',r.project+'-archive-worker','-e','M2_CRAWL_TTL=100','crawler'],'crawl',timeout=190)
        final=snapshot('frontier-final')
        by_url={row['url']:row for row in final['entries']}
        require(set(by_url)=={'http://'+host+path for path in ('/a','/b','/c','/blocked')},'Wrong discovery scope/count')
        require([by_url['http://'+host+path]['metadata'].get('discovery.depth',['0'])[0] for path in ('/a','/b','/c')]==['0','1','2'],'Depth provenance lost')
        traffic=[json.loads(line) for line in (p/'archive-requests.jsonl').read_text().splitlines()]
        http=[row for row in traffic if row['phase']=='http']
        counts=Counter(row['path'] for row in http)
        require(counts==Counter({'/robots.txt':1,'/a':1,'/b':2,'/c':1}),'Unexpected duplicate/cycle/robots/scope traffic')
        require([row['status'] for row in http if row['path']=='/b']==[503,200],'Scheduled retry not executed')
        require(all(row['atyp']==3 for row in http),'Remote SOCKS DNS contract missing')
        status=cli(['crawl','status',job],'status')
        require(status['archived_pages']==3 and status['snapshots']==3,'Catalog counts mismatch')
        require(any(row['url']=='http://'+host+'/b' and row['category']=='FETCH_ERROR'
                    for row in status['failure_records']),'Actual failed-fetch outcome disappeared')
        pages=cli(['pages','--job',job,'--limit','10'],'pages')
        search=cli(['search-local','ARCHIVE_GAMMA','--job',job],'search')
        result.update(crawl_path_passed=True,scope_depth_retry_verified=True,job_id=job,
                      frontier_records=len(final['entries']))
        verify_archive(p,host,job,pages,search,cli)
        dc(['stop','--timeout','10','frontier'],'frontier-stop')
        dc(['rm','-f','frontier'],'frontier-remove')
        dc(['up','-d','--no-deps','--no-build','--pull','never','--wait','--wait-timeout','40','frontier'],'frontier-recreate')
        restored=snapshot('frontier-restored')
        require(restored['entries']==final['entries'],'Frontier changed on recreation')
        reopened=cli(['pages','--job',job,'--limit','10'],'pages-after-restart')
        require(reopened==pages,'Archive changed after process/container recreation')
        cli(['archive','export','--job',job,'--output',str(p/'summary.json')],'export')
        result.update(passed=True,archive_reopen_verified=True)
    except (OSError,ValueError,KeyError,TypeError,RuntimeError,subprocess.SubprocessError,KeyboardInterrupt) as error:
        result['blocker']=type(error).__name__+': '+str(error)
    finally:
        signal.signal(signal.SIGTERM,signal.SIG_IGN);signal.signal(signal.SIGINT,signal.SIG_IGN)
        if r.started:
            try:
                r.command(['docker','stop','--time','5',r.project+'-archive-worker'],
                          'archive-worker-cleanup.log',allow_failure=True,timeout=20)
            except (OSError,subprocess.SubprocessError) as error:
                result.update(passed=False,worker_cleanup_blocker=str(error))
        cleanup_project(r,None,None,result)
        try:
            after=host_state();r.save('host-after.json',after)
            result['host_unchanged']=before==after;result['passed'] &= result['host_unchanged']
        except (OSError,ValueError,subprocess.SubprocessError) as error:
            result.update(passed=False,host_check_blocker=str(error))
        r.save('archive-local.json',result)
        print(p,flush=True);print(json.dumps(result,indent=2),flush=True)
    return 0 if result['passed'] else 1


def verify_archive(root,host,job,pages,search,cli):
    """Explicit archive schema assertions, independent of Frontier acceptance."""
    rows=pages['pages']
    require(len(rows)==3,'Three successful archived pages required')
    require({row['canonical_url'] for row in rows}=={'http://'+host+path for path in ('/a','/b','/c')},'Archive URL scope mismatch')
    require(len(search['results'])==1,'Local extracted-text search missing or duplicated')
    require(search['results'][0]['canonical_url']=='http://'+host+'/c','Local search returned wrong page')
    by_url={row['canonical_url']:row for row in rows}
    require([by_url['http://'+host+path]['depth'] for path in ('/a','/b','/c')]==[0,1,2],'Archive depth provenance mismatch')
    for row in rows:
        page=cli(['page','show',row['page_id']],'page-'+row['page_id'])
        require(page['job_id']==job,'Archive job provenance mismatch')
        snapshot=Path(page['storage_location'])
        require(snapshot.is_relative_to(root/'archive'),'Snapshot location outside archive')
        require(snapshot.is_file() and hashlib.sha256(snapshot.read_bytes()).hexdigest()==page['content_hash'],'Snapshot is incomplete or hash mismatch')
        require(snapshot.stat().st_size==page['content_size'] and page['http_status']==200,'Snapshot metadata mismatch')
        require(len(page['history'])==1,'Retry created duplicate snapshots')
        require(all(edge['source_url']==page['canonical_url'] for edge in page['links']),'Source-page link provenance mismatch')
        require('ARCHIVE_' in page['text'],'Extracted archive text missing')
        if page['canonical_url'].endswith('/a'):
            require(any(edge.get('target_url')=='http://'+host+'/b' for edge in page['links']),'A to B provenance missing')
        if page['canonical_url'].endswith('/c'):
            require(any(edge.get('disposition')=='DEPTH_LIMIT' for edge in page['links']),'Depth rejection outcome missing')
    require(not list((root/'archive').rglob('*.tmp')),'Partial snapshot exposed after normal completion')


if __name__=='__main__':
    raise SystemExit(main())
