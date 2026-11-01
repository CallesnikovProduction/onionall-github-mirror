"""Actual local Storm/frontier diagnostic. Internal-only HTTP; never Tor acceptance."""
import base64,hashlib,json,os,shutil,subprocess,sys,uuid,signal
from pathlib import Path
from prepare import prepare,ROOT

def main():
    project='onionall-brawler-m1-m3-'+uuid.uuid4().hex[:10]
    path=ROOT/'.runtime/m3'/project
    hosts=[]
    for n in (1,2,3):
        key=bytes([n])*32;v=b'\3';digest=hashlib.sha3_256(b'.onion checksum'+key+v).digest()[:2]
        hosts.append(base64.b32encode(key+digest+v).decode().lower()+'.onion')
    prepare(path,hosts)
    env=os.environ.copy();env.update(M1_RUN_DIR=str(path),M1_RUN_REL=str(path.relative_to(ROOT)))
    compose=['docker','compose','-p',project,'-f','compose.m1.yaml','-f',str(path/'override.json')]
    def interrupted(signum,frame):
        signal.signal(signum,signal.SIG_IGN)
        raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM,interrupted)
    result={'passed':False,'m3_verified':False,'transport':'isolated local HTTP, not Tor'}
    def cmd(args,name,timeout=60):
        with (path/(name+'.log')).open('w') as out:
            r=subprocess.run(args,cwd=ROOT,env=env,stdout=out,stderr=subprocess.STDOUT,timeout=timeout)
        if r.returncode:raise RuntimeError(name+' failed')
    def dc(args,name,timeout=60):cmd(compose+args,name,timeout)
    try:
        config=json.loads((path/'crawler-conf.yaml').read_text())
        c=config['config'];c.update({'http.protocol.implementation':'org.apache.stormcrawler.protocol.okhttp.HttpProtocol',
            'https.protocol.implementation':'org.apache.stormcrawler.protocol.okhttp.HttpProtocol',
            'http.filter.ipaddress.include':'172.30.71.10','fetcher.threads.number':1,
            'fetchInterval.fetch.error':1,'fetcher.threads.start.delay':0,'urlfrontier.max.messages.in.flight':10})
        (path/'crawler-conf.yaml').write_text(json.dumps(config))
        overrides={'services':{'fixture':{'volumes':['./test/m3:/tests:ro'],
            'command':['python','-u','/tests/fixture.py',*hosts,'--extended','--port','80','--log','/evidence/requests.jsonl'],
            'healthcheck':{'test':['CMD','python','-c',"import socket;socket.create_connection(('localhost',80),2).close()"]}},
            'crawler':{'extra_hosts':{h:'172.30.71.10' for h in hosts},
                'entrypoint':['storm','local',str(path.relative_to(ROOT)/'crawler-m1.jar'),'org.apache.storm.flux.Flux',str(path.relative_to(ROOT)/'topology.flux'),'--local-ttl','100']},
            'probe':{'entrypoint':['java','-cp','/apache-storm/lib/*:/evidence/crawler-m1.jar','dev.onionall.brawler.m2.FrontierProbe']}}}
        (path/'override.json').write_text(json.dumps(overrides))
        shutil.copy2(ROOT/'target/onionall-brawler-0.1.0-SNAPSHOT.jar',path/'crawler-m1.jar')
        source=(ROOT/'test/m2/FrontierProbe.java').read_text().replace('"m2"','"m3"')
        (path/'FrontierProbe.java').write_text(source);(path/'classes').mkdir()
        cmd(['javac','--release','17','-cp',str(path/'crawler-m1.jar'),'-d',str(path/'classes'),str(path/'FrontierProbe.java')],'compile')
        cmd(['jar','--update','--file',str(path/'crawler-m1.jar'),'-C',str(path/'classes'),'.','-C',str(path),'m3-parsefilters.json','-C',str(path),'m3-urlfilters.json'],'package')
        (path/'onion-hostname.txt').write_text(hosts[0])
        dc(['config','--quiet'],'config');dc(['up','-d','--no-build','--pull','never','--wait','--wait-timeout','60','fixture','frontier'],'startup',90)
        dc(['run','--rm','--no-deps','probe','seed','root'],'seed')
        dc(['run','--rm','--no-deps','--name',project+'-crawler','crawler'],'crawl',190)
        dc(['run','--rm','--no-deps','probe','snapshot','final.json'],'snapshot')
        entries=json.loads((path/'final.json').read_text())['entries']
        roots={'http://'+h+'/' for h in hosts}
        rows=[json.loads(line) for line in (path/'requests.jsonl').read_text().splitlines()]
        assert {row['url'] for row in entries}==roots|{'http://'+hosts[0]+'/temporary','http://'+hosts[0]+'/blocked'}, 'Wrong frontier scope'
        assert all(sum(r['host']==h and r['path']=='/' for r in rows)==1 for h in hosts),'Cycle/duplicate requests'
        assert not any(r['path']=='/dump.zip' for r in rows),'Binary link fetched'
        assert all('discovery.edges' in row['metadata'] for row in entries if row['url'] in roots),'Missing persisted edges'
        by_url={row['url']:row for row in entries}
        assert [by_url['http://'+h+'/']['metadata'].get('discovery.depth',['0'])[0] for h in hosts]==['0','1','2'],'Depth propagation lost'
        assert sum(r['path']=='/temporary' for r in rows)==2,'Temporary fetch not retried exactly once'
        assert not any(r['path']=='/blocked' for r in rows),'Robots bypass'
        assert by_url['http://'+hosts[0]+'/']['metadata'].get('discovery.scope.rejected')==['1'],'Rejected scope not audited'
        assert 'M3_RETRY_MARKER' in (path/'crawl.log').read_text(),'Retry content not parsed'
        for row in entries:
            for edge in row['metadata'].get('discovery.edges',[]):
                assert json.loads(edge)['source']==row['url'],'Wrong parent edge provenance'
        result.update(passed=True,frontier_records=len(entries),provenance_persisted=True)
    except (Exception,KeyboardInterrupt) as e:result['blocker']=str(e)
    finally:
        signal.signal(signal.SIGTERM,signal.SIG_IGN)
        try:
            try:dc(['logs','--no-color'],'services')
            finally:
                subprocess.run(['docker','stop','--time','30',project+'-crawler'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=45)
                subprocess.run(['docker','rm','-f',project+'-crawler'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=15)
                dc(['down','--timeout','30'],'cleanup',90)
            result['containers_removed']=not subprocess.check_output(['docker','ps','-aq','--filter','label=com.docker.compose.project='+project],text=True).strip()
            result['networks_removed']=not subprocess.check_output(['docker','network','ls','-q','--filter','label=com.docker.compose.project='+project],text=True).strip()
            result['passed'] &= result['containers_removed'] and result['networks_removed']
        except Exception as e:result.update(passed=False,cleanup_blocker=str(e))
        result['volume_retained']=subprocess.run(['docker','volume','inspect',project+'_frontier-data'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0
        result['passed'] &= result['volume_retained']
        (path/'local-result.json').write_text(json.dumps(result,indent=2));print(path);print(json.dumps(result))
    return 0 if result['passed'] else 1
if __name__=='__main__':raise SystemExit(main())
