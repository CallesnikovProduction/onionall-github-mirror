"""Run the live failure matrix, preserving every attempted result and subsystem log."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time
from datetime import datetime, timezone

REQUIRED = ['socks_port_unavailable','invalid_socks_endpoint','bootstrap_incomplete',
            'onion_unreachable','malformed_onion','out_of_scope_redirect','redirect_loop',
            'timeout','connection_reset','tor_shutdown_active_crawl','tor_stopped',
            'safe_retry_after_tor_return']


def run(project, path):
    compose=['docker','compose','-p',project,'-f','compose.m2.yaml']
    results={}
    def command(args, name, timeout=120):
        started = datetime.now(timezone.utc).isoformat()
        code = None
        try:
            with (path/(name+'.log')).open('w') as log:
                code = subprocess.run(args, stdout=log, stderr=subprocess.STDOUT, timeout=timeout).returncode
            return code
        finally:
            with (path/'scenario-timeline.jsonl').open('a') as timeline:
                timeline.write(json.dumps(dict(stage=name, started_at_utc=started,
                    finished_at_utc=datetime.now(timezone.utc).isoformat(), exit_code=code))+'\n')
    def probe(name, host=None, background=False):
        args=compose+['run','--rm','--no-deps','--entrypoint','java','probe','-cp',
                     '/apache-storm/lib/*:/evidence/crawler-m2.jar','dev.onionall.brawler.m2.TorScenarioProbe',name]
        if host:
            args.append(host)
        if background:
            log=(path/(name+'.log')).open('w')
            return subprocess.Popen(args,stdout=log,stderr=subprocess.STDOUT),log
        code=command(args,name,90)
        read(name,code)
    def read(name,code):
        evidence='scenario-'+name+'.json'
        try:
            result=json.loads((path/evidence).read_text())
            result['passed']=result.get('passed') is True and code==0
            result['evidence']=[evidence,name+'.log']
        except (OSError,ValueError):
            result={'passed':False,'exit_code':code,'evidence':[name+'.log'],'blocker':'Protocol probe did not produce a result'}
        results[name]=result
    def save():
        policy=json.loads((path/'policy.json').read_text()) if (path/'policy.json').exists() else {}
        results['metadata_bypass']={'passed':policy.get('passed') is True,'evidence':['policy.json']}
        network=json.loads((path/'network.json').read_text()) if (path/'network.json').exists() else {}
        isolation=network.get('passed') is True and network.get('direct_internet',{}).get('connected') is False and network.get('direct_fixture',{}).get('connected') is False
        for value in results.values():
            value['passed'] = value.get('passed') is True and isolation
            value['evidence'].append('network.json')
        for name in REQUIRED:
            if name not in results:
                results[name]={'passed':False,'evidence':[],'blocker':'Scenario not executed after an earlier infrastructure failure'}
        (path/'security-scenarios.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
    unready=project+'-unbootstrapped'
    try:
        # Fresh Tor state with DisableNetwork prevents bootstrap by construction; confirm
        # actual bootstrap status over its authenticated local Unix socket before probing.
        code=command(compose+['run','-d','--no-deps','--name',unready,'tor','-f','/etc/tor/torrc.gateway',
                              '--DataDirectory','/tmp/unbootstrapped','--ControlSocket','/tmp/unbootstrapped/control',
                              '--CookieAuthFile','/tmp/unbootstrapped/cookie','--DisableNetwork','1'],'unbootstrapped-start')
        if code==0:
            control="""import socket,time,pathlib,json,re
for attempt in range(40):
 try:
  s=socket.socket(socket.AF_UNIX);s.settimeout(2);s.connect('/tmp/unbootstrapped/control')
  cookie=pathlib.Path('/tmp/unbootstrapped/cookie').read_bytes().hex()
  reader=s.makefile('rb')
  s.sendall(('AUTHENTICATE '+cookie+'\\r\\n').encode())
  if not reader.readline().startswith(b'250 '): raise SystemExit('Control authentication failed')
  s.sendall(b'GETINFO status/bootstrap-phase\\r\\n');lines=[]
  for line in reader:
   lines.append(line.decode())
   if line.startswith(b'250 '):break
  response=''.join(lines);s.close()
  observed=re.search(r'PROGRESS=(\\d+)',response)
  print(json.dumps({'observed_progress_percent':int(observed.group(1)) if observed else None}))
  if 'PROGRESS=100' in response or 'PROGRESS=' not in response:raise SystemExit('Fresh Tor unexpectedly bootstrapped')
  break
 except OSError:
  time.sleep(.25)
else: raise SystemExit('Unbootstrapped Tor control unavailable')
"""
            status=command(['docker','exec',unready,'python3','-c',control],'bootstrap-status',30)
            if status==0:
                probe('bootstrap_incomplete',unready)
                results['bootstrap_incomplete']['evidence'].append('bootstrap-status.log')
            else:
                results['bootstrap_incomplete']={'passed':False,'evidence':['bootstrap-status.log']}
        for name in ['socks_port_unavailable','invalid_socks_endpoint','onion_unreachable','malformed_onion',
                     'out_of_scope_redirect','redirect_loop','timeout','connection_reset']:
            if name == 'timeout':
                (path/'slow-started').unlink(missing_ok=True)
            before=(path/'requests.jsonl').read_text().splitlines()
            if name == 'onion_unreachable':
                stopped=command(compose+['stop','--timeout','5','onion'],'controlled-onion-stop',30)
                if stopped:
                    results[name]={'passed':False,'evidence':['controlled-onion-stop.log'],
                                   'blocker':'Controlled onion daemon could not be stopped'}
                    continue
                gateway=command(compose+['exec','-T','tor','python3','-c',
                    "import socket; s=socket.create_connection(('127.0.0.1',9050),timeout=2); "
                    "s.settimeout(2); s.sendall(b'\\x05\\x01\\x00'); "
                    "assert s.recv(2)==b'\\x05\\x00'; s.close()"], 'controlled-onion-gateway-live',10)
            probe(name)
            if name == 'onion_unreachable':
                # Require a live SOCKS greeting while our own service is stopped.
                # This is endpoint liveness, not proof of Tor circuit health.
                restarted=command(compose+['up','-d','--wait','--wait-timeout','90','onion'],
                                  'controlled-onion-return',110)
                ready=command(compose+['run','--rm','--no-deps','--entrypoint','python3','crawler',
                              '/workspace/test/m2/network_probe.py','--onion-ready',
                              '--readiness-output','onion-readiness-controlled-return.json'],
                              'controlled-onion-ready',110) if restarted==0 else 1
                results[name]['passed'] &= gateway==0 and restarted==0 and ready==0
                results[name]['evidence'] += ['controlled-onion-stop.log','controlled-onion-return.log',
                                              'controlled-onion-gateway-live.log','controlled-onion-ready.log',
                                              'onion-readiness-controlled-return.json']
                if restarted != 0 or ready != 0:
                    raise OSError('Controlled onion unavailable after restart; dependent scenarios not executed')
            # Correlate protocol errors with actual network policy and fixture traffic.
            after=(path/'requests.jsonl').read_text().splitlines()
            rows=[json.loads(line) for line in after[len(before):]]
            policy_rows=[row for row in rows if row.get('user_agent') == 'OnionallBrawlerMTwoPolicy']
            if name in {'socks_port_unavailable','invalid_socks_endpoint','onion_unreachable','malformed_onion'}:
                results[name]['passed'] &= not policy_rows
                results[name]['fixture_requests'] = len(policy_rows)
            if name == 'connection_reset':
                results[name]['passed'] &= any(row['path']=='/reset' and row['status']==0 for row in policy_rows)
            if name == 'out_of_scope_redirect':
                results[name]['passed'] &= any(row['path']=='/escape' and row['status']==302 for row in policy_rows)
            if name == 'redirect_loop':
                results[name]['passed'] &= [row['path'] for row in policy_rows] == ['/loop-a']
            results[name]['evidence'].append('requests.jsonl')
        # Wait for the real fixture handler to start /slow before stopping Tor.
        marker=path/'slow-started'
        marker.unlink(missing_ok=True)
        process,log=probe('tor_shutdown_active_crawl',background=True)
        try:
            deadline=time.monotonic()+45
            while not marker.exists() and process.poll() is None and time.monotonic()<deadline:
                time.sleep(.2)
            started=marker.exists()
            code=command(compose+['stop','--timeout','5','tor'],'tor-active-stop',30)
            process.wait(timeout=45)
            read('tor_shutdown_active_crawl',process.returncode)
            results['tor_shutdown_active_crawl']['passed'] &= started and code==0
            results['tor_shutdown_active_crawl']['fixture_request_started']=started
            results['tor_shutdown_active_crawl']['evidence'] += ['tor-active-stop.log','slow-started'] if started else ['tor-active-stop.log']
        finally:
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=10)
            log.close()
        probe('tor_stopped')
        code=command(compose+['up','-d','--wait','--wait-timeout','360','tor'],'scenario-tor-return',390)
        if code==0:
            ready=command(compose+['run','--rm','--no-deps','--entrypoint','python3','crawler',
                          '/workspace/test/m2/network_probe.py','--onion-ready',
                          '--readiness-output','onion-readiness-scenarios.json'],
                          'scenario-onion-ready',120)
            if ready==0:
                probe('safe_retry_after_tor_return')
                results['safe_retry_after_tor_return']['evidence'] += ['scenario-onion-ready.log','onion-readiness-scenarios.json']
            else:
                results['safe_retry_after_tor_return']={'passed':False,'evidence':['scenario-onion-ready.log','onion-readiness-scenarios.json'],'blocker':'Tor bootstrap completed but controlled onion remains unavailable'}
        else:
            results['safe_retry_after_tor_return']={'passed':False,'evidence':['scenario-tor-return.log']}
    except (OSError,subprocess.TimeoutExpired) as error:
        results['driver_failure']={'passed':False,'evidence':[],'blocker':str(error)}
    finally:
        command(['docker','rm','-f',unready],'unbootstrapped-cleanup',30)
        save()
    return all(result.get('passed') is True for result in results.values())


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('project')
    parser.add_argument('run_dir',type=Path)
    args=parser.parse_args()
    raise SystemExit(0 if run(args.project,args.run_dir.resolve()) else 1)
