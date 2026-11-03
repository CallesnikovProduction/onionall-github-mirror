"""One passive retained-vs-cold pair; diagnostic evidence never M2 acceptance."""
import json
import subprocess
import time
from pathlib import Path

PAIR_CODE = '''import concurrent.futures,json,sys,threading,time
from pathlib import Path
from datetime import datetime,timezone
sys.path.insert(0,'/workspace/test/m2')
from network_probe import remote_fetch
p=Path('/evidence');host=(p/'onion-hostname.txt').read_text().strip();barrier=threading.Barrier(2)
def request(proxy):
 r={'passed':False,'proxy':proxy,'network_namespace':str(Path('/proc/self/ns/net').readlink()),'socks_method_offer':'NO_AUTH','deadline_seconds':20}
 barrier.wait(timeout=3);start=time.monotonic();r['started_at_utc']=datetime.now(timezone.utc).isoformat()
 try:r['health']=remote_fetch(host,deadline=start+20,observation=r,proxy_host=proxy);r['passed']=True
 except OSError as e:r['error_type']=type(e).__name__
 r['elapsed_seconds']=time.monotonic()-start;return r
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
 futures={name:pool.submit(request,name) for name in ('tor','cold')}
 rows={name:f.result(timeout=25) for name,f in futures.items()}
(p/'retained-cold-pair.json').write_text(json.dumps(rows,indent=2))
'''


def observer_override(config, project):
    """Clone only the gateway policy into a separate service, never replace tor."""
    import copy
    service = copy.deepcopy(config['services']['tor'])
    service.pop('build', None)
    service.pop('container_name', None)
    directory = '/var/lib/tor/client-generations/' + project.rsplit('-', 1)[1]
    service['command'] = ['--DataDirectory', directory, '--ControlSocket', directory+'/control', '--CookieAuthFile', directory+'/control_auth_cookie']
    health = (Path(__file__).resolve().parents[2]/'docker/m2/health.py').read_text().replace('/var/lib/tor/control', directory+'/control')
    service['healthcheck'] = {'test':['CMD','python3','-c',health], 'interval':'2s','timeout':'4s','retries':60}
    service['depends_on'] = {}
    return {'services': {'cold': service}}


def records(path):
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


def publication_observed(events):
    return any(e.get('kind') == 'HS_DESC' and e.get('action') == 'UPLOADED'
               and e.get('blinded_key_hash') and isinstance(e.get('revision_counter'), int)
               and isinstance(e.get('period'), int) for e in events)


def interpret(pair, telemetry):
    """Only classify path/state after revision, stream and publication evidence."""
    required = ('retained-events.jsonl', 'cold-events.jsonl', 'service-events.jsonl')
    if any(not any(e.get('kind') == 'watch_started' for e in telemetry.get(name, [])) for name in required):
        return 'INCONCLUSIVE', 'Required passive observers unavailable'
    service = telemetry['service-events.jsonl']
    uploaded = {(e.get('blinded_key_hash'), e.get('revision_counter'), e.get('period'))
                for e in service if e.get('kind') == 'HS_DESC' and e.get('action') == 'UPLOADED'
                and e.get('blinded_key_hash') and isinstance(e.get('revision_counter'),int)
                and isinstance(e.get('period'),int)}
    cold = telemetry['cold-events.jsonl']
    correlation = any(len({period for key,revision,period in uploaded
                         if key == e.get('blinded_key_hash') and revision == e.get('revision_counter')}) == 1
                      for e in cold if e.get('kind') == 'HS_DESC_CONTENT')
    if not correlation or any(not any(e.get('kind') == 'STREAM' for e in telemetry[name]) for name in required[:2]):
        return 'INCONCLUSIVE', 'Missing revision/period/publication or stream correlation; no causal attribution'
    if pair['tor']['passed'] and pair['cold']['passed']:
        return 'NOT_REPRODUCED', 'Both paths reached the destination'
    if not pair['tor']['passed'] and not pair['cold']['passed']:
        return 'DEPENDENCY_UNAVAILABLE', 'No positive destination reachability control'
    if pair['cold']['passed']:
        return 'STATE_PATH_DIFFERENCE', 'Fresh-state path succeeds while restarted retained-state path fails; disk corruption is not established'
    return 'INCONCLUSIVE', 'Only retained path succeeds; cold state is not sufficient for reachability'


def run(runner, subject, config, deadline, previous):
    """Called only after the single normal gateway restart still fails CONNECT."""
    path = runner.path
    watchers = []
    result = {'status': 'INCONCLUSIVE', 'm2_verified': False, 'pair_attempts': 0}
    def budget(maximum):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError('Retained/cold experiment ceiling exceeded')
        return min(maximum, remaining)
    def start_watch(service, filename):
        cid = runner.dc(['ps', '-q', service], capture=True, timeout=budget(10)).stdout.strip()
        output = (path / filename).open('w')
        watch_args = ['docker', 'exec']
        if service == 'cold':
            watch_args += ['-e', 'TOR_OBSERVER_ROOT=/var/lib/tor/client-generations/'+runner.project.rsplit('-',1)[1]]
        proc = subprocess.Popen(watch_args + [cid, 'python3', '-u', '-c',
            (Path(__file__).with_name('tor_observer.py')).read_text()], cwd=Path(__file__).resolve().parents[2],
            env=runner.environment, stdout=output, stderr=subprocess.DEVNULL)
        watchers.append((proc, output))
        limit = time.monotonic() + budget(8)
        while time.monotonic() < limit and proc.poll() is None:
            if any(e.get('kind') == 'watch_started' for e in records(path / filename)):
                return
            time.sleep(.1)
        raise RuntimeError('Passive observer unavailable: ' + service)
    try:
        identity = runner.dc(['ps', '-q', 'tor'], capture=True, timeout=budget(10)).stdout.strip()
        stamp = runner.command(['docker', 'inspect', '--format', '{{.State.StartedAt}}', identity], capture=True, timeout=budget(10)).stdout.strip()
        result['retained_container'] = identity
        result['retained_started_at'] = stamp
        if identity != previous['container'] or stamp == previous['started_at']:
            raise RuntimeError('Retained control restart identity/start-time not verified')
        start_watch('tor', 'retained-events.jsonl')
        # service observer is started by caller immediately after onion restart.
        override = path / 'cold-observer.json'
        with override.open('x') as output:
            json.dump(observer_override(config, runner.project), output)
        runner.compose += ['-f', str(override)]
        runner.dc(['up', '-d', '--no-deps', '--no-build', '--pull', 'never', '--wait',
                   '--wait-timeout', '120', 'cold'], 'cold-observer-start.log', timeout=budget(145))
        start_watch('cold', 'cold-events.jsonl')
        if not publication_observed(records(path / 'service-events.jsonl')):
            raise RuntimeError('Publication revision/period not observed before pair; no pair attempted')
        runner.command(['docker', 'exec', subject, 'python3', '-c', PAIR_CODE], 'retained-cold-pair.log', timeout=budget(30))
        result['pair_attempts'] = 1
        pair = json.loads((path / 'retained-cold-pair.json').read_text())
        if pair['tor']['network_namespace'] != pair['cold']['network_namespace']:
            raise RuntimeError('Pair requester namespaces differ')
        telemetry = {name: records(path/name) for name in ('retained-events.jsonl','cold-events.jsonl','service-events.jsonl')}
        result['status'], result['interpretation'] = interpret(pair, telemetry)
        result['evidence'] = ['retained-cold-pair.json', *telemetry]
        result['retained_container_preserved'] = runner.dc(['ps', '-q', 'tor'], capture=True, timeout=budget(10)).stdout.strip() == identity
        if not result['retained_container_preserved']:
            result.update(status='INCONCLUSIVE', interpretation='Retained control container changed')
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        result['blocker'] = type(error).__name__ + ': ' + str(error)
    finally:
        for proc, output in watchers:
            if proc.poll() is None:
                proc.terminate()
                try: proc.wait(timeout=5)
                except subprocess.TimeoutExpired: proc.kill(); proc.wait(timeout=5)
            output.close()
        runner.save('retained-cold-result.json', result)
    return result
