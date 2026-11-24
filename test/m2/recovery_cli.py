"""Call the same containerized recovery commands as the operator.

Authors: Стефан Колесников and GPT-6.1 Sol.
"""

import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]


def invoke(args, *, environment=None, observation=None):
    bash = 'bash'
    if os.name == 'nt':
        git_tools = subprocess.check_output(['git', '--exec-path'], text=True).strip()
        bash = Path(git_tools).parents[2] / 'bin' / 'bash.exe'
    return subprocess.run([bash, './onionall', *args], cwd=ROOT, env=environment,
                          input=observation, capture_output=True, encoding='utf-8')


def connect_failure(document):
    result = invoke(['check-connect-failure'], observation=json.dumps(document))
    if result.returncode not in (0, 1) or result.stderr:
        raise OSError(result.stderr.strip() or 'Rust recovery classifier failed')
    return result.returncode == 0


def recover(project, path, detection='recovery-detection.json', diagnostics=None, *, environment=None):
    args = ['recover', project, str(path), '--detection', detection]
    if diagnostics is False:
        environment = dict(os.environ if environment is None else environment)
        environment.pop('M2_TOR_DIAGNOSTICS', None)
    if diagnostics is True:
        args.append('--diagnostics')
    result = invoke(args, environment=environment)
    if result.returncode in (0, 1):
        try:
            outcome = json.loads(result.stdout)
        except ValueError:
            pass
        else:
            if isinstance(outcome, dict) and outcome.get('passed') is (result.returncode == 0):
                return outcome
    raise OSError(result.stderr.strip() or 'Rust recovery controller returned no valid result')
