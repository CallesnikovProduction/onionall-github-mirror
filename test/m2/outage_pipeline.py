"""Actual StormCrawler outage, persisted failure, and scheduled retry after Tor returns."""
import argparse
import json
from pathlib import Path
import subprocess
import time


def run(project,path):
    compose=['docker','compose','-p',project,'-f','compose.m2.yaml']
    result={'passed':False,'evidence':[]}
    def command(args,name,timeout=180):
        with (path/(name+'.log')).open('w') as log:
            status=subprocess.run(compose+args,stdout=log,stderr=subprocess.STDOUT,timeout=timeout).returncode
        result['evidence'].append(name+'.log')
        if status:
            raise RuntimeError(name+' exited '+str(status))
    def snapshot(name):
        command(['run','--rm','--no-deps','probe','snapshot',name],'snapshot-'+name)
        result['evidence'].append(name)
        return json.loads((path/name).read_text())
    def entries(document):
        return {row['url']:row for row in document['entries']}
    def check(condition,message):
        if not condition:
            raise RuntimeError(message)
    marker=path/'outage-started'
    container=project+'-outage-crawl'
    process=None
    log=None
    try:
        marker.unlink(missing_ok=True)
        command(['up','-d','--wait','--wait-timeout','360','tor'],'pipeline-tor-ready',390)
        command(['run','--rm','--no-deps','probe','seed','outage'],'outage-seed')
        log=(path/'crawl-outage.log').open('w')
        args=compose+['run','--rm','--name',container,'--no-deps','-e','M2_TOPOLOGY=topology-outage.flux','-e','M2_CRAWL_TTL=45','crawler']
        process=subprocess.Popen(args,stdout=log,stderr=subprocess.STDOUT)
        deadline=time.monotonic()+65
        while not marker.exists() and process.poll() is None and time.monotonic()<deadline:
            time.sleep(.2)
        check(marker.exists(),'Active StormCrawler never reached controlled /outage handler')
        result['evidence'] += ['outage-started','crawl-outage.log']
        command(['stop','--timeout','5','tor'],'pipeline-tor-stop',30)
        result['outage_exit_code']=process.wait(timeout=120)
        check(result['outage_exit_code']==0,'Outage crawler process failed')
        log.close();log=None
        failed=snapshot('outage-failed.json')
        host=(path/'onion-hostname.txt').read_text().strip()
        url='http://'+host+'/outage'
        previous=entries(json.loads((path/'final.json').read_text()))
        rows=entries(failed)
        check(set(rows)==set(previous)|{url},'Outage phase changed frontier scope')
        check(all(rows[key]==value for key,value in previous.items()),'Completed state changed during outage')
        row=rows[url]
        check(row['kind']=='known' and row['metadata'].get('fetch.error.count')==['1'],'Active outage failed fetch not persisted exactly once')
        check(bool(row['metadata'].get('fetch.exception')),'Transport failure diagnostic missing from frontier')
        check('M2_OUTAGE_MARKER' not in (path/'crawl-outage.log').read_text(),'Outage response unexpectedly completed')
        command(['up','-d','--wait','--wait-timeout','360','tor'],'pipeline-tor-return',390)
        command(['run','--rm','--no-deps','--entrypoint','python3','crawler',
                 '/workspace/test/m2/network_probe.py','--onion-ready',
                 '--readiness-output','onion-readiness-pipeline.json'],
                'pipeline-onion-ready',120)
        result['evidence'].append('onion-readiness-pipeline.json')
        readiness=json.loads((path/'onion-readiness-pipeline.json').read_text())
        check(readiness.get('ready') is True and readiness.get('passed') is True,
              'Tor returned but controlled onion service is not reachable')
        # Respect the stored retry date; do not reseed or rewrite failure metadata.
        check(row['refetch'] <= time.time()+90,'Outage retry exceeds the test-only one-minute policy')
        while time.time() < row['refetch']:
            time.sleep(min(2,row['refetch']-time.time()))
        command(['run','--rm','--no-deps','-e','M2_TOPOLOGY=topology-outage.flux','-e','M2_CRAWL_TTL=60','crawler'],'crawl-recovered',180)
        result['recovered_exit_code']=0
        recovered=snapshot('outage-recovered.json')
        end=entries(recovered)
        check(set(end)==set(rows) and all(end[key]==value for key,value in previous.items()),'Retry changed unrelated completed state')
        check(end[url]['refetch']>recovered['captured_at_epoch']+3600,'Recovered URL was not successfully rescheduled')
        check('M2_OUTAGE_MARKER' in (path/'crawl-recovered.log').read_text(),'Recovered content not parsed/indexed')
        requests=[json.loads(line) for line in (path/'requests.jsonl').read_text().splitlines()]
        outage=[row for row in requests if row['user_agent']=='OnionallBrawlerMTwoOutage']
        check(sum(row['path']=='/outage' for row in outage)==2,'Expected one interrupted request and one scheduled retry')
        check(all(row['path'] in {'/robots.txt','/outage'} for row in outage),'Outage topology fetched unrelated paths')
        result['passed']=True
        result['failed_fetch_persisted']=True
        result['scheduled_retry_recovered']=True
        result['evidence'] += ['final.json','requests.jsonl']
    except (OSError,RuntimeError,ValueError,subprocess.TimeoutExpired) as error:
        result['blocker']=str(error)
    finally:
        if process is not None and process.poll() is None:
            subprocess.run(['docker','stop','--time','5',container],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=20)
            process.wait(timeout=30)
        if log is not None:
            log.close()
        (path/'outage-pipeline.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    return result['passed']


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('project')
    parser.add_argument('run_dir',type=Path)
    args=parser.parse_args()
    raise SystemExit(0 if run(args.project,args.run_dir.resolve()) else 1)
