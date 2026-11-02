"""One bounded same-container experiment. Never a replacement for M2 acceptance."""
import argparse,json,os,shutil,subprocess,sys,signal,hashlib,re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts'))
from m2 import Runner
from restart_recovery import host_state

def cold_gateway_override(path, project):
    """Diagnostic-only new generation; never delete old state or onion identity."""
    if not re.fullmatch(r'onionall-brawler-m2-[a-f0-9]{12}', project):
        raise ValueError('Expected owned isolated M2 project')
    directory='/var/lib/tor/client-generations/'+project.rsplit('-',1)[1]
    destination=path/'cold-gateway.json'
    with destination.open('x') as output:
        json.dump({'services':{'tor':{'command':['--DataDirectory',directory]}}},output)
    return destination

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cold-gateway-on-failure',action='store_true',help='One diagnostic replacement with fresh client directory; preserve old volume/state')
    args=parser.parse_args()
    r=Runner();p=r.path;subject=r.project+'-incident';result={'passed':False,'m2_verified':False,'comparisons':[]}
    before=host_state();r.save('host-before.json',before)
    def dc(args,name,**kw):
        print(name,flush=True);kw.setdefault('timeout',40)
        return r.dc(args,name+'.log',**kw)
    def compare(phase):
        python="""import sys,json,time;from pathlib import Path
sys.path.insert(0,'/workspace/test/m2');from network_probe import single_health,remote_fetch
p=Path('/evidence');host=(p/'onion-hostname.txt').read_text().strip()
if len(sys.argv)>2:
 r={'passed':False};start=time.monotonic()
 try:r['health']=remote_fetch(host,deadline=start+20,observation=r,jdk_auth=True);r['passed']=True
 except OSError as e:r['error_type']=type(e).__name__
 r['elapsed_seconds']=time.monotonic()-start
else:r=single_health(host,20)
r['network_namespace']=str(Path('/proc/self/ns/net').readlink());(p/sys.argv[1]).write_text(json.dumps(r))
"""
        # docker exec preserves exactly the same container, source IP and network namespace.
        r.command(['docker','exec',subject,'python3','-c',python,phase+'-python.json'],phase+'-python.log',timeout=25)
        r.command(['docker','exec',subject,'python3','-c',python,phase+'-python-auth.json','auth'],phase+'-python-auth.log',timeout=25)
        for mode in ('native','protocol'):
            r.command(['docker','exec',subject,'java','-cp','/apache-storm/lib/*:/apache-storm/lib-common/*:/evidence/crawler-m2.jar','dev.onionall.brawler.m2.SocksIncidentProbe',mode,phase+'-'+mode+'.json'],phase+'-'+mode+'.log',allow_failure=True,timeout=30)
        rows={m:json.loads((p/(phase+'-'+m+'.json')).read_text()) for m in ('python','python-auth','native','protocol')}
        assert len({v['network_namespace'] for v in rows.values()})==1
        result['comparisons'].append(dict(phase=phase,results=rows));r.save('incident.json',result)
        return all(v['passed'] for v in rows.values())
    def stop(signum,frame):raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM,stop)
    try:
        r.validate_toolchain()
        r.save('compose.json',json.loads(dc(['--profile','tools','config','--format','json'],'config',capture=True).stdout))
        r.started=True
        dc(['up','-d','--no-build','--pull','never','--wait','--wait-timeout','120','fixture','tor','onion','frontier'],'startup',timeout=145)
        hostname=dc(['exec','-T','onion','cat','/var/lib/tor/hidden_service/hostname'],'identity',capture=True).stdout.strip();(p/'onion-hostname.txt').write_text(hostname)
        r.command([sys.executable,'-B','test/m2/prepare.py',p],'prepare.log')
        artifact=ROOT/'target/onionall-brawler-0.1.0-SNAPSHOT.jar';shutil.copy2(artifact,p/'crawler-m2.jar')
        r.save('artifact.json',{'source_sha256':hashlib.sha256(artifact.read_bytes()).hexdigest()})
        cp=str(artifact)+os.pathsep+(ROOT/'target/cli-classpath.txt').read_text().strip()
        r.command([r.tool('javac'),'--release','25','-cp',cp,'-d',p/'classes',ROOT/'test/m2/SocksIncidentProbe.java',ROOT/'test/m2/FrontierProbe.java'],'compile.log')
        r.command([r.tool('jar'),'--update','--file',p/'crawler-m2.jar','-C',p/'classes','.','-C',p/'resources','.'],'package.log')
        dc(['run','-d','--no-deps','--name',subject,'--entrypoint','python3','crawler','-c','import time;time.sleep(900)'],'client-start')
        if not compare('initial'):raise RuntimeError('Initial equivalent-deadline comparison failed; no restart attempted')
        dc(['restart','--timeout','5','onion'],'onion-restart',timeout=30)
        # Bootstrap and live stream availability remain separate; no arbitrary sleep.
        compare('service-return')
        dc(['restart','--timeout','5','tor'],'gateway-restart',timeout=30)
        dc(['up','-d','--no-build','--no-recreate','--pull','never','--wait','--wait-timeout','90','tor'],'gateway-bootstrap',timeout=110)
        recovered=compare('gateway-return')
        if not recovered and args.cold_gateway_on_failure:
            # Replacement changes IP; every compared client is newly constructed.
            # No configured/live crawler may resume across this replacement.
            result['cold_replacement_attempted']=True
            dc(['stop','--timeout','5','tor'],'cold-gateway-stop',timeout=25)
            override=cold_gateway_override(p,r.project)
            r.compose += ['-f',str(override)]
            dc(['up','-d','--no-deps','--force-recreate','--no-build','--pull','never',
                '--wait','--wait-timeout','120','tor'],'cold-gateway-start',timeout=145)
            recovered=compare('cold-gateway-return')
        if not recovered:raise RuntimeError('Same-context recovery comparison failed; crawler not started')
        # Only after all equivalent-context clients pass, exercise the actual
        # Storm fetcher in that same container, retaining normal robots/policy.
        dc(['run','--rm','--no-deps','probe','ready'],'frontier-ready')
        dc(['run','--rm','--no-deps','probe','seed','pending'],'seed-pending')
        r.command(['docker','exec','-e','M2_CRAWL_TTL=45',subject,'bash',
            '/workspace/scripts/m2-container.sh'],'crawler-path.log',timeout=130)
        dc(['run','--rm','--no-deps','probe','snapshot','crawler-final.json'],'frontier-final')
        snapshot=json.loads((p/'crawler-final.json').read_text())
        entries=snapshot['entries']
        if not (len(entries)==1 and entries[0]['refetch']>snapshot['captured_at_epoch']+3600
                and not entries[0]['metadata'].get('fetch.error.count')
                and 'M2_PENDING_MARKER' in (p/'crawler-path.log').read_text()):
            raise RuntimeError('Actual crawler did not consume and reschedule pending URL')
        result['crawler_path_passed']=True
        result['passed']=True
    except (OSError,ValueError,RuntimeError,AssertionError,subprocess.SubprocessError,KeyboardInterrupt) as e:result['blocker']=type(e).__name__+': '+str(e)
    finally:
        signal.signal(signal.SIGTERM,signal.SIG_IGN)
        signal.signal(signal.SIGINT,signal.SIG_IGN)
        try:
            r.command(['docker','rm','-f',subject],'client-cleanup.log',allow_failure=True,timeout=20)
        finally:
            try:dc(['logs','--no-color'],'services',allow_failure=True)
            finally:dc(['down','--timeout','5'],'cleanup',allow_failure=True,timeout=60)
        result['host_unchanged']=before==host_state();r.save('host-after.json',host_state())
        result['containers_removed']=not subprocess.check_output(['docker','ps','-aq','--filter','label=com.docker.compose.project='+r.project],text=True).strip()
        result['networks_removed']=not subprocess.check_output(['docker','network','ls','-q','--filter','label=com.docker.compose.project='+r.project],text=True).strip()
        v=r.command(['docker','volume','inspect',*[r.project+'_'+k for k in ('frontier-data','tor-state','onion-state')]],capture=True,allow_failure=True,timeout=15)
        result['volumes_retained']=v.returncode==0 and len(json.loads(v.stdout or '[]'))==3
        result['passed'] &= all(result[k] for k in ('host_unchanged','containers_removed','networks_removed','volumes_retained'))
        r.save('incident.json',result);print(p,flush=True)
    return 0 if result['passed'] else 1
if __name__=='__main__':raise SystemExit(main())
