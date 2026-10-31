"""Summarize observed Tor bootstrap logs without copying relay identifiers."""
import json
from pathlib import Path
import re
import sys


def summarize(path):
    progress = {'tor': None, 'onion': None}
    log = path / 'services.log'
    text = log.read_text() if log.exists() else ''
    for service, percent in re.findall(r'(?m)^(tor|onion)-\d+[^\n]*Bootstrapped (\d+)%', text):
        progress[service] = int(percent)
    result = {'observed_progress_percent': progress,
              'bootstrap_100_observed': all(value == 100 for value in progress.values()),
              'observation_kind': 'log_history',
              'current_connectivity_verified': False,
              'tls_failure_records': text.count('TLS_ERROR'),
              'evidence': ['services.log', 'startup.log']}
    (path / 'tor-readiness.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    return result


if __name__ == '__main__':
    summarize(Path(sys.argv[1]))
